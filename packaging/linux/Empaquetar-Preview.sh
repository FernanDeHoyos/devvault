#!/usr/bin/env bash
set -Eeuo pipefail

version="${1:-0.4.0-preview.1}"
[[ "$version" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "La versión solo admite letras, números, punto y guion." >&2; exit 2; }

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
backend_root="$(cd -- "$script_dir/../.." && pwd -P)"
ui_root="$backend_root/ui"
distribution_root="$backend_root/build/distribution"
ui_build_dir="$backend_root/build/preview-ui"
stage_root="$distribution_root/DevVault-$version-linux-$(uname -m)"
archive="$distribution_root/DevVault-$version-linux-$(uname -m).tar.gz"

command -v npm >/dev/null 2>&1 || { echo "Se necesita Node.js y npm para compilar la UI." >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "Se necesita Node.js para generar el SBOM de la UI." >&2; exit 1; }
[[ -x "$backend_root/gradlew" ]] || { echo "No se encontró el wrapper Gradle en $backend_root." >&2; exit 1; }
[[ -f "$ui_root/package.json" ]] || { echo "No se encuentra la UI. Inicializa el submódulo con git submodule update --init --recursive." >&2; exit 1; }

if [[ -z "${JAVA_HOME:-}" ]]; then
  java_command="$(command -v java || true)"
  [[ -n "$java_command" ]] || { echo "Define JAVA_HOME a un JDK 17." >&2; exit 1; }
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$java_command")")")"
fi
export JAVA_HOME
jlink="$JAVA_HOME/bin/jlink"
[[ -x "$jlink" ]] || { echo "No se encontró jlink. JAVA_HOME debe apuntar a un JDK 17." >&2; exit 1; }
jdk_version="$("$jlink" --version)"
[[ "$jdk_version" == 17.* ]] || { echo "El paquete usa Java 17; JDK detectado: $jdk_version." >&2; exit 1; }

mkdir -p "$distribution_root"
case "$stage_root" in "$distribution_root"/*) ;; *) echo "Ruta de empaquetado insegura." >&2; exit 1 ;; esac
rm -rf -- "$stage_root"
mkdir -p "$stage_root"

echo "1/4 Instalando dependencias y compilando UI..."
(cd "$ui_root" && npm ci && npm run build -- --outDir "$ui_build_dir" --emptyOutDir)
[[ -f "$ui_build_dir/index.html" ]] || { echo "La UI no produjo index.html." >&2; exit 1; }

echo "2/4 Generando informes de licencias y JAR ejecutable con UI integrada..."
(cd "$backend_root" && ./gradlew extractRuntimeDependencyLicenses "-PpreviewUiDir=$ui_build_dir" --no-daemon --console=plain)
jar_file="$(find "$backend_root/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' ! -name 'devvault.jar' -printf '%T@ %p\n' | sort -nr | head -n1 | cut -d' ' -f2-)"
[[ -n "$jar_file" && -f "$jar_file" ]] || { echo "Gradle no generó un JAR ejecutable." >&2; exit 1; }

echo "3/4 Creando runtime reducido de Java 17..."
"$jlink" --add-modules ALL-MODULE-PATH --strip-debug --no-man-pages \
  --no-header-files --compress=2 --output "$stage_root/runtime"
cp -- "$jar_file" "$stage_root/devvault.jar"
cp -- "$script_dir/compose.preview.yml" "$stage_root/"
cp -- "$script_dir/Iniciar-DevVault.sh" "$script_dir/Detener-DevVault.sh" "$stage_root/"
cp -- "$backend_root/LICENSE" "$stage_root/LICENSE"
cp -- "$script_dir/LEEME.txt" "$stage_root/LEEME.txt"
chmod +x "$stage_root/Iniciar-DevVault.sh" "$stage_root/Detener-DevVault.sh"

notice_root="$stage_root/third-party-notices"
mkdir -p "$notice_root"
cp -- "$backend_root/docs/THIRD-PARTY-NOTICES.md" "$notice_root/"
cp -- "$backend_root"/build/reports/dependency-license/java-dependencies.* "$notice_root/"
cp -- "$backend_root/build/reports/dependency-license/java-license-file-status.txt" "$backend_root/build/reports/dependency-license/java-packaged-jars.txt" "$notice_root/"
cp -R -- "$backend_root/build/reports/dependency-license/java" "$notice_root/"
node "$backend_root/scripts/collect-ui-license-files.mjs" \
  "$ui_root" "$notice_root"
for required in THIRD-PARTY-NOTICES.md java-dependencies.csv java-dependencies.html java-dependencies.xml java-license-file-status.txt java-packaged-jars.txt ui-sbom.cdx.json ui-license-file-status.txt; do
  [[ -s "$notice_root/$required" ]] || { echo "Falta el documento de terceros requerido: $required" >&2; exit 1; }
done
embedded_jar_count="$(jar tf "$stage_root/devvault.jar" | grep -Ec '^BOOT-INF/lib/[^/]+\.jar$')"
listed_jar_count="$(wc -l < "$notice_root/java-packaged-jars.txt" | tr -d ' ')"
[[ "$embedded_jar_count" -gt 0 && "$embedded_jar_count" -eq "$listed_jar_count" ]] \
  || { echo "La lista Java no coincide con los JAR incluidos en el backend." >&2; exit 1; }
[[ -n "$(find "$notice_root/java" -type f -print -quit)" ]] \
  || { echo "No se recopilaron textos de licencia de dependencias Java." >&2; exit 1; }
[[ -n "$(find "$notice_root/ui" -type f -print -quit)" ]] \
  || { echo "No se recopilaron textos de licencia de dependencias UI." >&2; exit 1; }

echo "4/4 Comprimiendo paquete..."
rm -f -- "$archive"
tar -C "$distribution_root" -czf "$archive" "$(basename "$stage_root")"
echo "Preview lista: $archive ($(du -h "$archive" | cut -f1))"
