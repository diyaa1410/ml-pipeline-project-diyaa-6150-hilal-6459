package com.mlpipeline;

import com.mlpipeline.metrics.Metrics;
import com.mlpipeline.model.Job;
import com.mlpipeline.model.JobResult;
import com.mlpipeline.pipeline.InferenceClient;
import com.mlpipeline.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 *  Concurrent ML Classification Pipeline - API Gateway
 * ============================================================================
 * Topic B (ML / Data-Analysis Pipeline), ULFG Sem VIII - Concurrency,
 * Parallelism & Distributed Systems final project.
 *
 * Pipeline stages:
 *   1. Validate / pre-process  (runs on the bounded worker pool below)
 *   2. Infer / analyze         (async network call to Python ML service,
 *                                see InferenceClient - has timeout+fallback)
 *   3. Post-process / store    (write immutable JobResult into ConcurrentHashMap)
 *
 * Concurrency mechanisms on display here:
 *   - Bounded ThreadPoolExecutor (explicit sizing, explicit rejection policy)
 *   - CompletableFuture async composition (fan-out to Python, fan back in)
 *   - ConcurrentHashMap for shared result/idempotency state (no manual locks)
 *   - AtomicLong-based metrics (lock-free counters)
 *   - Semaphore bulkhead on the outbound network boundary (in InferenceClient)
 *   - Graceful shutdown hook that drains in-flight work
 * ============================================================================
 */
public class Main {

    static final int PORT = 8080;

