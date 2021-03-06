package com.couchbase.bigfun;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.PersistTo;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.env.CouchbaseEnvironment;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;
import com.couchbase.client.java.error.TemporaryFailureException;

import java.nio.charset.UnsupportedCharsetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.lang.Thread;
import java.util.function.Consumer;
import java.nio.charset.Charset;
import java.io.UnsupportedEncodingException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;

import com.google.gson.stream.JsonReader;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import com.google.gson.JsonParseException;

public class LoadTarget {

    public class CBASQueryResult {
        public String status;
        public CBASQueryMetrics metrics;
        public CBASQueryResult(String status, CBASQueryMetrics metrics) {
            this.status = status;
            this.metrics = metrics;
        }
    }

    public class CBASQueryMetrics {
        public String elapseTime;
        public String executionTime;
        public long resultCount;
        public long resultSize;
        public CBASQueryMetrics(String elapseTime, String executionTime, long resultCount, long resultSize) {
            this.elapseTime = elapseTime;
            this.executionTime = executionTime;
            this.resultCount = resultCount;
            this.resultSize = resultSize;
        }
    }

    private TargetInfo targetInfo;
    private static CouchbaseEnvironment env = DefaultCouchbaseEnvironment.create();
    private Cluster cluster;
    private Bucket bucket;
    protected long timeout;
    private HttpClient cbasClient;

    private String getCurrentTime() {
        Date d = new Date();
        SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
        return sf.format(d);
    }

    public LoadTarget(TargetInfo targetInfo) {
        this.targetInfo = targetInfo;
        this.timeout = env.kvTimeout();
        if (targetInfo.host.equals("")) {
            this.cluster = null;
            this.bucket = null;
        } else {
            this.cluster = CouchbaseCluster.create(env, targetInfo.host);
            this.bucket = targetInfo.password.equals("") ?
                    cluster.openBucket(targetInfo.bucket) : cluster.openBucket(targetInfo.bucket, targetInfo.password);
        }
        this.cbasClient = HttpClientBuilder.create().build();
    }

    public void upsert(JsonDocument doc) {
        Consumer<JsonDocument> op = (x) -> this.upsertWithoutRetry(x);
        retryDocumentOperation(op, doc);
        return;
    }

    public void delete(JsonDocument doc) {
        Consumer<JsonDocument> op = (x) -> this.deleteWithoutRetry(x);
        retryDocumentOperation(op, doc);
        return;
    }

