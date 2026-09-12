# 集群 Comparison Runtime

`consilens-cluster-application` 是 YARN ApplicationMaster 和 Kubernetes Job 的实际入口。它读取 YAML/JSON descriptor，解析运行环境中的 `${env.NAME}`，调用现有 `DefaultCompareRuntime` 执行一次数据比对，并只向 stdout 输出统计摘要。

## 构建

```bash
mvn -pl :consilens-cluster-application -am -DskipTests package
mvn -pl consilens-cli -am -Pyarn-client -DskipTests package
mvn -pl consilens-cli -am -Pkubernetes-client -DskipTests package
```

YARN archive 产物是 `consilens-cluster/consilens-cluster-application/target/consilens-cluster-application-0.1-SNAPSHOT-runtime.zip`。上传至 HDFS 后，YARN 将其解压为 `consilens-runtime/`，启动 classpath 为 `consilens-runtime/*`。

Kubernetes runtime image 使用同一个 shaded jar：

```bash
docker build -f deploy/cluster/Dockerfile -t registry.example/consilens-runtime:0.1.0 .
```

镜像将 jar 放在 `/opt/consilens/runtime/`，与 Kubernetes Job 的固定 classpath 一致。

## 提交

YARN 既可使用已上传的远端 URI，也可直接传本地 runtime archive 与 descriptor。本地文件由提交器通过 Hadoop `FileSystem` 自动复制到 `--staging-uri` 指向的远端目录，等价于 Spark/YARN 把本地 jar 复制到 `spark.yarn.stagingDir` 后再注册 `LocalResource`：

`--runtime-archive` 既接受 `consilens-cluster-application-...-runtime.zip`（以 `ARCHIVE` 解压后按 `consilens-runtime/*` 启动），也接受单个 fat jar（以 `FILE` 本地化后按 `consilens-runtime.jar` 启动），提交器按扩展名自动选择：

```bash
java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --runtime-archive consilens-cluster/consilens-cluster-application/target/consilens-cluster-application-0.1-SNAPSHOT-runtime.zip \
  --descriptor-uri comparison.yaml \
  --staging-uri hdfs://namenode/apps/consilens/staging \
  --secret-env-file secrets.properties
```

远端 URI 用法保持兼容；本地路径只有在同时提供 `--staging-uri` 时才被接受：

```bash
java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --runtime-archive hdfs://namenode/apps/consilens/runtime.zip \
  --descriptor-uri hdfs://namenode/apps/consilens/comparison.yaml \
  --secret-env-file hdfs://namenode/apps/consilens/secrets.properties
```

Kubernetes 支持两种 descriptor 来源。使用本地文件时，提交器创建 ConfigMap 并挂载到 `/opt/consilens/descriptor`，Coordinator 从挂载路径读取：

```bash
java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar submit kubernetes \
  --image registry.example/consilens-runtime:0.1.0 \
  --descriptor comparison.yaml \
  --secret-env SOURCE_PASSWORD=consilens-database/source-password \
  --secret-env TARGET_PASSWORD=consilens-database/target-password
```

也可使用容器可访问的 HTTP(S) URI：

```bash
java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar submit kubernetes \
  --image registry.example/consilens-runtime:0.1.0 \
  --descriptor-uri https://config.example/consilens/comparison.yaml \
  --secret-env SOURCE_PASSWORD=consilens-database/source-password \
  --secret-env TARGET_PASSWORD=consilens-database/target-password
```

如果 runtime fat jar 尚未内置到镜像，可让提交器通过 HTTP PUT 上传，再由 initContainer 下载到 Pod 内的 `/opt/consilens/runtime`。Kubernetes 本地 runtime 必须是单个 fat jar，不支持这里传 zip（zip 需要先解压，initContainer 不负责解压）：

```bash
java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar submit kubernetes \
  --image registry.example/consilens-coordinator:0.1.0 \
  --local-runtime consilens-cluster/consilens-cluster-application/target/consilens-cluster-application-0.1-SNAPSHOT.jar \
  --runtime-upload-url https://artifact.example/consilens/upload \
  --runtime-download-url https://artifact.example/consilens/download \
  --init-image curlimages/curl:8.4.0 \
  --descriptor comparison.yaml
```

`runtime-upload-url` 是提交机可写的 HTTP(S) 根目录，`runtime-download-url` 是 Pod 可读的同一共享存储根目录。两者指向同一存储服务时最直接，但也允许由网关转发，业务上必须保证上传后的文件能被 initContainer 下载到。

`--am-class` 和 `--coordinator-class` 默认均为 `com.consilens.cluster.application.ClusterComparisonCoordinator`，仅在自定义 runtime 时覆盖。首期执行模型为单个 coordinator process，不包含分片 worker、结果持久化或状态查询。

`-c/--config` 为兼容已有脚本保留的可选参数；YARN/Kubernetes 提交不会读取它。运行时读取的配置来自 `--descriptor-uri`（远端）或 `--descriptor`（本地 ConfigMap/挂载）指向的 descriptor。
