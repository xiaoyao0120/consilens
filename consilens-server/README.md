# Consilens Server

`consilens-server` is the Spring Boot HTTP service for Consilens atomic compare
capabilities. MCP and Agent skills should call this API instead of embedding server
logic.

## Build

```bash
./mvnw -pl consilens-server -am test
./mvnw -pl consilens-server -am package
```

## Production Database

`consilens-server` now defaults to the `local` Spring profile so that
`./mvnw -pl consilens-server spring-boot:run` starts against an in-memory H2 database,
auto-initializes the schema, and disables API-key enforcement for local smoke tests.

Production deployments must switch to the `prod` profile, configure a persistent
datasource, and initialize the schema before starting the service.

MySQL bootstrap:

```bash
mysql -h <host> -u <user> -p <database> < consilens-server/src/main/resources/schema-mysql.sql
```

Runtime configuration:

```bash
java -jar consilens-server/target/consilens-server-0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --spring.datasource.url=jdbc:mysql://<host>:3306/<database>?useSSL=false&serverTimezone=UTC \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password> \
  --consilens.server.node.node-key=<stable-node-key> \
  --consilens.server.security.api-key=<secret> \
  --consilens.server.artifact.local-base-dir=/var/lib/consilens/artifacts \
  --consilens.server.artifact.max-content-bytes=52428800 \
  --consilens.server.api.max-request-body-bytes=10485760
```

The API key is required when `consilens.server.security.enabled=true` and must be sent
as `X-Consilens-Api-Key` on `/v1/*` requests. `GET /actuator/health` stays open for
health checks.

For local smoke tests:

```bash
./mvnw -pl consilens-server spring-boot:run
```

## Operations

- HTTP port defaults to `18080`.
- Actuator exposes `/actuator/health` and `/actuator/info`.
- `X-Trace-Id` is propagated in API responses and should be passed by gateways, MCP,
  and automation clients.
- `consilens.server.node.node-key` can pin a stable node identity in containers or
  NATed deployments.
- Local artifact storage defaults to `./.consilens-server/artifacts`; use a persistent
  path in production.
- Artifact content is capped by `consilens.server.artifact.max-content-bytes`, default
  `52428800` bytes.
- `/v1/*` request bodies are capped by `consilens.server.api.max-request-body-bytes`,
  default `10485760` bytes.
