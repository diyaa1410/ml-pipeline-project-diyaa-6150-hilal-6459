package com.mlpipeline.pipeline;

import com.mlpipeline.util.SimpleJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Stage 2 of the pipeline: calls out to the Python inference microservice.
 *
 * This is the project's required "real network boundary" (Section 4 of the
 * brief): a Java service calling a Python ML service over HTTP, with JSON
 * serialization, a timeout, and failure handling.
 *
 * A Semaphore bulkhead caps how many concurrent outbound calls we allow,
 * so a slow or overloaded Python service can't let unbounded work pile up
 * on the Java side (this is our second bounded resource, separate from the
 * gateway's bounded thread pool queue).
 */
public class InferenceClient {

    private static final String ML_SERVICE_URL = "http://localhost:8000/predict";
    private static final int MAX_CONCURRENT_CALLS = 20;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    private final Semaphore bulkhead = new Semaphore(MAX_CONCURRENT_CALLS);

    public static final class Result {
        public final String label;
        public final double confidence;
        public Result(String label, double confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }

    public CompletableFuture<Result> classify(String text) {
        boolean acquired;
        try {
            // Don't block forever waiting for a permit - if the bulkhead itself
            // is saturated, fail fast so the caller's timeout/fallback kicks in.
            acquired = bulkhead.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
        if (!acquired) {
            return CompletableFuture.failedFuture(new RuntimeException("bulkhead saturated: too many in-flight inference calls"));
        }

        String jsonBody = "{\"text\":\"" + SimpleJson.escape(text) + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ML_SERVICE_URL))
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        CompletableFuture<Result> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("inference service returned HTTP " + resp.statusCode());
                    }
                    Map<String, Object> parsed = SimpleJson.parseObject(resp.body());
                    String label = String.valueOf(parsed.getOrDefault("label", "neutral"));
                    Object confObj = parsed.get("confidence");
                    double confidence = (confObj instanceof Double) ? (Double) confObj : 0.5;
                    return new Result(label, confidence);
                });

        future.whenComplete((r, ex) -> bulkhead.release());
        return future;
    }

    public int availablePermits() {
        return bulkhead.availablePermits();
    }
}
