# Consilens AI Runtime Usage Guide

This guide documents the **current supported closed-loop flow** for Consilens AI.

The production path is the session-based runtime exposed by `consilens-cli`, not the
older in-process chat engine. The runtime keeps config, run result, diff evidence,
diagnosis, repair artifacts and non-sensitive memories under the same session so the
workflow can iterate end-to-end.

## Closed Loop at a Glance

The supported loop is:

1. Generate a config from chat goal + explicit hints
2. Validate it and optionally dry-run it
3. Execute the real diff with explicit approval
4. Produce diagnosis from the resulting evidence
5. Regenerate a repaired config from the latest diagnosis
6. Re-run the diff until the config converges

`ai repair` regenerates **configuration**, not repair SQL.

## Recommended Workflow

Use two layers together:

1. **Bootstrap the session with `ai plan` or `ai run` and explicit flags**
2. **Continue the loop in `ai --session ...`**

That gives you both:

- deterministic connector/key/field inputs on the first turn
- a chat-style workflow for explain / diagnose / repair / rerun

## 1. Preflight Checks

Verify discovered plugins and backend/analyzer wiring:

```bash
consilens ai providers
consilens ai doctor
consilens ai --session orders-loop --config ./orders-loop.yaml --backend openai
```

Optional backend defaults:

```bash
export CONSILENS_AI_BACKEND=openai
export OPENAI_API_KEY=...
```

For a production-friendly persistent default, put backend settings in
`~/.consilens/ai/backend-defaults.json` (or `$CONSILENS_AI_HOME/backend-defaults.json`):

```json
{
  "defaultBackend": "openai",
  "shared": {
    "timeout": "30s",
    "temperature": 0.1
  },
  "backends": {
    "openai": {
      "model": "gpt-4.1-mini",
      "baseUrl": "https://api.openai.com/v1",
      "apiKeyEnv": "OPENAI_API_KEY"
    },
    "deepseek": {
      "model": "deepseek-chat",
      "baseUrl": "https://api.deepseek.com",
      "apiKeyEnv": "DEEPSEEK_API_KEY"
    },
    "ollama": {
      "model": "qwen2.5:14b",
      "baseUrl": "http://localhost:11434"
    }
  }
}
```

Supported backend selection comes from CLI flags or environment defaults:

- `--backend openai|deepseek|ollama|noop`
- `CONSILENS_AI_BACKEND`
- `CONSILENS_AI_MODEL`
- `CONSILENS_AI_BASE_URL`
- `CONSILENS_AI_TIMEOUT`
- `CONSILENS_AI_TEMPERATURE`
- `CONSILENS_AI_MAX_TOKENS`

Use `--no-llm` if you want config generation to rely only on explicit CLI hints.

The interactive entrypoints `consilens ai` and `consilens ai shell` also accept the
same startup flags, so you can bootstrap a live session directly with:

```bash
consilens ai \
  --session orders-loop \
  --config ./orders-loop.yaml \
  --backend openai \
  --base-url https://api.openai.com/v1
```

By default, `consilens ai` / `consilens ai shell` validates backend, base URL and
API key before entering the REPL. If any of them cannot be resolved from CLI flags,
environment variables, or `backend-defaults.json`, the shell exits early instead of
opening a broken chat session. Only source/target/key-style business details are left
for the clarification loop. When the first natural-language turn already looks
complete enough, the shell upgrades it into a direct `plan` using the same startup
backend settings.

## 2. Create the First Session Config

Recommended bootstrap:

```bash
consilens ai plan \
  --session orders-loop \
  "compare mysql orders with postgresql orders by order_id" \
  --source-type mysql \
  --source-url jdbc:mysql://mysql-prod:3306/shop \
  --source-table orders \
  --source-user-env MYSQL_USER \
  --source-password-env MYSQL_PASSWORD \
  --target-type postgresql \
  --target-url jdbc:postgresql://pg-staging:5432/shop \
  --target-table orders \
  --target-user-env PG_USER \
  --target-password-env PG_PASSWORD \
  --keys order_id \
  --fields status,amount,updated_at \
  --strategy-mode checksum \
  --algorithm xor \
  --dry-run \
  -o orders-loop.yaml
```

This creates a session-scoped config artifact, validates it, optionally dry-runs it,
and sets it as the current config for the session.

If you already want the runtime to execute the first loop immediately:

```bash
consilens ai run --session orders-loop --approve-execute \
  "compare mysql orders with postgresql orders by order_id" \
  --source-type mysql \
  --source-url jdbc:mysql://mysql-prod:3306/shop \
  --source-table orders \
  --target-type postgresql \
  --target-url jdbc:postgresql://pg-staging:5432/shop \
  --target-table orders \
  --keys order_id \
  --fields status,amount,updated_at
```

## 3. Continue in Chat Mode

```bash
consilens ai --session orders-loop
```

Supported interactive commands:

