#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
OUT="$ROOT/target/release-checks/$RUN_ID"
LOG_DIR="$OUT/logs"
mkdir -p "$LOG_DIR"

WITH_MCP=true
for arg in "$@"; do
  case "$arg" in
    --skip-mcp)
      WITH_MCP=false
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

run_gate() {
  local name="$1"
  shift
  echo "==> $name"
  "$@" 2>&1 | tee "$LOG_DIR/$name.log"
}

find_java17() {
  if [[ -n "${JAVA17_HOME:-}" && -x "$JAVA17_HOME/bin/java" ]]; then
    echo "$JAVA17_HOME"
    return 0
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local home
    if home="$(/usr/libexec/java_home -v 17 2>/dev/null)" && [[ -x "$home/bin/java" ]]; then
      echo "$home"
      return 0
    fi
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    local major
    major="$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '[._]' '{print $1}')"
    if [[ "$major" == "17" || "$major" -gt 17 ]]; then
      echo "$JAVA_HOME"
      return 0
    fi
  fi
  return 1
}

smoke_dist_cli() {
  local archive
  archive="$(ls -t consilens-dist/target/consilens-*.tar.gz | head -1)"
  if [[ -z "$archive" || ! -f "$archive" ]]; then
    echo "Release archive not found" >&2
    exit 1
  fi

  local smoke_dir="$OUT/smoke"
  rm -rf "$smoke_dir"
  mkdir -p "$smoke_dir"
  tar -xzf "$archive" -C "$smoke_dir"

  local app
  app="$(find "$smoke_dir" -maxdepth 1 -type d -name 'consilens-*' | head -1)"
  if [[ -z "$app" || ! -x "$app/bin/consilens-cli.sh" ]]; then
    echo "Unpacked CLI launcher not found" >&2
    exit 1
  fi
  if ! find "$app/agent-skills" -mindepth 2 -maxdepth 2 -name SKILL.md | grep -q .; then
    echo "Agent skills not found in release archive" >&2
    exit 1
  fi

  "$app/bin/consilens-cli.sh" --version
  "$app/bin/consilens-cli.sh" ai providers --format json
  "$app/bin/consilens-cli.sh" ai doctor --format json --backend noop
}

smoke_server_artifact() {
  local jar
  jar="$(ls -t consilens-server/target/consilens-server-*.jar | head -1)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "Server jar not found" >&2
    exit 1
  fi

  unzip -p "$jar" META-INF/MANIFEST.MF | grep -q '^Main-Class: org.springframework.boot.loader.JarLauncher'
  unzip -p "$jar" META-INF/MANIFEST.MF | grep -q '^Start-Class: com.consilens.server.ConsilensServerApplication'
  jar tf "$jar" | grep -q '^BOOT-INF/classes/com/consilens/server/ConsilensServerApplication.class$'
}

smoke_mcp() {
  local java_home="$1"
  local jar
  jar="$(ls -t consilens-mcp/target/consilens-mcp-*.jar | grep -v -- '-original\\.jar$' | head -1)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "MCP jar not found" >&2
    exit 1
  fi

  local version
  version="$("$java_home/bin/java" -jar "$jar" --version)"
  if [[ "$version" != consilens-mcp\ * ]]; then
    echo "Unexpected MCP version output: $version" >&2
    exit 1
  fi
  echo "$version"
}

run_gate root-tests ./mvnw -B test
run_gate server-package ./mvnw -B -pl consilens-server -am package -DskipTests
run_gate server-smoke smoke_server_artifact
run_gate release-package ./mvnw -B -Prelease -pl consilens-dist -am package -DskipTests
run_gate dist-cli-smoke smoke_dist_cli

if [[ "$WITH_MCP" == "true" ]]; then
  JAVA17="$(find_java17 || true)"
  if [[ -z "$JAVA17" ]]; then
    echo "Java 17 not found. Set JAVA17_HOME or run with --skip-mcp." >&2
    exit 1
  fi
  run_gate mcp-package env JAVA_HOME="$JAVA17" ./mvnw -B -Pconsilens-mcp -pl consilens-mcp package
  run_gate mcp-smoke smoke_mcp "$JAVA17"
fi

echo "Release checks passed. Logs: $LOG_DIR"
