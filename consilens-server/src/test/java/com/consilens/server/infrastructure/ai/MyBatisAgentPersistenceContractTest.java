package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.core.store.AgentPersistenceContractTest;
import com.consilens.server.infrastructure.db.mapper.ai.AiApprovalMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiEventMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanActionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiRunMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSecretMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSessionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSnapshotMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiToolCallMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.UUID;

/**
 * Runs the shared AgentPersistence contract suite against the real MyBatis
 * implementation. Every test gets a fresh in-memory H2 database, so the same
 * suite that validated InMemoryAgentPersistence now validates the DB layer.
 */
class MyBatisAgentPersistenceContractTest extends AgentPersistenceContractTest {

    @Override
    protected AgentPersistence newPersistence() {
        String url = "jdbc:h2:mem:ai_contract_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        PooledDataSource dataSource = new PooledDataSource(
                "org.h2.Driver", url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize H2 schema", e);
        }
        Configuration configuration = new Configuration(new Environment("ai-test",
                new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiSessionMapper.class);
        configuration.addMapper(AiEventMapper.class);
        configuration.addMapper(AiRunMapper.class);
        configuration.addMapper(AiToolCallMapper.class);
        configuration.addMapper(AiApprovalMapper.class);
        configuration.addMapper(AiPlanMapper.class);
        configuration.addMapper(AiPlanActionMapper.class);
        configuration.addMapper(AiSnapshotMapper.class);
        configuration.addMapper(AiSecretMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new MyBatisAgentPersistence(factory);
    }
}
