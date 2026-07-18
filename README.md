# Concurrent ML Classification Pipeline

**ULFG — Sem VIII — Concurrency, Parallelism & Distributed Systems — Final Project**
**Topic B: Machine-Learning / Data-Analysis Pipeline**
Team: Hilal · Diyaa

A text-classification service where a Java 21 API gateway accepts concurrent
requests, runs them through a 3-stage pipeline (preprocess → infer →
postprocess), and calls out to a separate Python inference microservice over
a real network boundary — with bounded queues, backpressure, timeouts,
fallbacks, idempotency, and live metrics.

See `diagrams/architecture.svg`, `diagrams/sequence.svg`, and
`diagrams/failure_propagation.svg` for the required diagrams, and
`docs/ARCHITECTURE_MEMO.md` / `docs/CONCURRENCY_SCORECARD.md` for the
written deliverables.

---

## 1. Prerequisites

- **JDK 21** (only the JDK — no external libraries, no Maven Central
  dependency at all). Check with `java -version`.
- **Python 3.8+** for the inference microservice (standard library only —
  no `pip install` needed).
- `curl` (or any HTTP client) for manual testing — optional, used in the demo.

## 2. Project layout

```
ml-pipeline-project/
├── src/main/java/com/mlpipeline/
│   ├── Main.java                 # API gateway: HTTP server, bounded pool, endpoints
│   ├── model/Job.java             # immutable request
│   ├── model/JobResult.java       # immutable pipeline-stage snapshot
│   ├── pipeline/InferenceClient.java  # Stage 2: async network call + bulkhead
│   ├── metrics/Metrics.java       # atomic counters + latency percentiles
│   ├── util/SimpleJson.java       # dependency-free JSON parse/write
│   ├── loadtest/LoadTester.java   # concurrent load generator
│   └── benchmark/CpuBoundBenchmark.java  # sequential vs parallel CPU-bound demo
├── python-ml-service/server.py   # Stage 2 target: stdlib-only inference service
├── diagrams/                     # architecture / sequence / failure diagrams
├── docs/                         # memo, scorecard, load-test results
├── scripts/                      # convenience run scripts
├── build.gradle, settings.gradle # Gradle build (zero external deps)
└── VIDEO_SCRIPT.md               # presentation script
```

## 3. Running it (fastest path — no Gradle needed)

Everything is built on the JDK's own `com.sun.net.httpserver` and
`java.net.http.HttpClient`, so there is nothing to download. Three
terminals:

**Terminal 1 — compile once, then start the Python inference service:**
```bash
python3 python-ml-service/server.py
```

**Terminal 2 — compile and start the Java gateway:**
```bash
# from the project root
javac -d out $(find src/main/java -name "*.java")     # Windows: use scripts/compile.bat instead
java -cp out com.mlpipeline.Main
```
On Windows (PowerShell), compile with:
```powershell
javac -d out (Get-ChildItem -Recurse -Filter *.java src\main\java | % FullName)
java -cp out com.mlpipeline.Main
```

**Terminal 3 — try it:**
```bash
curl -X POST http://localhost:8080/classify \
  -H "Content-Type: application/json" \
  -d '{"text":"This is an excellent product, I love it!"}'
# => {"jobId":"...","status":"QUEUED",...}

curl "http://localhost:8080/status?id=<jobId-from-above>"
# => {"jobId":"...","status":"COMPLETED","label":"positive","confidence":1.0,...}

curl http://localhost:8080/metrics
```

Or with Gradle, if installed: `gradle run` starts the gateway (start the
Python service separately either way).

## 4. API reference

| Endpoint | Method | Body | Notes |
|---|---|---|---|
| `/classify` | POST | `{"text": "...", "idempotencyKey": "optional"}` | Returns `202` + job in `QUEUED` state, or `503` if the bounded queue is full, or `200` with the existing result if this is a duplicate of an in-flight/completed idempotency key. |
| `/status?id=<jobId>` | GET | — | Poll for the current `JobResult` (`QUEUED` → `PROCESSING` → `COMPLETED`/`FAILED`). |
| `/metrics` | GET | — | Live counters: received, completed, failed, rejected, fallbackUsed, duplicateHits, queue depth, active workers, p50/p95/p99 latency. |
| `/health` | GET | — | Liveness check. |

