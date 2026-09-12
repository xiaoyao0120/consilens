package com.consilens.cluster.yarn.gateway;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HadoopYarnSubmissionGatewayHostnameTest {

    @Test
    void shouldPreferConfiguredResourceManagerHostname() {
        Configuration configuration = new Configuration(false);
        configuration.set(YarnConfiguration.RM_HOSTNAME, "resourcemanager");
        configuration.set(YarnConfiguration.RM_ADDRESS, "resourcemanager:8032");

        try (HadoopYarnSubmissionGateway gateway = new HadoopYarnSubmissionGateway(configuration)) {
            assertEquals("resourcemanager", gateway.resourceManagerHostname());
        }
    }

    @Test
    void shouldDeriveHostnameFromResourceManagerAddress() {
        Configuration configuration = new Configuration(false);
        configuration.set(YarnConfiguration.RM_ADDRESS, "resourcemanager:8032");

        try (HadoopYarnSubmissionGateway gateway = new HadoopYarnSubmissionGateway(configuration)) {
            assertEquals("resourcemanager", gateway.resourceManagerHostname());
        }
    }

    @Test
    void shouldReturnNullWithoutResourceManagerConfiguration() {
        Configuration configuration = new Configuration(false);

        try (HadoopYarnSubmissionGateway gateway = new HadoopYarnSubmissionGateway(configuration)) {
            assertNull(gateway.resourceManagerHostname());
        }
    }
}
