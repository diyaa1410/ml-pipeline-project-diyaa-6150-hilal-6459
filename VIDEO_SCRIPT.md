# Video Presentation Script — Concurrent ML Classification Pipeline
**Speakers: Hilal & Diyaa** — target length: ~8 minutes + demo buffer

Before recording: open 3 terminals side by side (Python service / Java
gateway / commands), and have `diagrams/architecture.svg` open in a browser
tab. Compile once before you hit record: `./scripts/compile.sh` (or
`scripts\compile.bat` on Windows).

Speak naturally — this is a guide for content and timing, not a word-for-word
script to memorize.

---

### [0:00–0:40] INTRO — both speakers, faces on camera

**Hilal:** "Hi, we're Hilal and Diyaa, and this is our final project for
Concurrency, Parallelism and Distributed Systems — we built Topic B, a
machine-learning data pipeline."

**Diyaa:** "It's a text classification service — a Java gateway takes
requests from many clients at once, runs them through a pipeline, and calls
out to a separate Python inference service over the network. In the next
few minutes we'll show it actually running, show the concurrency mechanisms
that make it safe under load, and then break it on purpose twice to show it
recovers."

**SHOW:** switch to screen share, `diagrams/architecture.svg` full screen.

---

### [0:40–2:00] ARCHITECTURE — Hilal talks, diagram on screen

**Hilal:** "Here's the shape of the system. Clients hit our Java gateway on
port 8080 with a POST to `/classify`. The gateway doesn't process the
request itself — it hands it to a bounded thread pool, that's a fixed
number of worker threads backed by a queue with a hard capacity of 25. If
that queue fills up, we reject new work immediately with an HTTP 503
instead of letting memory grow without limit."

**Diyaa:** "From there, stage one does light preprocessing, then stage two
is the real network boundary the assignment requires — the worker calls out
over HTTP to a Python microservice running the actual classifier, with a
two-second timeout. Stage three writes the result into a thread-safe map
and updates our metrics. Everything after the initial HTTP response happens
asynchronously — the client polls a `/status` endpoint to get the result."

**SHOW:** switch to VS Code, open `src/main/java/com/mlpipeline/Main.java`,
scroll to the `ThreadPoolExecutor` construction and the `runPipeline`
method just briefly — don't read code line by line, just point at it.

---

### [2:00–3:15] STARTING THE SYSTEM — Diyaa drives the terminal

**Diyaa:** "Let's start it for real. First terminal, the Python inference
service — this only uses Python's standard library, no installs needed."

**SHOW / RUN (Terminal 1):**
```bash
./scripts/run-python.sh
```
*(wait for "Python ML inference service listening on http://localhost:8000")*

**Hilal:** "Second terminal, the Java gateway. Notice it prints how many
worker threads it started with — that's `Runtime.getRuntime().availableProcessors()`,
so it automatically sizes itself to this machine."

**SHOW / RUN (Terminal 2):**
```bash
./scripts/run-gateway.sh
```
*(wait for the gateway banner — point at "Worker threads" and "Queue capacity" in the printed banner)*

---

### [3:15–4:30] HAPPY PATH DEMO — Hilal drives

**Hilal:** "Let's send a real request."

**SHOW / RUN (Terminal 3):**
```bash
curl -X POST http://localhost:8080/classify \
  -H "Content-Type: application/json" \
  -d '{"text":"This is an excellent product, I love it!"}'
```

**Hilal:** "That returned immediately with status QUEUED and a job ID — the
gateway thread didn't block waiting for the model. Now we poll for the
result:"

**SHOW / RUN:**
```bash
curl "http://localhost:8080/status?id=<paste jobId here>"
```

**Diyaa:** "COMPLETED, label positive, and a latency number — that's the
full round trip through preprocessing, the network call to Python, and
postprocessing. Let's also check idempotency — if we send that exact same
request again..."

**SHOW / RUN:**
```bash
curl -X POST http://localhost:8080/classify \
  -H "Content-Type: application/json" \
  -d '{"text":"This is an excellent product, I love it!"}'
```

**Diyaa:** "Same job ID comes back immediately — it didn't get processed
twice. That matters for retries: if a client's connection drops and it
resends the same request, we don't double-charge or double-process it."

---

### [4:30–6:00] FAILURE INJECTION #1 — QUEUE OVERLOAD — Hilal drives

**Hilal:** "Now let's break it on purpose. First: what happens if way more
requests arrive than the system can handle at once? We'll fire 200
requests with 80 of them happening simultaneously — way past our worker
pool plus queue capacity."