Python service (`:8000`): `POST /predict`, `POST /chaos` (failure
injection toggle — see below), `GET /health`.

## 5. Load testing / stress evidence

```bash
java -cp out com.mlpipeline.loadtest.LoadTester 200 80
```
Fires 200 concurrent requests (concurrency 80) at the gateway and prints a
throughput/latency table (accepted / rejected / duplicate counts, p50/p95/p99,
throughput). See `docs/load-test-results.md` for a captured run. Because the
worker pool + queue are deliberately small (documented in
`docs/ARCHITECTURE_MEMO.md`), overloading it and seeing HTTP 503s is expected
and is the point of the exercise — it proves the system degrades on purpose
instead of falling over.

## 6. Sequential vs. parallel CPU-bound benchmark

```bash
java -cp out com.mlpipeline.benchmark.CpuBoundBenchmark
```
Runs word-frequency counting over 20,000 synthetic documents both
single-threaded and split across `Runtime.availableProcessors()` threads,
verifies both give identical results, and prints the speedup. **Run this on
your own laptop for a meaningful number** — a CI/sandbox machine with a
single CPU core will show little to no speedup; a real multi-core laptop
should show a clear improvement.

## 7. Failure injection (two scenarios)

**Scenario 1 — Queue overload (backpressure):**
Run the load test above with a concurrency well above `WORKER_THREADS +
QUEUE_CAPACITY` (defaults: cores + 25). You'll see HTTP 503s and
`"rejected"` climb in `/metrics`, while the gateway process itself stays up
and responsive.

**Scenario 2 — Inference service slow/down (timeout + fallback):**
```bash
# Make the Python service simulate a 3s delay (longer than Java's 2s timeout)
curl -X POST http://localhost:8000/chaos \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"delay_ms":3000,"fail_rate":0.0}'

# Submit a request - it will time out and fall back gracefully
curl -X POST http://localhost:8080/classify -d '{"text":"test"}'
# ... poll /status - you'll see status COMPLETED, label "neutral", fallbackUsed:true

# Turn chaos off again
curl -X POST http://localhost:8000/chaos -d '{"enabled":false}'
```
You can also just `Ctrl+C` the Python service entirely to demonstrate the
same timeout/fallback path against a fully dead dependency.

## 8. Idempotency

Every `/classify` request carries an idempotency key (client-supplied or
auto-derived from the text). Retrying the same key — e.g. because a client
timed out and resent the request — returns the original job's result
instead of creating and billing a second one. Demonstrated by submitting
the same `idempotencyKey` twice in a row (see `docs/load-test-results.md`
and the video script).

## 9. Concurrency mechanisms → syllabus mapping

| Mechanism | Where | Syllabus week |
|---|---|---|
| Bounded `ThreadPoolExecutor` + `ArrayBlockingQueue`, `AbortPolicy` | `Main.java` | Week 5 |
| `Semaphore` bulkhead on outbound calls | `InferenceClient.java` | Week 5 |
| `CompletableFuture` async composition (preprocess → infer → postprocess) | `Main.runPipeline` | Week 7 |
| Timeout (`orTimeout`) + fallback (`.handle`) | `Main.runPipeline` | Week 7 |
| `ConcurrentHashMap` shared state, `putIfAbsent` idempotency | `Main.java` | Week 3–4 |
| Immutable `Job` / `JobResult` for safe publication | `model/` | Week 4 |
| `AtomicLong` lock-free counters | `Metrics.java` | Week 3 |
| Real network boundary, JSON serialization, timeout, failure handling | `InferenceClient.java` ↔ `server.py` | Weeks 10–14 |
| GIL discussion (threads vs. processes for CPU-bound Python work) | `python-ml-service/server.py` header comment, memo | Week 9 |
| Graceful shutdown (drain, then stop) | `Main.java` shutdown hook | Weeks 1–2 |

## 10. Known limitations / next steps

- Results are stored in-memory (`ConcurrentHashMap`), not a real database —
  fine for a single-process demo, would move to Postgres/Redis for a real
  deployment.
- Single Java instance / single Python instance — the assignment's minimum
  scope doesn't require horizontal scaling, but the same bulkhead +
  timeout pattern would extend cleanly to multiple Python replicas behind
  a load balancer.
- No TLS/auth — out of scope for the course's concurrency focus.
