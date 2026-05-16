# Consilens AI Module

Consilens AI is the AI/runtime layer for Consilens. It combines natural-language intent parsing,
session-scoped runtime orchestration, diff diagnosis, and repair-driven config regeneration on top
of the deterministic Consilens engine.

## Features

- 🤖 **Natural Language Interface**: Understand comparison and repair goals in plain language
- 🔁 **Closed-Loop Runtime**: Generate config, execute diff, diagnose, repair config, and rerun within one session
- 🔍 **Smart Diff Analysis**: Identify inconsistencies and root-cause patterns from diff evidence
- 🛠️ **Repair-Driven Config Regeneration**: Rebuild better Consilens YAML from the latest diagnosis
- 🔌 **Pluggable Architecture**: Extensible tool system and LLM backend support
- 🧠 **Pattern Detection**: Identify common root causes like timezone mismatches, encoding issues, and data truncation
- 📊 **Schema Discovery**: Automatically discover and document database table schemas
- 🔒 **Security-First Design**: Input validation, password protection, and audit-friendly operations

## Quick Start

### Prerequisites

- Java 11+
- Maven 3.6+
- Optional: Ollama for local LLM support

### Building

```bash
mvn clean install -pl consilens-ai -am
```

### Basic Usage

Production entrypoint: the session runtime in `consilens-cli`.

Minimal closed-loop demo:

```bash
consilens ai doctor

consilens ai plan \
  --session demo-orders \
  "compare mysql orders with postgresql orders by order_id" \
  --no-llm \
  --source-type mysql \
  --source-url jdbc:mysql://127.0.0.1:3306/shop \
  --source-table orders \
  --source-user-env MYSQL_USER \
  --source-password-env MYSQL_PASSWORD \
  --target-type postgresql \
  --target-url jdbc:postgresql://127.0.0.1:5432/shop \
  --target-table orders \
  --target-user-env PG_USER \
  --target-password-env PG_PASSWORD \
  --keys order_id \
  --fields status,amount,updated_at \
  --dry-run \
  -o demo-orders.yaml

consilens ai run --session demo-orders --approve-execute
consilens ai repair --session demo-orders -o demo-orders-repaired.yaml
consilens ai run --session demo-orders --approve-execute
consilens ai --session demo-orders
```

External diff-record diagnosis demo:

```bash
consilens diff -c demo-orders.yaml

consilens ai diagnose \
  --session demo-orders \
  --result ./diff-records.json \
  --analyzer rulebased \
  --output demo-diagnose.md

consilens ai repair --session demo-orders -o demo-orders-repaired.yaml
consilens ai run --session demo-orders --approve-execute
```

The runtime can execute a real diff, but only with explicit approval. `ai diagnose` reads
row-level diff evidence; stats-only result files are not enough for pattern analysis.
The analyzer is loaded via SPI. Use `--analyzer <name>` or `CONSILENS_AI_ANALYZER`; the default is `rulebased`.
Use `--output` to persist the diagnosis report; otherwise it is printed to stdout.
Use `ai providers` to verify which analyzer and LLM backend plugins are visible on the runtime classpath; `--format json` is available for CI checks and scripts.
Use `ai doctor` as a production preflight check for SPI discovery, selected analyzer/backend wiring and required API key configuration. It is offline by default; add `--online` only when the deployment environment should verify backend reachability.
For persistent backend defaults, use `~/.consilens/ai/backend-defaults.json` (or `$CONSILENS_AI_HOME/backend-defaults.json`) and keep secrets in `apiKeyEnv`-referenced environment variables instead of hardcoding them into command lines.

For the full workflow, see:

- [`consilens-cli/README.md`](../consilens-cli/README.md) - Chinese closed-loop guide and operator runbook
- [`USAGE.md`](./USAGE.md) - detailed runtime closed-loop guide

SDK/runtime notes:

```java
// The supported orchestration entrypoint is the AI runtime in consilens-cli.
// ai-core keeps reusable primitives such as conversation context, tool registry,
// intent parsing, runtime models, draft validation and SPI interfaces.
ConversationContext conversation = new ConversationContext();
ToolRegistry tools = new ToolRegistry();
tools.register(new DiffTool());
tools.register(new AnalyzeTool());
tools.register(new SchemaDiscoveryTool());

Intent intent = new IntentParser().parse("Compare my production and staging users table");
System.out.println(intent); // DIFF_TABLE
```

## Module Structure

