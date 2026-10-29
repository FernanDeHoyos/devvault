#!/usr/bin/env bash
set -Eeuo pipefail

package_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
compose_file="$package_root/compose.preview.yml"
jar_file="$package_root/devvault.jar"
java_file="$package_root/runtime/bin/java"
data_root="${XDG_STATE_HOME:-$HOME/.local/state}/devvault/preview"
pid_file="$data_root/backend.pid"
stdout_log="$data_root/backend.out.log"
stderr_log="$data_root/backend.err.log"
api_base_url="http://127.0.0.1:${DEVVAULT_PREVIEW_PORT:-5050}"
app_url="http://localhost:${DEVVAULT_PREVIEW_PORT:-5050}/"

fail() { printf '\nDevVault: %s\n' "$1" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "No se encontró Docker. Instala Docker Engine con Docker Compose plugin."
command -v curl >/dev/null 2>&1 || fail "No se encontró curl, necesario para comprobar el estado de DevVault."
docker info >/dev/null 2>&1 || fail "Docker no responde. Inicia Docker Engine y vuelve a intentar."
docker compose -p devvault-preview -f "$compose_file" up -d postgres \
  || fail "No se pudo iniciar PostgreSQL. Comprueba que el puerto 5434 esté disponible."

database_ready=false
for _ in {1..30}; do
  if docker compose -p devvault-preview -f "$compose_file" exec -T postgres pg_isready -U devvault -d devvault >/dev/null 2>&1; then
    database_ready=true
    break
  fi
  sleep 2
done
$database_ready || fail "PostgreSQL no quedó listo; revisa el estado del contenedor."

if curl --fail --silent --show-error --max-time 2 "$api_base_url/api/v1/health" >/dev/null 2>&1; then
  command -v xdg-open >/dev/null 2>&1 && xdg-open "$app_url" >/dev/null 2>&1 || true
  printf 'DevVault ya estaba iniciado: %s\n' "$app_url"
  exit 0
fi

[[ -x "$java_file" && -f "$jar_file" ]] || fail "Faltan el runtime Java o el backend. Vuelve a extraer el paquete completo."
mkdir -p "$data_root"

if curl --silent --output /dev/null --max-time 1 "$api_base_url"; then
  fail "El puerto ${DEVVAULT_PREVIEW_PORT:-5050} está ocupado por otro servicio."
fi

nohup "$java_file" -jar "$jar_file" --spring.profiles.active=preview \
  >"$stdout_log" 2>"$stderr_log" < /dev/null &
backend_pid=$!
printf '%s\n' "$backend_pid" > "$pid_file"

backend_ready=false
for _ in {1..45}; do
  if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
  if curl --fail --silent --max-time 2 "$api_base_url/api/v1/health" >/dev/null 2>&1; then
    backend_ready=true
    break
  fi
  sleep 2
done

if [[ "$backend_ready" != true ]]; then
  kill "$backend_pid" 2>/dev/null || true
  rm -f "$pid_file"
  fail "DevVault no inició. Revisa $stdout_log y $stderr_log."
fi

command -v xdg-open >/dev/null 2>&1 && xdg-open "$app_url" >/dev/null 2>&1 || true
printf 'DevVault está listo: %s\nRegistros: %s\n' "$app_url" "$data_root"
