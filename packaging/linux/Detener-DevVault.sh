#!/usr/bin/env bash
set -Eeuo pipefail

package_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
compose_file="$package_root/compose.preview.yml"
java_file="$package_root/runtime/bin/java"
jar_file="$package_root/devvault.jar"
data_root="${XDG_STATE_HOME:-$HOME/.local/state}/devvault/preview"
pid_file="$data_root/backend.pid"

if [[ -f "$pid_file" ]]; then
  backend_pid="$(<"$pid_file")"
  if [[ "$backend_pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$backend_pid/cmdline" ]]; then
    command_line="$(tr '\0' ' ' <"/proc/$backend_pid/cmdline")"
    if [[ "$command_line" == *"$java_file"* && "$command_line" == *"-jar $jar_file"* ]]; then
      kill -TERM "$backend_pid" 2>/dev/null || true
      for _ in {1..50}; do
        kill -0 "$backend_pid" 2>/dev/null || break
        sleep 0.2
      done
      if kill -0 "$backend_pid" 2>/dev/null; then
        kill -KILL "$backend_pid" 2>/dev/null || true
      fi
      printf 'Backend detenido.\n'
    fi
  fi
  rm -f "$pid_file"
fi

docker compose -p devvault-preview -f "$compose_file" stop postgres \
  && printf 'PostgreSQL detenido; sus datos permanecen guardados.\n' \
  || printf 'No se pudo detener PostgreSQL. Si Docker está cerrado, puedes ignorar este mensaje.\n' >&2