```
consilens-ai/
├── consilens-ai-core/          # Intent parsing, runtime models, session abstractions
├── consilens-ai-analyzer/      # Pattern detection engine
│   ├── consilens-ai-analyzer-api/
│   └── consilens-ai-analyzer-plugins/consilens-ai-analyzer-rulebased/
├── consilens-ai-llm/           # LLM backend support
│   ├── consilens-ai-llm-api/
│   └── consilens-ai-llm-plugins/
│       ├── consilens-ai-llm-noop/
│       ├── consilens-ai-llm-ollama/
│       ├── consilens-ai-llm-openai/
│       └── consilens-ai-llm-deepseek/
└── consilens-ai-tool/          # Tool system
    ├── consilens-ai-tool-api/
    └── consilens-ai-tool-plugins/consilens-ai-tool-defaults/
```

## Available Tools

### DiffTool
Compares two database tables via JDBC and identifies all differences.

This tool is intended for SDK/demo usage. Production CLI flows should generate a YAML config and execute through `DiffService` / `DefaultCompareRuntime`.

**Example**: "Compare the orders table between production and staging"

### AnalyzeTool
Analyzes a diff result to identify patterns, root causes, and repair suggestions.

**Example**: "What patterns do you see in the differences?"

### RepairGenerateTool
Generates SQL suggestions for a diff result as a low-level SDK tool.

This is **not** the default production repair loop. The production CLI/runtime flow uses
`ai repair` to regenerate Consilens configuration from the latest diagnosis and then
re-validates it with `ai run`.

**Example**: "Generate SQL to fix these mismatches on the target side"

### SchemaDiscoveryTool
Discovers table schemas from JDBC connections.

**Example**: "Show me the schema of the users table"

### ConfigGenerateTool
Generates Consilens YAML configuration files for table comparisons.

**Example**: "Generate a config for comparing these tables"

## Pattern Detection

The built-in rule-based analyzer detects:

- **EncodingPattern**: Character encoding mismatches
- **TimeWindowPattern**: Time range filtering issues
- **TimeDriftPattern**: Timezone and time synchronization problems
- **NullHandlingPattern**: Null vs empty string differences
- **PrecisionLossPattern**: Floating-point and decimal precision issues
- **TruncationPattern**: String or numeric truncation

## Configuration

### LLM Backend

Configure Ollama (local LLM):
```java
LLMBackend backend = new OllamaBackend("http://localhost:11434");
```

Configure OpenAI:
```java
LLMBackend backend = new OpenAIBackend("https://api.openai.com/v1", "gpt-4.1-mini", System.getenv("OPENAI_API_KEY"));
```

Configure DeepSeek:
```java
LLMBackend backend = new DeepSeekBackend("https://api.deepseek.com", "deepseek-chat", System.getenv("DEEPSEEK_API_KEY"));
```

Or use no-op backend (fallback to rule-based):
```java
LLMBackend backend = new NoopBackend();
```

### Connection Management

```java
ConversationContext context = new ConversationContext();
context.registerConnection("prod", 
    ConnectionInfo.builder()
        .type("mysql")
        .url("jdbc:mysql://prod-server:3306/db")
        .username("user")
        .password("password")
        .build());
```

## Security

- **Input Validation**: All user inputs are sanitized to prevent prompt injection
- **Password Protection**: Credentials are marked transient and never logged
- **Explicit Execution Approval**: Real diff execution in the runtime requires explicit approval
- **Audit Trail**: Tool operations are logged for compliance

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed security considerations.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture and design decisions
- [USAGE.md](USAGE.md) - Detailed usage guide with examples
- [API Documentation](../docs/) - Complete API reference

## Testing

Run tests with:
```bash
mvn test -pl consilens-ai -am
```

Test coverage includes:
- Intent parsing (multilingual)
- Tool execution and error handling
- LLM integration with mocked backends
- Configuration generation
- Schema discovery

## Contributing

To add a custom tool:

1. Implement the `Tool` interface
2. Register via `ToolRegistry.register(tool)`
3. Wire the tool into the runtime or command layer that should expose it

To add a new analyzer:

1. Implement the `AIAnalyzer` interface
2. Register via ServiceLoader in `META-INF/services/`

To add a new LLM backend:

1. Implement the `LLMBackend` interface
2. Register via ServiceLoader in `META-INF/services/`

## Performance Considerations

- Tool execution is synchronous; long-running operations block the conversation
- Set reasonable limits on database queries to avoid memory issues
- Consider connection pooling for repeated database access
- Conversation history grows with interaction count

## Troubleshooting

### No response or "No LLM backend is configured"
- Ensure Ollama is running: `ollama serve`
- Verify OllamaBackend URL configuration
- Use NoopBackend if LLM is not available

### Tool execution errors
- Check JDBC URLs, credentials, and firewall settings
- Verify database user has required permissions
- Review tool input schema for required parameters

### Input too long
- Messages are truncated to 10,000 characters
- Break long requests into multiple queries

## License

See [LICENSE](../../LICENSE) file in the root directory.

## Support

For issues, questions, or contributions, see the main repository: https://github.com/NoeticLens/consilens