    public CBASQueryResult cbasQuery(String query, int retryNum) {
        CBASQueryResult result = null;
        if (!this.targetInfo.cbashost.equals("")) {
            int retryCnt = 0;
            while (true) {
                HttpPost post = createCbasPost();
                String data = createCbasQueryJson(query);
                try {
                    post.setEntity(new StringEntity(data));
                    HttpResponse response = cbasClient.execute(post);
                    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                        System.out.println(this.getCurrentTime() + String.format(" Invalid query call with status code %d", response.getStatusLine().getStatusCode()));
                        throw new RuntimeException(String.format("Invalid query call with status code %d", response.getStatusLine().getStatusCode()));
                    }
                    result = parseCbasQueryResultStream(response.getEntity().getContent());
                    break;
                } catch (IOException e) {
                    System.out.println(this.getCurrentTime() + " IOException occured while sending http request : " + e);
                    if (retryCnt++ > retryNum) {
                        System.out.println(this.getCurrentTime() + " Retry too much times after IOException");
                        throw new RuntimeException("Retry too much times after IOException", e);
                    }
                    try {
                        Thread.sleep(this.timeout);
                        System.out.println(this.getCurrentTime() + String.format(" Retry %d after IOException", retryCnt));
                    }
                    catch (InterruptedException ie) {
                        System.err.println(e.toString());
                        System.exit(-1);
                    }
                } finally {
                    post.releaseConnection();
                }
            }
        }
        return result;
    }

    private CBASQueryResult parseCbasQueryResultStream(InputStream istream) throws IOException {
        CBASQueryResult result = null;
        CBASQueryMetrics metrics = null;
        String status = "";
        JsonReader reader = null;
        try {
            reader = new JsonReader(new InputStreamReader(istream, "UTF-8"));
            reader.beginObject();
            while (reader.hasNext()) {
                String fieldname = reader.nextName();
                switch (fieldname) {
                    case "requestID":
                    case "signature":
                    case "results":
                    case "errors":
                        reader.skipValue();
                        break;
                    case "status":
                        status = reader.nextString();
                        break;
                    case "metrics":
                        metrics = parseCbaseQueryResultStreamMetrics(reader);
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        }
        finally {
            if (reader != null) {
                reader.close();
            }
        }
        return new CBASQueryResult(status, metrics);
    }

    private CBASQueryMetrics parseCbaseQueryResultStreamMetrics(JsonReader reader) throws IOException {
        String elapsedTime = "";
        String executionTime = "";
        long resultCount = 0;
        long resultSize = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String fieldname = reader.nextName();
            switch (fieldname) {
                case "elapsedTime":
                    elapsedTime = reader.nextString();
                    break;
                case "executionTime":
                    executionTime = reader.nextString();
                    break;
                case "resultCount":
                    resultCount = reader.nextLong();
                    break;
                case "resultSize":
                    resultSize = reader.nextLong();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        return new CBASQueryMetrics(elapsedTime, executionTime, resultCount, resultSize);
    }

    private String createCbasQueryJson(String query) {
        return this.createCbasQueryJson(query, 900);
    }

    private String createCbasQueryJson(String query, int timeout) {
        JsonObject queryObj = JsonObject.create();
        queryObj.put("statement", query);
        queryObj.put("timeout", String.format("%ds", timeout));
        queryObj.put("pretty", true);
        return queryObj.toString();
    }

    private HttpPost createCbasPost() {
        String restUrl = String.format("http://%s:8095/analytics/service", this.targetInfo.cbashost);
        HttpPost post = new HttpPost(restUrl);
        String auth=new StringBuffer(this.targetInfo.username).append(":").append(this.targetInfo.password).toString();
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(Charset.forName("US-ASCII")));
        String authHeader = "Basic " + new String(encodedAuth);
        post.setHeader("AUTHORIZATION", authHeader);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Accept", "application/json");
        post.setHeader("X-Stream" , "true");
        return post;
    }

    private void retryDocumentOperation(Consumer<JsonDocument> operation, JsonDocument doc) {
        while (true) {
            try {
                operation.accept(doc);
                break;
            } catch (RuntimeException e) {
                if (e instanceof TemporaryFailureException || e.getCause() instanceof TimeoutException) {
                    System.out.println(this.getCurrentTime() + "+++ caught " + e.toString() + " +++");
                    try {
                        Thread.sleep(this.timeout);
                    }
                    catch (InterruptedException ie) {
                        System.err.println(e.toString());
                        System.exit(-1);
                    }
                    System.out.println(this.getCurrentTime() + "+++ new timeout " + timeout + " +++");
                } else {
                    System.out.println(this.getCurrentTime() + "+++ throw " + e.toString() + " +++");
                    try {
                        Thread.sleep(this.timeout);
                    }
                    catch (InterruptedException ie) {
                        System.err.println(e.toString());
                        System.exit(-1);
                    }
                    throw e;
                }
            }
        }
    }

    protected void upsertWithoutRetry(JsonDocument doc) {
        if (this.bucket != null) {
            this.bucket.upsert(doc, PersistTo.NONE, timeout, TimeUnit.MILLISECONDS);
        }
    }

    protected void deleteWithoutRetry(JsonDocument doc) {
        if (this.bucket != null) {
            this.bucket.remove(doc, PersistTo.NONE, timeout, TimeUnit.MILLISECONDS);
        }
    }

    public void close() {
        if (bucket != null) {
            bucket.close();
            cluster.disconnect();
        }
    }
}
