#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$backend_root"

jar_file="build/libs/devvault-0.0.1-SNAPSHOT.jar"
log_file="$(mktemp)"
workspace_path="$(mktemp -d)"
smoke_port="${DEVVAULT_SMOKE_PORT:-18080}"
base_url="http://127.0.0.1:${smoke_port}"
app_pid=""
workspace_id=""
database_container=""
database_url="${DEVVAULT_DB_URL:-}"
database_username="${DEVVAULT_DB_USERNAME:-devvault}"
database_password="${DEVVAULT_DB_PASSWORD:-devvault_dev}"

cleanup() {
  if [[ -n "$workspace_id" ]]; then
    curl -fsS -X DELETE "$base_url/api/v1/workspaces/$workspace_id" >/dev/null 2>&1 || true
  fi
  if [[ -n "$app_pid" ]]; then
    kill "$app_pid" >/dev/null 2>&1 || true
    wait "$app_pid" 2>/dev/null || true
  fi
  if [[ -n "$database_container" ]]; then
    docker stop --time 5 "$database_container" >/dev/null 2>&1 || true
  fi
  if [[ -n "$workspace_path" ]]; then
    rm -rf -- "$workspace_path"
  fi
  rm -f "$log_file"
}
trap cleanup EXIT

if [[ ! -f "$jar_file" ]]; then
  echo "No se encontró $jar_file; primero construye el JAR con la UI integrada." >&2
  exit 1
fi

for command in curl python3 java node npm; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Falta el comando requerido para el smoke test: $command" >&2
    exit 1
  fi
done

if ! python3 -c 'import socket,sys; s=socket.socket(); occupied=s.connect_ex(("127.0.0.1",3000)) == 0; s.close(); sys.exit(1 if occupied else 0)'; then
  echo "El puerto 3000 ya estÃ¡ ocupado; no se iniciarÃ¡ el proyecto de prueba." >&2
  exit 1
fi

