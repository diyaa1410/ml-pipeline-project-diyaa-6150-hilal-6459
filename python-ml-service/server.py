"""
Python ML Inference Microservice
=================================
Topic B final project - this is the second half of the required "real
network boundary" (Section 4 of the brief): the Java gateway calls this
service over HTTP for the "infer/analyze" pipeline stage.

Deliberately built with the Python STANDARD LIBRARY ONLY (http.server, json,
threading) so it runs on any machine with Python 3 installed - no pip
install, no internet needed at demo time.

Concurrency note (Week 9 - GIL):
  ThreadingHTTPServer handles each request in its own thread, which is fine
  here because classify() is lightweight (a bit of string splitting plus a
  simulated I/O-like delay via time.sleep, which releases the GIL). If this
  were swapped for a real CPU-heavy model (e.g. a transformer running pure
  Python tensor ops), threads would NOT give a speedup because of the GIL -
  we'd need a process pool (multiprocessing) or multiple service replicas
  behind a load balancer instead. This tradeoff is written up in
  docs/ARCHITECTURE_MEMO.md.

Endpoints:
  POST /predict   { "text": "..." }         -> { "label": ..., "confidence": ... }
  POST /chaos      { "enabled": true, "delay_ms": 3000, "fail_rate": 0.5 }
                    toggles failure-injection mode for the live demo
  GET  /health
"""

import json
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

POSITIVE_WORDS = {
    "good", "great", "excellent", "love", "amazing", "happy", "best",
    "wonderful", "fantastic", "awesome", "recommend", "value", "fast", "easy"
}
NEGATIVE_WORDS = {
    "bad", "terrible", "hate", "awful", "worst", "sad", "horrible", "poor",
    "disappointing", "angry", "slow", "hard"
}

_chaos_lock = threading.Lock()
_chaos = {"enabled": False, "delay_ms": 3000, "fail_rate": 0.5}


def classify(text: str):
    words = [w.strip(".,!?;:\"'()").lower() for w in text.split()]
    pos = sum(1 for w in words if w in POSITIVE_WORDS)
    neg = sum(1 for w in words if w in NEGATIVE_WORDS)
    if pos == 0 and neg == 0:
        return "neutral", 0.50
    total = pos + neg
    if pos > neg:
        return "positive", round(0.5 + 0.5 * (pos / total), 3)
    elif neg > pos:
        return "negative", round(0.5 + 0.5 * (neg / total), 3)
    return "neutral", 0.50


class Handler(BaseHTTPRequestHandler):

    def _send_json(self, status: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            return json.loads(raw) if raw.strip() else {}
        except json.JSONDecodeError:
            return None

    def do_POST(self):
        if self.path == "/predict":
            with _chaos_lock:
                chaos = dict(_chaos)
            if chaos["enabled"]:
                time.sleep(chaos["delay_ms"] / 1000.0)
                if random.random() < chaos["fail_rate"]:
                    self._send_json(500, {"error": "simulated inference failure (chaos mode)"})
                    return

            data = self._read_json_body()
            if data is None:
                self._send_json(400, {"error": "invalid json"})
                return
            text = str(data.get("text", ""))

            # Simulate realistic (non-chaos) inference latency.
            time.sleep(random.uniform(0.03, 0.15))
            label, confidence = classify(text)
            self._send_json(200, {"label": label, "confidence": confidence})

        elif self.path == "/chaos":
            data = self._read_json_body() or {}
            with _chaos_lock:
                _chaos["enabled"] = bool(data.get("enabled", False))
                _chaos["delay_ms"] = int(data.get("delay_ms", 3000))
                _chaos["fail_rate"] = float(data.get("fail_rate", 0.5))
                snapshot = dict(_chaos)
            print(f"[chaos] mode updated: {snapshot}")
            self._send_json(200, {"chaos": snapshot})

        else:
            self._send_json(404, {"error": "not found"})

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
        elif self.path == "/chaos":
            with _chaos_lock:
                snapshot = dict(_chaos)
            self._send_json(200, {"chaos": snapshot})
        else:
            self._send_json(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        print("[python-ml] " + (fmt % args))


def main():
    port = 8000
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"Python ML inference service listening on http://localhost:{port}")
    print("Endpoints: POST /predict | POST /chaos | GET /health")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[shutdown] Stopping Python ML service...")
        server.shutdown()


if __name__ == "__main__":
    main()
