@echo off
set MODE=%1
if "%MODE%"=="" set MODE=on
if "%MODE%"=="on" (
  curl -s -X POST http://localhost:8000/chaos -H "Content-Type: application/json" -d "{\"enabled\":true,\"delay_ms\":3000,\"fail_rate\":0.0}"
  echo Chaos mode ON
) else (
  curl -s -X POST http://localhost:8000/chaos -H "Content-Type: application/json" -d "{\"enabled\":false}"
  echo Chaos mode OFF
)
