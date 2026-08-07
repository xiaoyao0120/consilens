# consilens-benchmark

`consilens-benchmark` 是 Consilens 的性能基准测试套件，覆盖算法微基准
（JMH）与端到端比对基准（复用 `examples/` 与 CLI 子进程），并产出基线
JSON 与漂移报告。

设计详见 `logs/06-benchmark工程设计.md`。

## 编译与单元测试

```bash
./mvnw -pl consilens-benchmark -am compile
./mvnw -pl consilens-benchmark -am test
```

`mvn test` 仅跑 `*Test` 单元测试，不触发 JMH，不拉子进程。

## 直接执行基准

```bash
./mvnw -pl consilens-benchmark -am package
./mvnw -pl consilens-benchmark -Pbenchmark exec:java \
  -Dexec.args="--mode micro"
```

## 运行方式

推荐通过仓库根目录的 `bin/run-benchmark.sh` 执行，脚本会先构建模块与依赖，
再以 `benchmark` profile 启动 `com.consilens.benchmark.runner.Main`。

```bash
# 微基准（不依赖数据库）
bin/run-benchmark.sh micro

# 端到端基准（需要数据库凭据，无库环境自动 SKIPPED 且退出 0）
bin/run-benchmark.sh e2e

# 微基准 + 端到端
bin/run-benchmark.sh all
```

可选参数透传给 Main：`--scenario E02,E03`、`--update-baseline`、`--forks N`、
`--warmup-iterations N`、`--measurement-iterations N`。

### 前置条件

- JDK 11+，Maven Wrapper 可用。
- e2e 模式需要 CLI fat jar 已构建：`./mvnw -pl consilens-cli -am package`。
- e2e 模式需要数据库凭据环境变量（复用 `examples/README.md` 的约定）：
  E02/E03 需要 `MYSQL_USER`、`MYSQL_PASSWORD`、`PG_USER`、`PG_PASSWORD`；
  join 场景需要 `MYSQL_USER`、`MYSQL_PASSWORD`。凭据缺失时场景标记
  `SKIPPED`，不会判失败。
- 可通过 `CONSILENS_CLI_JAR` 覆盖 CLI jar 路径，`CONSILENS_EXAMPLES_DIR`
  覆盖 examples 目录，`CONSILENS_BENCHMARK_SKIP_BUILD=1` 跳过自动构建。

### 基线更新

首次运行或需要重置基线时：

```bash
bin/run-benchmark.sh all --update-baseline
```

基线写入 `src/main/resources/baseline/benchmark-baseline.json`，人工 review
后提交。常规运行只做漂移判定（`--update-baseline` 缺省不写基线），有回归时
退出码非零。

报告与结果 JSON 输出到 `target/benchmark/report/`。