**SHOW / RUN:**
```bash
./scripts/run-loadtest.sh 200 80
```

**Hilal:** *(while it runs)* "Watch the output — 'Rejected 503' should climb
into the hundreds."

*(after it finishes, point at the printed table)*

**Diyaa:** "Right — roughly three-quarters of these got an immediate 503
instead of piling up in memory. That's the bounded queue doing its job.
Let's confirm the gateway is still alive and responsive:"

**SHOW / RUN:**
```bash
curl http://localhost:8080/health
curl http://localhost:8080/metrics
```

**Diyaa:** "Still up, still answering — 'rejected' in the metrics matches
what the load tester counted. The system degraded on purpose instead of
falling over."

---

### [6:00–7:15] FAILURE INJECTION #2 — DOWNSTREAM SERVICE SLOW — Diyaa drives

**Diyaa:** "Second failure scenario — what if the Python inference service
itself gets slow or dies? We built a chaos switch into it for exactly this
demo."

**SHOW / RUN:**
```bash
./scripts/chaos.sh on
```

**Diyaa:** "That tells Python to sleep three seconds before answering — but
our Java client only waits two seconds before it gives up. Let's send a
request:"

**SHOW / RUN:**
```bash
curl -X POST http://localhost:8080/classify -d '{"text":"testing failure injection"}'
```
*(copy the jobId, wait ~2-3 seconds, then:)*
```bash
curl "http://localhost:8080/status?id=<jobId>"
```

**Hilal:** "Status COMPLETED, not FAILED — label is 'neutral', and there's
a flag `fallbackUsed: true`. The request still succeeded from the client's
point of view, just with a safe default answer instead of hanging forever
or throwing an error. That's the timeout-and-fallback pattern the
assignment asks for."

**SHOW / RUN:**
```bash
./scripts/chaos.sh off
curl -X POST http://localhost:8080/classify -d '{"text":"back to normal, great service!"}'
```

**Hilal:** "And once we turn chaos off, the very next request completes
normally again in under a hundred milliseconds — full recovery, no restart
needed."

---

### [7:15–7:50] CPU-BOUND BENCHMARK — Diyaa drives

**Diyaa:** "Last thing — the assignment asks for a sequential-versus-parallel
comparison on one CPU-bound step. We picked word-frequency counting, which
is what a real feature-extraction stage would look like before inference."

**SHOW / RUN:**
```bash
./scripts/run-benchmark.sh
```

**Diyaa:** "Same result both ways — that's our correctness check — but the
parallel version is faster because it splits the corpus across all
available cores and only merges results once at the end, instead of every
thread fighting over one shared map."

---

### [7:50–8:30] WRAP-UP — both speakers

**Hilal:** "To sum up the concurrency mechanisms: a bounded thread pool
with an explicit rejection policy, a semaphore limiting how many concurrent
calls we make to the Python service, `CompletableFuture` chaining the three
pipeline stages asynchronously, and lock-free atomic counters for our
metrics."

**Diyaa:** "The one tradeoff we'd call out: we picked small pool and queue
sizes on purpose so the backpressure behavior is easy to see live, like we
just showed you. In a real deployment you'd size those numbers from actual
production traffic, not from what looks good on camera — we wrote up that
whole decision, with the measured numbers, in our architecture memo."

**Hilal:** "All the code, the load test results, and the diagrams are in
the GitHub repo linked below. Thanks for watching."

---

## Quick command cheat-sheet (keep visible off-screen while recording)

```bash
# Terminal 1
./scripts/run-python.sh

# Terminal 2
./scripts/run-gateway.sh

# Terminal 3 — happy path
curl -X POST http://localhost:8080/classify -H "Content-Type: application/json" -d '{"text":"This is an excellent product, I love it!"}'
curl "http://localhost:8080/status?id=PASTE_ID"
curl -X POST http://localhost:8080/classify -H "Content-Type: application/json" -d '{"text":"This is an excellent product, I love it!"}'   # idempotency

# Overload demo
./scripts/run-loadtest.sh 200 80
curl http://localhost:8080/health
curl http://localhost:8080/metrics

# Downstream failure demo
./scripts/chaos.sh on
curl -X POST http://localhost:8080/classify -d '{"text":"testing failure injection"}'
curl "http://localhost:8080/status?id=PASTE_ID"
./scripts/chaos.sh off
curl -X POST http://localhost:8080/classify -d '{"text":"back to normal, great service!"}'

# Benchmark
./scripts/run-benchmark.sh
```

**Windows users:** replace `./scripts/X.sh` with `scripts\X.bat` throughout.
