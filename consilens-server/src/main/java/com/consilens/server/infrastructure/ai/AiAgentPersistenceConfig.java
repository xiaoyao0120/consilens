package com.consilens.server.infrastructure.ai;

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
    public MyBatisAgentPersistence myBatisAgentPersistence(SqlSessionFactory sqlSessionFactory) {
        return new MyBatisAgentPersistence(sqlSessionFactory);
    }
}
