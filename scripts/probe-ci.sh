#!/usr/bin/env bash
# Self-contained contract probe: build shield-x + client-go from source, start a real
# server, seed sites, then run the Java raw-HTTP probe.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHIELDX="$ROOT/shield-x"
CLIENT_GO="$ROOT/client-go"
BIN="${RUNNER_TEMP:-/tmp}/shield-x-java-probe"
LOG="${RUNNER_TEMP:-/tmp}/shield-x-java-probe.log"
JAR="${RUNNER_TEMP:-/tmp}/shield-x-java-probe-cookies"
DB="shield_x_java_probe"
CONSOLE="http://127.0.0.1:18081/shieldx/console/api/v1"
AGENT="http://127.0.0.1:18080/shieldx"

if [ ! -d "$SHIELDX" ] || [ ! -d "$CLIENT_GO" ]; then
  echo "need ./shield-x and ./client-go checkouts" >&2
  exit 1
fi

cleanup() {
  if [ -n "${PID:-}" ]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  if [ -f "$SHIELDX/go.mod.ci-bak" ]; then
    mv "$SHIELDX/go.mod.ci-bak" "$SHIELDX/go.mod"
  fi
}
trap cleanup EXIT

echo "== build shield-x (with local client-go replace) =="
cp "$SHIELDX/go.mod" "$SHIELDX/go.mod.ci-bak"
printf '\nreplace github.com/linyu19872008/shield-x-client-go => %s\n' "$CLIENT_GO" >> "$SHIELDX/go.mod"
(cd "$SHIELDX" && go build -o "$BIN" ./cmd/shield-x)
mv "$SHIELDX/go.mod.ci-bak" "$SHIELDX/go.mod"

export SHIELDX_DB_DSN="root:11111111@tcp(127.0.0.1:3306)/${DB}?parseTime=true&loc=UTC"
export SHIELDX_SUPER_ADMIN_PASSWORD="Passw0rd!123"
export SHIELDX_LISTEN_AGENT="127.0.0.1:18080"
export SHIELDX_LISTEN_CONSOLE="127.0.0.1:18081"

echo "== migrate / serve =="
"$BIN" migrate >/tmp/java-probe-migrate.log 2>&1
"$BIN" serve >"$LOG" 2>&1 &
PID=$!
for _ in $(seq 1 60); do
  if curl -sf "$AGENT/healthz" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done
curl -sf "$AGENT/healthz" >/dev/null || {
  tail -40 "$LOG" >&2
  exit 1
}

echo "== login / sites =="
LOGIN="$(curl -sS -c "$JAR" -X POST "$CONSOLE/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"super_admin","password":"Passw0rd!123"}')"
CSRF="$(printf '%s' "$LOGIN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["csrf_token"])')"
curl -sS -b "$JAR" -X POST "$CONSOLE/auth/password" \
  -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
  -d '{"old_password":"Passw0rd!123","new_password":"Probe!Passw0rd123"}' >/dev/null
LOGIN="$(curl -sS -c "$JAR" -X POST "$CONSOLE/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"super_admin","password":"Probe!Passw0rd123"}')"
CSRF="$(printf '%s' "$LOGIN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["csrf_token"])')"

create_site() {
  curl -sS -b "$JAR" -X POST "$CONSOLE/sites" \
    -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$1\",\"generate_token\":true}"
}
A="$(create_site java-probe-a)"
B="$(create_site java-probe-b)"
C="$(create_site java-probe-c)"
ID_A="$(printf '%s' "$A" | python3 -c 'import sys,json;print(json.load(sys.stdin)["site"]["id"])')"
ID_B="$(printf '%s' "$B" | python3 -c 'import sys,json;print(json.load(sys.stdin)["site"]["id"])')"
TOK_A="$(printf '%s' "$A" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token_plaintext"])')"
TOK_B="$(printf '%s' "$B" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token_plaintext"])')"
TOK_C="$(printf '%s' "$C" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token_plaintext"])')"

echo "== seed policies =="
curl -sS -b "$JAR" -X POST "$CONSOLE/sites/$ID_A/policies" \
  -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
  -d '{"name":"probe-a","priority":100,"match":{"all":[{"field":"ip","op":"in_cidr","value":["203.0.113.0/24"]}]},"action":{"type":"observe"}}' >/dev/null
curl -sS -b "$JAR" -X POST "$CONSOLE/sites/$ID_B/policies" \
  -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
  -d '{"name":"probe-b","priority":100,"match":{"all":[{"field":"path","op":"prefix","value":"/probe-b"}]},"action":{"type":"observe"}}' >/dev/null

echo "== run Java contract probe =="
cd "$ROOT"
SHIELDX_BASE_URL="$AGENT" \
SHIELDX_TOKEN="$TOK_A" \
SHIELDX_PROBE_EMPTY_TOKEN="$TOK_C" \
SHIELDX_PROBE_TOKEN_B="$TOK_B" \
./mvnw -B -ntp -pl core -Dtest=ContractProbeTest test
