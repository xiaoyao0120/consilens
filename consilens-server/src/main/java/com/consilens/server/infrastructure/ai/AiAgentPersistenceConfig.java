package com.consilens.server.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the durable agent persistence onto the application SqlSessionFactory.
 * The AgentRunWorker and recovery scheduler (WP-07) depend on this bean.
 */
@Configuration
public class AiAgentPersistenceConfig {

    @Bean
    public MyBatisAgentPersistence myBatisAgentPersistence(SqlSessionFactory sqlSessionFactory,
                                                           ObjectMapper objectMapper) {
        return new MyBatisAgentPersistence(sqlSessionFactory, objectMapper);
    }
}
