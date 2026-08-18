package com.consilens.server.application.ai;

import com.consilens.agent.api.policy.AgentActor;
import com.consilens.server.boot.ConsilensServerProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AgentPrincipalResolverTest {

    @Test
    void apiKeyFingerprintIsStableAndDeterministic() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setApiKey("secret-key-1");
        AgentPrincipalResolver resolver = new AgentPrincipalResolver(properties);

        AgentActor first = resolver.resolve();
        AgentActor second = resolver.resolve();
        assertEquals(first.getId(), second.getId());
        assertEquals(64, first.getId().length());
    }

    @Test
    void differentKeysProduceDifferentActors() {
        ConsilensServerProperties a = new ConsilensServerProperties();
        a.getSecurity().setApiKey("key-a");
        ConsilensServerProperties b = new ConsilensServerProperties();
        b.getSecurity().setApiKey("key-b");
        assertNotEquals(new AgentPrincipalResolver(a).resolve().getId(),
                new AgentPrincipalResolver(b).resolve().getId());
    }

    @Test
    void localActorWhenSecurityDisabled() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getSecurity().setEnabled(false);
        properties.getSecurity().setApiKey(null);
        assertEquals("local", new AgentPrincipalResolver(properties).resolve().getId());
    }
}
