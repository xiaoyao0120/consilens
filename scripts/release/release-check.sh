#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
OUT="$ROOT/target/release-checks/$RUN_ID"
LOG_DIR="$OUT/logs"
mkdir -p "$LOG_DIR"

WITH_MCP=true
DOCKER_VERIFY_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --skip-mcp)
      WITH_MCP=false
      ;;
    --docker-verify-only)
      DOCKER_VERIFY_ONLY=true
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

docker_verify() {
  local report_dir="$ROOT/consilens-core/target/failsafe-reports"
  local reports=()
  local report
  local tests
  local skipped
  local errors
  local failures

  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker CLI is required for the integration verify gate." >&2
    return 1
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is unavailable; refusing to skip integration verification." >&2
    return 1
  fi

  rm -rf "$report_dir"
  ./mvnw -B -pl consilens-core -am verify

  if [[ ! -f "$report_dir/failsafe-summary.xml" ]]; then
    echo "Failsafe summary is missing: $report_dir/failsafe-summary.xml" >&2
    return 1
  fi

  while IFS= read -r report; do
    reports+=("$report")
  done < <(find "$report_dir" -maxdepth 1 -type f -name 'TEST-*.xml' -print | sort)
  if [[ "${#reports[@]}" -eq 0 ]]; then
    echo "Failsafe did not produce any TEST-*.xml integration reports." >&2
    return 1
  fi

  for external_test in CrossDatabaseDockerITest OceanBaseSameDbComparisonTest OceanBaseCrossDbComparisonTest; do
    if find "$report_dir" -maxdepth 1 -type f -name "*${external_test}*.xml" -print -quit | grep -q .; then
      echo "Default Docker verify unexpectedly ran fixed-service test: $external_test" >&2
      return 1
    fi
  done

  read -r tests skipped errors failures < <(
    awk '
      match($0, /tests="[0-9]+"/) { value = substr($0, RSTART + 7, RLENGTH - 8); tests += value }
      match($0, /skipped="[0-9]+"/) { value = substr($0, RSTART + 9, RLENGTH - 10); skipped += value }
      match($0, /errors="[0-9]+"/) { value = substr($0, RSTART + 8, RLENGTH - 9); errors += value }
      match($0, /failures="[0-9]+"/) { value = substr($0, RSTART + 10, RLENGTH - 11); failures += value }
      END { print tests + 0, skipped + 0, errors + 0, failures + 0 }
    ' "${reports[@]}"
  )

  if [[ "$tests" -eq 0 ]]; then
    echo "Failsafe reports contain zero executed integration tests." >&2
    return 1
  fi
  if [[ "$skipped" -ne 0 ]] || grep -Eq '<skipped([[:space:]/>])' "${reports[@]}"; then
    echo "Integration reports contain skipped tests (skipped=$skipped)." >&2
    return 1
  fi
  if [[ "$errors" -ne 0 || "$failures" -ne 0 ]]; then
    echo "Integration reports contain failures (errors=$errors, failures=$failures)." >&2
    return 1
  fi

  echo "Docker integration verify passed: tests=$tests, skipped=$skipped."
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

if [[ "$DOCKER_VERIFY_ONLY" == "true" ]]; then
  run_gate docker-verify docker_verify
  echo "Docker integration verify passed. Logs: $LOG_DIR"
  exit 0
fi

run_gate root-tests ./mvnw -B test
run_gate docker-verify docker_verify
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
