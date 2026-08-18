package com.consilens.agent.core.store;

import com.consilens.agent.api.store.AgentPersistence;

class InMemoryAgentPersistenceContractTest extends AgentPersistenceContractTest {

    @Override
    protected AgentPersistence newPersistence() {
        return new InMemoryAgentPersistence();
    }
}
