#!/usr/bin/env bash
# Usage: ./scripts/chaos.sh on   (enables 3s delay, 0% forced failure -> triggers Java's 2s timeout+fallback)
#        ./scripts/chaos.sh off
MODE=${1:-on}
if [ "$MODE" = "on" ]; then
  curl -s -X POST http://localhost:8000/chaos \
    -H "Content-Type: application/json" \
    -d '{"enabled":true,"delay_ms":3000,"fail_rate":0.0}'
  echo
  echo "Chaos mode ON - Python service now delays 3s per request (Java's timeout is 2s)."
else
  curl -s -X POST http://localhost:8000/chaos \
    -H "Content-Type: application/json" \
    -d '{"enabled":false}'
  echo
  echo "Chaos mode OFF - Python service back to normal."
fi
