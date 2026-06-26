#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
OUT="$REPO_ROOT/target/teavm-browser-spike"
ASSETS="$OUT/assets"
COMMON_API_JAR="$ASSETS/common-api.jar"
SDK_API_JAR="$ASSETS/sdk-api.jar"
SDK_BROWSER_JAR="$ASSETS/sdk-browser.jar"
SOURCE_BUNDLE="$ASSETS/fluxzero-browser-sources.json"
PORT="${1:-8765}"

mkdir -p "$ASSETS"
cp "$ROOT/index.html" "$OUT/index.html"
cp "$ROOT/app.js" "$OUT/app.js"

download() {
    local url="$1"
    local target="$2"
    if [[ ! -f "$target" ]]; then
        curl -L --fail --show-error "$url" -o "$target"
    fi
}

download "https://teavm.org/playground/compiler.wasm" "$ASSETS/compiler.wasm"
download "https://teavm.org/playground/compiler.wasm-runtime.js" "$ASSETS/compiler.wasm-runtime.js"
download "https://teavm.org/playground/compile-classlib-teavm.bin" "$ASSETS/compile-classlib-teavm.bin"
download "https://teavm.org/playground/runtime-classlib-teavm.bin" "$ASSETS/runtime-classlib-teavm.bin"

"$REPO_ROOT/mvnw" -q -pl common-api,sdk-api,sdk-browser -am -DskipTests package
cp "$REPO_ROOT/common-api/target/common-api-0-SNAPSHOT.jar" "$COMMON_API_JAR"
cp "$REPO_ROOT/sdk-api/target/sdk-api-0-SNAPSHOT.jar" "$SDK_API_JAR"
cp "$REPO_ROOT/sdk-browser/target/sdk-browser-0-SNAPSHOT.jar" "$SDK_BROWSER_JAR"
python3 - "$REPO_ROOT" "$SOURCE_BUNDLE" <<'PY'
import json
import sys
from pathlib import Path

repo = Path(sys.argv[1])
target = Path(sys.argv[2])
files = [
    "sdk-api/src/main/java/io/fluxzero/sdk/publishing/CommandGateway.java",
    "sdk-api/src/main/java/io/fluxzero/sdk/publishing/QueryGateway.java",
]
files.extend(str(path.relative_to(repo)) for path in sorted((repo / "sdk-browser/src/main/java").rglob("*.java")))

sources = {}
for relative in files:
    path = repo / relative
    source_root = path.parts[path.parts.index("java") + 1:]
    sources["/".join(source_root)] = path.read_text()

target.write_text(json.dumps(sources, indent=2))
PY

echo "Serving TeaVM browser spike at http://localhost:$PORT"
echo "Static files: $OUT"
exec jwebserver -d "$OUT" -p "$PORT"