if [[ -z "$database_url" ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "Falta Docker; define DEVVAULT_DB_URL para usar un PostgreSQL existente." >&2
    exit 1
  fi
  database_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
  database_container="devvault-smoke-$$"
  docker run --detach --rm --name "$database_container" \
    --publish "127.0.0.1:${database_port}:5432" \
    --env POSTGRES_DB=devvault \
    --env POSTGRES_USER="$database_username" \
    --env POSTGRES_PASSWORD="$database_password" \
    postgres:16-alpine >/dev/null
  database_url="jdbc:postgresql://127.0.0.1:${database_port}/devvault"
  database_ready="false"
  for _ in $(seq 1 60); do
    if docker exec "$database_container" pg_isready -U "$database_username" -d devvault >/dev/null 2>&1; then
      database_ready="true"
      break
    fi
    sleep 1
  done
  if [[ "$database_ready" != "true" ]]; then
    docker logs "$database_container" >&2 || true
    echo "PostgreSQL temporal no quedó listo." >&2
    exit 1
  fi
fi

wait_for_backend() {
  for _ in $(seq 1 90); do
    if ! kill -0 "$app_pid" 2>/dev/null; then
      cat "$log_file" >&2
      return 1
    fi
    health="$(curl -fsS "$base_url/api/v1/health" 2>/dev/null || true)"
    if python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("status") == "UP" and d.get("database") == "Operative" else 1)' <<<"$health" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  cat "$log_file" >&2
  return 1
}

start_backend() {
  : >"$log_file"
  DEVVAULT_DB_URL="$database_url" \
    DEVVAULT_DB_USERNAME="$database_username" \
    DEVVAULT_DB_PASSWORD="$database_password" \
    java -jar "$jar_file" --server.address=127.0.0.1 --server.port="$smoke_port" >>"$log_file" 2>&1 &
  app_pid=$!
  wait_for_backend
}

echo "1/7 Esperando a PostgreSQL y arrancando el JAR..."
start_backend

echo "2/7 Comprobando la portada, assets y ruta SPA..."
page="$(curl -fsS "$base_url/")"
grep -q '<title>DevVault</title>' <<<"$page"
spa_page="$(curl -fsS "$base_url/workspaces")"
grep -q '<title>DevVault</title>' <<<"$spa_page"
js_asset="$(grep -oE 'src="[^"]+\.js"' <<<"$page" | head -n 1 | cut -d '"' -f 2)"
css_asset="$(grep -oE 'href="[^"]+\.css"' <<<"$page" | head -n 1 | cut -d '"' -f 2)"
[[ -n "$js_asset" && -n "$css_asset" ]]
curl -fsS "$base_url$js_asset" >/dev/null
curl -fsS "$base_url$css_asset" >/dev/null

echo "3/7 Creando un workspace temporal..."
node_project_path="$workspace_path/node-project"
mkdir -p "$node_project_path"
cat >"$node_project_path/package.json" <<'EOF'
{"name":"devvault-node-smoke","version":"1.0.0","scripts":{"start":"node server.js"}}
EOF
cat >"$node_project_path/server.js" <<'EOF'
const http = require("node:http");
const server = http.createServer((_request, response) => response.end("DevVault Node smoke OK"));
server.listen(3000, "127.0.0.1", () => console.log("DevVault Node smoke listening on 3000"));
EOF
request="$(python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"path":sys.argv[2]}))' "DevVault CI smoke" "$workspace_path")"
created="$(curl -fsS -X POST "$base_url/api/v1/workspaces" -H 'Content-Type: application/json' -d "$request")"
workspace_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$created")"
python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("name") == sys.argv[1] and d.get("path") == sys.argv[2] else 1)' "DevVault CI smoke" "$workspace_path" <<<"$created"

echo "4/7 Escaneando e iniciando un proyecto Node en 3000..."
curl -fsS -X POST "$base_url/api/v1/workspaces/$workspace_id/scan" >/dev/null
scan_state=""
for _ in $(seq 1 60); do
  scan_status="$(curl -fsS "$base_url/api/v1/workspaces/$workspace_id/scan/status")"
  scan_state="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))' <<<"$scan_status")"
  [[ "$scan_state" == "COMPLETED" || "$scan_state" == "FAILED" ]] && break
  sleep 1
done
[[ "$scan_state" == "COMPLETED" ]]
projects="$(curl -fsS "$base_url/api/v1/projects?workspaceId=$workspace_id")"
project_id="$(python3 -c 'import json,sys; p=json.load(sys.stdin); print(next((x["id"] for x in p if x.get("path") == sys.argv[1]), ""))' "$node_project_path" <<<"$projects")"
[[ -n "$project_id" ]]
started="$(curl -fsS -X POST "$base_url/api/v1/projects/$project_id/start")"
python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("overallStatus") == "RUNNING" else 1)' <<<"$started"
[[ "$(curl -fsS http://127.0.0.1:3000/)" == "DevVault Node smoke OK" ]]
stopped="$(curl -fsS -X POST "$base_url/api/v1/projects/$project_id/stop")"
python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("overallStatus") == "STOPPED" else 1)' <<<"$stopped"
if curl -fsS http://127.0.0.1:3000/ >/dev/null 2>&1; then
  echo "El proceso Node siguió escuchando después de detener el proyecto." >&2
  exit 1
fi

echo "5/7 Reiniciando el backend y verificando persistencia..."
kill "$app_pid"
wait "$app_pid" 2>/dev/null || true
app_pid=""
start_backend
saved="$(curl -fsS "$base_url/api/v1/workspaces/$workspace_id")"
python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("path") == sys.argv[1] else 1)' "$workspace_path" <<<"$saved"

echo "6/7 Borrando el registro y confirmando que la carpeta permanece..."
curl -fsS -X DELETE "$base_url/api/v1/workspaces/$workspace_id" >/dev/null
workspace_id=""
[[ -d "$workspace_path" ]]

echo "7/7 Smoke test OK: health, UI, API, proyecto Node start/stop, CRUD y persistencia."
