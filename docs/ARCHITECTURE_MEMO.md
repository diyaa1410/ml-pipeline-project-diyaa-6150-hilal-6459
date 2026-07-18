# Architecture Decision Memo

**Decision:** Bounded worker pool + queue capacity (gateway side) and
Semaphore bulkhead size (inference-client side).

**Team:** Hilal, Diyaa
**Project:** Concurrent ML Classification Pipeline (Topic B)

---

## Context

Every incoming `/classify` request needs to be preprocessed and then sent
to a Python inference service over HTTP. Two numbers control how much
concurrent work the system allows before it starts pushing back:

1. **Gateway worker pool + queue** — `ThreadPoolExecutor(WORKER_THREADS,
   WORKER_THREADS, ..., new ArrayBlockingQueue<>(QUEUE_CAPACITY),
   AbortPolicy)` in `Main.java`. `WORKER_THREADS` = number of CPU cores,
   `QUEUE_CAPACITY` = 25.
2. **Inference bulkhead** — `Semaphore(20)` in `InferenceClient.java`,
   capping concurrent outbound HTTP calls to the Python service.

This memo is about that pair of numbers, since they're the single decision
that determines the system's overload behavior end to end.

## Q1. What guarantee does your chosen design provide?

The gateway never queues more than `QUEUE_CAPACITY` jobs waiting for a free
worker thread, and never has more than `MAX_CONCURRENT_CALLS` (20) requests
in flight to the Python service at once. Every request either (a) gets
accepted and is guaranteed to eventually be picked up by a worker, or (b)
is rejected immediately with HTTP 503. There is no unbounded queue, no
unbounded thread creation, and no possibility of the process running out of
memory because clients kept sending faster than we could process.

## Q2. What failure modes does it prevent?

- **Memory exhaustion under load.** An unbounded queue (e.g. plain
  `LinkedBlockingQueue` with no capacity) would accept every request
  indefinitely and eventually OOM the JVM during a traffic spike.
- **Cascading overload of the Python service.** Without the bulkhead, a
  burst of requests could open hundreds of simultaneous HTTP connections to
  a single-process Python server, degrading it further and making
  recovery slower.
- **Silent, unbounded latency growth.** Bounding the queue means a client
  gets a fast, explicit "no" (503) instead of waiting behind an
  ever-growing backlog with no idea when — or if — they'll get a response.

## Q3. What failure modes does it introduce?

- **False rejections during legitimate bursts.** A real traffic spike that
  the system could eventually work through (just more slowly) will still
  get some requests rejected once the queue fills, even though no resource
  was actually exhausted yet.
- **Client-side retry storms.** If clients naively retry immediately on
  503 without backoff, they can turn a temporary overload into a sustained
  one. (Mitigated in our design by idempotency keys — a retried request
  collapses onto the original job rather than creating new load — but the
  client still needs to *implement* backoff for full protection.)
- **Tuning risk.** Capacity numbers picked too low reject traffic the
  hardware could actually handle; picked too high, they stop protecting
  anything. These are magic numbers that need load-test evidence behind
  them (see Q4), not guesses.

## Q4. How does it behave under overload? Give measured numbers.

Measured with `LoadTester` (200 unique requests, concurrency 80, against
`WORKER_THREADS=2` on the test machine, `QUEUE_CAPACITY=25`):

| Metric | Value |
|---|---|
| Total requests | 200 |
| Accepted (202) | 48 |
| Rejected (503) | 152 |
| Wall time | 1.29 s |
| Client-observed throughput | 154.7 req/s |
| Latency p50 | 314 ms |
| Latency p95 | 841 ms |
| Latency p99 | 894 ms |

Full server-side counters after the run (`GET /metrics`): `received: 200,
completed: 42, rejected: 152, fallbackUsed: 0, duplicateHits: 0`. The gap
between `accepted` (48) and `completed` (42) at query time is expected —
those 6 jobs were still in flight through the async pipeline when metrics
were captured, which is itself evidence the pipeline is genuinely
asynchronous rather than blocking. See `docs/load-test-results.md` for the
raw run and a second run at a lower, sustainable concurrency for
comparison. **Note:** these numbers were captured on a single-core test
environment; `WORKER_THREADS` auto-scales to `Runtime.availableProcessors()`,
so a multi-core machine will accept proportionally more concurrent work
before rejecting.

## Q5. How would a new engineer debug it?

1. **Check `/metrics` first.** `queueDepth` near `queueCapacity` with
   `activeWorkers` at `poolSize` means the gateway itself is saturated —
   look at whether Stage 1 (preprocess) or something blocking the worker
   thread is the bottleneck.
2. **Check `inferencePermitsAvailable`.** If this is consistently near 0
   while `queueDepth` is low, the bottleneck is downstream — the Python
   service is slow, not the gateway.
3. **Check `fallbackUsed`.** A rising count means requests are timing out
   against the Python service (2s timeout) and silently degrading to the
   neutral fallback — worth alerting on even though it doesn't surface as
   an error to clients.
4. **Reproduce with `LoadTester`.** Given a concurrency number, `received`
   and `rejected` in `/metrics` should match what `LoadTester`'s own
   client-side counts show; a mismatch points at network-layer issues
   (e.g. connection resets) rather than the queue itself.
5. **Logs.** The gateway logs queue-capacity and worker-thread counts on
   startup and prints a shutdown trace on `Ctrl+C`; the Python service logs
   every request (including chaos-mode state changes) to stdout.
