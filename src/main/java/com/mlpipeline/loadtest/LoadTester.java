package com.mlpipeline.loadtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple concurrent load generator / stress tool for the gateway.
 *
 * Usage:
 *   java -cp out com.mlpipeline.loadtest.LoadTester [numRequests] [concurrency]
 *
 * This produces the "load-test table with throughput and latency" and the
 * "stress-test output for concurrency correctness" evidence required by
 * the assignment. Running it with a concurrency higher than
 * (worker threads + queue capacity) will intentionally trigger HTTP 503
 * backpressure responses - that's expected and is the point of the demo.
 */
public class LoadTester {

    static final String[] SAMPLE_TEXTS = {
            "This is an excellent product, I love it!",
            "Terrible experience, worst service ever.",
            "It was okay, nothing special.",
            "Amazing quality and fantastic support team.",
            "I am very disappointed and angry about this.",
            "Pretty good overall, would recommend.",
            "Awful. Just awful. Never again.",
            "Neutral opinion, no strong feelings either way."
    };

    public static void main(String[] args) throws Exception {
        int numRequests = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 50;

        System.out.println("=== Load Test: " + numRequests + " requests, concurrency=" + concurrency + " ===");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < numRequests; i++) {
            final int idx = i;
            // Give every request a unique idempotency key so this load test measures
            // genuine queue/backpressure behavior rather than idempotency collapsing.
            String baseText = SAMPLE_TEXTS[i % SAMPLE_TEXTS.length];
            tasks.add(() -> {
                long start = System.currentTimeMillis();
                try {
                    String body = "{\"text\":\"" + baseText.replace("\"", "\\\"") + "\",\"idempotencyKey\":\"loadtest-" + idx + "-" + System.nanoTime() + "\"}";
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8080/classify"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                    long latency = System.currentTimeMillis() - start;
                    latencies.add(latency);
                    switch (resp.statusCode()) {
                        case 202 -> ok.incrementAndGet();
                        case 503 -> rejected.incrementAndGet();
                        case 200 -> duplicates.incrementAndGet();
                        default -> otherErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                }
                return null;
            });
        }

        long wallStart = System.currentTimeMillis();
        pool.invokeAll(tasks);
        long wallEnd = System.currentTimeMillis();
        pool.shutdown();

        double wallSeconds = (wallEnd - wallStart) / 1000.0;
        double throughput = numRequests / wallSeconds;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        System.out.println();
        System.out.println("---------------------------------------------------------------");
        System.out.printf("Total requests      : %d%n", numRequests);
        System.out.printf("Accepted (202)       : %d%n", ok.get());
        System.out.printf("Rejected (503, backpressure) : %d%n", rejected.get());
        System.out.printf("Duplicates (200)     : %d%n", duplicates.get());
        System.out.printf("Other errors         : %d%n", otherErrors.get());
        System.out.printf("Wall time            : %.2f s%n", wallSeconds);
        System.out.printf("Throughput           : %.1f req/s%n", throughput);
        if (!sorted.isEmpty()) {
            System.out.printf("Latency p50          : %d ms%n", pct(sorted, 0.50));
            System.out.printf("Latency p95          : %d ms%n", pct(sorted, 0.95));
            System.out.printf("Latency p99          : %d ms%n", pct(sorted, 0.99));
            System.out.printf("Latency max          : %d ms%n", sorted.get(sorted.size() - 1));
        }
        System.out.println("---------------------------------------------------------------");
        System.out.println("Tip: query http://localhost:8080/metrics after this run for");
        System.out.println("server-side completed/failed/rejected counts and end-to-end p50/p95/p99.");
    }

    static long pct(List<Long> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }
}
