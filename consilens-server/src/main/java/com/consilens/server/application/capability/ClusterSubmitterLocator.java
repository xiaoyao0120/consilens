package com.consilens.server.application.capability;

import com.consilens.cluster.api.ClusterSubmitter;
import org.springframework.stereotype.Component;

/**
 * 按 platform 懒加载 ClusterSubmitter。Hadoop/Fabric8 客户端仅在集群提交时加载，
 * 反射隔离保证 local 平台无需相关依赖在 classpath 上。
 */
@Component
public class ClusterSubmitterLocator {

    public ClusterSubmitter locate(String platform) {
        String className = "yarn".equals(platform)
                ? "com.consilens.cluster.yarn.YarnClusterSubmitter"
                : "com.consilens.cluster.kubernetes.KubernetesClusterSubmitter";
        try {
            Class<?> type = Class.forName(className);
            return (ClusterSubmitter) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("cluster submitter is unavailable on the classpath: " + platform
                    + " (add consilens-cluster-yarn / consilens-cluster-kubernetes to the server distribution)", e);
        }
    }
}