```text
/plan <goal>
/use-config <path>
/validate [path]
/dry-run [path]
/run [--approve-execute] <goal>
/diff [--approve-execute]
/analyze-last
/approve execute
/deny
/diagnose [path]
/repair
/remember <type> <content>
/forget <memory-id>
/recover
/artifacts [type]
/artifact <id>
/explain [path]
/snapshot
/memories
/help
/exit
```

Plain text input is also accepted, but it currently goes through keyword-based intent
routing. For production loops, prefer explicit slash commands.

If the shell or client process is interrupted, use `/recover` or
`GET /api/conversation/sessions/{sessionId}/recovery` to rebuild the current loop state from the
persisted session snapshot plus the latest diagnosis/run-audit artifacts. Recovery summaries include
artifact paths and will recommend `validate` instead of `run` when the session is still in
`needs_attention` after loading or editing a config.

## 4. Example Closed Loop Transcript

```text
consilens ai> /snapshot
session=orders-loop config=<config-artifact> latestRun=<latest-run-or-null>

consilens ai> /explain
[AI RUNTIME] session=orders-loop
... explanation of the current config and runtime risks ...

consilens ai> /run
Pending approval created. Use `/approve execute` or `/deny`.
[AI RUNTIME] session=orders-loop
Diff execution requires explicit approval. Re-run with --approve-execute.

consilens ai> /approve execute
[AI RUNTIME] session=orders-loop
Run completed for session orders-loop ...
Diagnosis: ...
Repair Hints:
- ...

consilens ai> /repair
[AI RUNTIME] session=orders-loop
Created repair plan <repair-artifact> and regenerated config <new-config-artifact>

consilens ai> /explain
[AI RUNTIME] session=orders-loop
... explanation of the repaired current config ...

consilens ai> /run --approve-execute
[AI RUNTIME] session=orders-loop
Run completed for session orders-loop ...

consilens ai> /memories
# Session Memories
- [goal] ...
- [diagnosis] ...
- [repair] ...
```

## 5. Command Semantics in the Loop

| Command | Meaning | Typical moment |
| --- | --- | --- |
| `/plan <goal>` | Generate and validate a new current config for the session | First config or goal rewrite |
| `/run [--approve-execute] [goal]` | Validate, dry-run, execute diff and immediately produce diagnosis | Verify whether the current config is good enough |
| `/approve execute` | Approve the previously pending real diff execution | After reviewing the dry-run / approval prompt |
| `/diagnose [path]` | Diagnose latest session evidence, or an explicit external result path | Re-run diagnosis or switch analyzer |
| `/repair` | Regenerate config and repair plan from the latest diagnosis | After a run produced diagnosis and repair hints |
| `/explain [path]` | Explain the current config or an explicit config file | Review a repaired config before re-running |
| `/snapshot` | Show current config and latest run pointers | Inspect session state |
| `/memories` | Show recent non-sensitive runtime memories | Understand what the runtime remembered from previous iterations |

## 6. Session Rules

1. **`--session` is the loop key**  
   Keep the same session ID across `ai plan`, `ai run`, `ai repair`, `ai explain`
   and `ai shell` if you want them to share artifacts and memories.

2. **`ai run` without a new goal reuses the current config**  
   This is the normal path when you want to verify a repaired config.

3. **`ai run` with a goal or hints regenerates the current config first**  
   Useful when you want to adjust the config and execute in one step.

4. **`ai repair` works from the latest diagnosis artifact**  
   If the session has no diagnosis, repair cannot proceed.

## 7. Diagnosing External Results

If you stay inside the runtime loop, `ai run` already produces diagnosis from its own
diff evidence. You only need `ai diagnose --result ...` when:

1. the result was produced outside the current session
2. you want to rerun diagnosis with a chosen analyzer or output path

Example:

```bash
consilens ai diagnose \
  --session orders-loop \
  --result ./diff-records.json \
  --analyzer rulebased \
  --output diagnose.md
```

The input must contain row-level diff evidence:

- a JSON array of diff records, or
- an object containing a `differences` array

A stats-only `result` JSON file is not enough.

If the result comes from plain `consilens diff`, make sure the config includes a
`json` + `diff-record` sink:

```yaml
result:
  sinks:
    - format: console
      type: result
    - format: json
      type: diff-record
      properties:
        path: ./diff-records.json
        pretty: true
```

## 8. Equivalent Non-Interactive Loop

You can run the same session loop without entering the shell:

```bash
consilens ai plan --session orders-loop "compare orders ..."
consilens ai explain --session orders-loop -c orders-loop.yaml
consilens ai run --session orders-loop --approve-execute
consilens ai repair --session orders-loop -o orders-loop-repaired.yaml
consilens ai run --session orders-loop --approve-execute
consilens ai memories --session orders-loop
```

## 9. Boundaries of the Current Design

- The supported orchestration entrypoint is the runtime in `consilens-cli`
- Intent parsing is keyword-based and best used as a convenience layer, not as the
  only control surface for production loops
- Repair is config regeneration plus a repair-plan artifact
- Actual diff execution still uses the deterministic Consilens engine
- Analyzer and backend providers are loaded via SPI and should be checked with
  `ai providers` / `ai doctor`