    // Bounded resources - sizing rationale is documented in ARCHITECTURE_MEMO.md.
    // Kept intentionally small so it's easy to *demonstrate* backpressure live
    // (fire enough concurrent requests and you will see HTTP 503s).
    static final int WORKER_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());
    static final int QUEUE_CAPACITY = 25;

    static final Metrics metrics = new Metrics();
    static final ConcurrentHashMap<String, JobResult> results = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    static final InferenceClient inferenceClient = new InferenceClient();

    static ThreadPoolExecutor workerPool;

    public static void main(String[] args) throws IOException {
        workerPool = new ThreadPoolExecutor(
                WORKER_THREADS, WORKER_THREADS,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new ThreadPoolExecutor.AbortPolicy() // reject instead of growing unbounded
        );

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/classify", new ClassifyHandler());
        server.createContext("/status", new StatusHandler());
        server.createContext("/metrics", new MetricsHandler());
        server.createContext("/health", exchange -> sendJson(exchange, 200, "{\"status\":\"ok\"}"));
        // Separate, larger pool just for accepting/parsing HTTP connections -
        // deliberately decoupled from the bounded processing pool above so a
        // burst of slow clients can't starve the workers doing real work.
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.start();

        System.out.println("=================================================================");
        System.out.println(" ML Pipeline Gateway  ->  http://localhost:" + PORT);
        System.out.println(" Worker threads : " + WORKER_THREADS);
        System.out.println(" Queue capacity : " + QUEUE_CAPACITY + "  (requests beyond this get HTTP 503)");
        System.out.println(" Inference call : http://localhost:8000/predict  (Python ML service)");
        System.out.println("=================================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[shutdown] Signal received - draining in-flight work...");
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    System.out.println("[shutdown] Timeout reached - forcing shutdown of remaining tasks.");
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            server.stop(1);
            System.out.println("[shutdown] Complete. Goodbye.");
        }));
    }

    // ------------------------------------------------------------------
    // POST /classify   { "text": "...", "idempotencyKey": "optional" }
    // ------------------------------------------------------------------
    static class ClassifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method not allowed, use POST\"}");
                return;
            }

            long start = System.currentTimeMillis();

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = SimpleJson.parseObject(body);
            String text = String.valueOf(parsed.getOrDefault("text", "")).trim();
            Object keyObj = parsed.get("idempotencyKey");
            String suppliedKey = keyObj != null ? String.valueOf(keyObj) : null;

            if (text.isEmpty() || "null".equals(text)) {
                sendJson(exchange, 400, "{\"error\":\"'text' field is required\"}");
                return;
            }
            if (text.length() > 2000) {
                sendJson(exchange, 400, "{\"error\":\"text too long (max 2000 chars)\"}");
                return;
            }

            // Count only well-formed requests, so received == completed + failed
            // + rejected + duplicateHits holds exactly (clean accounting for the
            // /metrics evidence shown in the demo).
            metrics.received.incrementAndGet();

            String idemKey = (suppliedKey != null && !suppliedKey.isBlank() && !"null".equals(suppliedKey))
                    ? suppliedKey
                    : "auto-" + Integer.toHexString(text.hashCode());

            String jobId = UUID.randomUUID().toString();

            // Atomic idempotency check: only one thread can "win" the insert for a
            // given key. Concurrent retries of the same logical request collapse
            // onto the same job instead of being processed twice.
            String existingJobId = idempotencyIndex.putIfAbsent(idemKey, jobId);
            if (existingJobId != null) {
                metrics.duplicateHits.incrementAndGet();
                JobResult existing = results.get(existingJobId);
                sendJson(exchange, 200, existing != null ? existing.toJson() : JobResult.queued(existingJobId).toJson());
                return;
            }

            Job job = new Job(jobId, idemKey, text, Instant.now());
            results.put(jobId, JobResult.queued(jobId));

            try {
                workerPool.submit(() -> runPipeline(job, start));
            } catch (RejectedExecutionException e) {
                // Bounded queue is full -> explicit, measurable backpressure
                // instead of unbounded memory growth.
                metrics.rejected.incrementAndGet();
                JobResult rejected = JobResult.rejected(jobId, "queue full - backpressure engaged, retry shortly");
                results.put(jobId, rejected);
                idempotencyIndex.remove(idemKey, jobId);
                sendJson(exchange, 503, rejected.toJson());
                return;
            }

            sendJson(exchange, 202, JobResult.queued(jobId).toJson());
        }
    }

    /** Runs stages 1-3 for one job. Stage 1 runs synchronously on the calling
     *  worker thread; stage 2 is an async network call; stage 3 runs in the
     *  async callback. */
    static void runPipeline(Job job, long start) {
        results.put(job.id, JobResult.processing(job.id));

        // ---- Stage 1: preprocess (CPU-light normalization) ----
        String normalized = job.text.toLowerCase().trim().replaceAll("\\s+", " ");

        // ---- Stage 2: infer (async network boundary, timeout + fallback) ----
        inferenceClient.classify(normalized)
                .orTimeout(2, TimeUnit.SECONDS)
                .handle((infResult, ex) -> {
                    long latency = System.currentTimeMillis() - start;
                    JobResult result;
                    if (ex != null) {
                        // ---- Fallback path: degrade gracefully instead of failing ----
                        result = JobResult.completed(job.id, "neutral", 0.5, true, latency);
                        metrics.fallbackUsed.incrementAndGet();
                    } else {
                        result = JobResult.completed(job.id, infResult.label, infResult.confidence, false, latency);
                    }
                    // ---- Stage 3: postprocess/store ----
                    results.put(job.id, result);
                    metrics.completed.incrementAndGet();
                    metrics.recordLatency(latency);
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // GET /status?id=<jobId>
    // ------------------------------------------------------------------
    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String id = null;
            if (query != null) {
                for (String kv : query.split("&")) {
                    String[] parts = kv.split("=", 2);
                    if (parts.length == 2 && parts[0].equals("id")) id = parts[1];
                }
            }
            if (id == null) {
                sendJson(exchange, 400, "{\"error\":\"missing ?id=<jobId>\"}");
                return;
            }
            JobResult result = results.get(id);
            if (result == null) {
                sendJson(exchange, 404, "{\"error\":\"unknown job id\"}");
                return;
            }
            sendJson(exchange, 200, result.toJson());
        }
    }

    // ------------------------------------------------------------------
    // GET /metrics
    // ------------------------------------------------------------------
    static class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long[] p = metrics.percentiles();
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"received\":").append(metrics.received.get()).append(",");
            sb.append("\"completed\":").append(metrics.completed.get()).append(",");
            sb.append("\"failed\":").append(metrics.failed.get()).append(",");
            sb.append("\"rejected\":").append(metrics.rejected.get()).append(",");
            sb.append("\"fallbackUsed\":").append(metrics.fallbackUsed.get()).append(",");
            sb.append("\"duplicateHits\":").append(metrics.duplicateHits.get()).append(",");
            sb.append("\"queueDepth\":").append(workerPool.getQueue().size()).append(",");
            sb.append("\"queueCapacity\":").append(QUEUE_CAPACITY).append(",");
            sb.append("\"activeWorkers\":").append(workerPool.getActiveCount()).append(",");
            sb.append("\"poolSize\":").append(workerPool.getPoolSize()).append(",");
            sb.append("\"inferencePermitsAvailable\":").append(inferenceClient.availablePermits()).append(",");
            sb.append("\"latencyP50Ms\":").append(p[0]).append(",");
            sb.append("\"latencyP95Ms\":").append(p[1]).append(",");
            sb.append("\"latencyP99Ms\":").append(p[2]).append(",");
            sb.append("\"latencySamples\":").append(metrics.sampleCount());
            sb.append("}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
