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

YARN 提交遵循 spark-submit 的使用习惯：descriptor 像应用 jar 一样作为位置参数传入，密钥文件作为第二个位置参数；`--conf` / `--properties-file` 用 `consilens.yarn.*` 键（键名对齐 `spark.yarn.*`：archive、stagingDir、maxAppAttempts、queue、tags、amMemory、amVCores、submit.waitAppCompletion）设置默认值，CLI 选项优先于配置文件。runtime archive 推荐预传 HDFS 一次（等价 `spark.yarn.archive`），日常提交只传 descriptor：

```bash
# 一次性上传 runtime
hdfs dfs -put -f consilens-cluster-application-0.1-SNAPSHOT-runtime.zip /apps/consilens/runtime.zip

# consilens-defaults.conf: consilens.yarn.archive=hdfs:///apps/consilens/runtime.zip 等默认值
java -jar consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --properties-file consilens-defaults.conf \
  comparison.yaml secrets.properties
```

不加 `--properties-file`/`--conf` 时全部使用与 spark-submit 一致的默认值：staging 在用户 HDFS home 下的 `.consilens/staging/<submissionId>/`（应用成功后自动清理）、queue=default、内存 1g / 1 vcore（`--am-memory` 接受 `1g`/`2048m` 格式）、maxAppAttempts 跟随集群默认。提交后客户端默认等待应用结束并输出 finalStatus 与 tracking URL（`--conf consilens.yarn.submit.waitAppCompletion=false` 可关闭）。

随提交上传本地文件/jar（同 spark-submit 的 `--files`/`--jars`：逗号分隔、支持 `#alias`、远端 URI 不重传；`--files` 在 AM 容器内按文件名可见，`--jars` 追加到 AM classpath 且排在 runtime 之后）：

```bash
java -jar consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --properties-file consilens-defaults.conf \
  --files ./extra-config.properties#my-config.properties,other.json \
  --jars ./ojdbc8.jar \
  comparison.yaml secrets.properties
```

也可以全部显式指定，本地文件无需配置 staging（自动落到用户 home staging）：

```bash
java -jar consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --runtime-archive consilens-cluster/consilens-cluster-application/target/consilens-cluster-application-0.1-SNAPSHOT-runtime.zip \
  --am-memory 2g --am-vcores 2 --queue data-quality \
  comparison.yaml secrets.properties
```

远端 URI 同样接受：`--runtime-archive hdfs://namenode/apps/consilens/runtime.zip`，descriptor/secret 也可用远端 URI 代替位置参数。`--runtime-archive` 既接受 runtime.zip（以 `ARCHIVE` 解压后按 `consilens-runtime/*` 启动），也接受单个 fat jar（以 `FILE` 本地化后按 `consilens-runtime.jar` 启动），提交器按扩展名自动选择。提交机的 `HADOOP_CONF_DIR` 会作为 LocalResource 打进 AM 容器（同 Spark 的 `__spark_conf__`），因此能提交 Spark 应用的环境无需任何额外配置即可提交 consilens；YARN 容器需由集群提供 Java 11 的 `JAVA_HOME`（如 `yarn.nodemanager.admin-env`）。

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

coordinator 类（`com.consilens.cluster.application.ClusterComparisonCoordinator`）是内部实现细节，不作为 CLI 选项暴露，由 runtime 自带。YARN 模式由固定的 `ConsilensApplicationMaster` 包装类负责 RM 注册、心跳与最终状态上报（与 Spark 的 `ApplicationMaster` 包装用户 driver 相同的模型），coordinator 本身是平台无关的普通 main。staging 布局为 `<stagingDir>/<submissionId>/`（同 Spark `spark.yarn.stagingDir`），AM 执行成功后自动清理该目录。首期执行模型为单个 coordinator process，不包含分片 worker、结果持久化或状态查询。

`-c/--config` 为兼容已有脚本保留的可选参数；YARN/Kubernetes 提交不会读取它。运行时读取的配置来自 `--descriptor-uri`（远端）或 `--descriptor`（本地 ConfigMap/挂载）指向的 descriptor。
