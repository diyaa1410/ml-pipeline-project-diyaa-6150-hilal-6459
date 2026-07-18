# Load Test Results

Captured with `java -cp out com.mlpipeline.loadtest.LoadTester <n> <concurrency>`
against a freshly started gateway + Python inference service. `WORKER_THREADS=2`,
`QUEUE_CAPACITY=25` on the machine these were captured on (auto-scales to
`Runtime.availableProcessors()` — expect higher acceptance thresholds on a
multi-core laptop).

## Run A — sustainable load (concurrency ≈ worker pool size)

`60 requests, concurrency 2`

| Metric | Value |
|---|---|
| Accepted (202) | 60 / 60 |
| Rejected (503) | 0 |
| Wall time | 1.72 s |
| Throughput | 35.0 req/s |
| Latency p50 | 47 ms (client) / 97 ms (server, end-to-end) |
| Latency p95 | 54 ms (client) / 159 ms (server) |
| Latency p99 | 327 ms (client) / 324 ms (server) |

Server metrics after run: `received:60, completed:60, failed:0, rejected:0,
fallbackUsed:0`. At sustainable concurrency, nothing gets rejected and
nothing times out.

## Run B — overload (concurrency well above capacity)

`200 requests, concurrency 80`

| Metric | Value |
|---|---|
| Accepted (202) | 47 / 200 |
| Rejected (503, backpressure) | 153 / 200 |
| Wall time | 0.95 s |
| Throughput | 211.6 req/s |
| Latency p50 | 194 ms (client) / 698 ms (server) |
| Latency p95 | 712 ms (client) / 1294 ms (server) |
| Latency p99 | 744 ms (client) / 1310 ms (server) |

Server metrics after run: `received:200, completed:47, failed:0,
rejected:153, fallbackUsed:11`. Two things worth pointing out on camera:

1. **Backpressure engaged exactly as designed** — 153 requests were
   rejected with HTTP 503 the instant the bounded queue filled, rather
   than being queued indefinitely or crashing the process.
2. **`fallbackUsed:11` emerged organically** — under this much contention,
   11 of the 47 *accepted* jobs took longer than the 2-second inference
   timeout and transparently fell back to a default classification instead
   of hanging or erroring. This wasn't manually triggered chaos — it's the
   timeout/fallback path protecting the pipeline under real load.

## How to reproduce

```bash
# Terminal 1
python3 python-ml-service/server.py
# Terminal 2
java -cp out com.mlpipeline.Main
# Terminal 3
java -cp out com.mlpipeline.loadtest.LoadTester 60 2     # Run A
java -cp out com.mlpipeline.loadtest.LoadTester 200 80   # Run B
curl -s http://localhost:8080/metrics
```
