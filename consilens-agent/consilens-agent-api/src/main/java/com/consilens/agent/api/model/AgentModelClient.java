package com.consilens.agent.api.model;

/**
 * Canonical model port. One implementation per provider protocol; the core
 * never branches on provider identity.
 */
public interface AgentModelClient {

    AgentModelResponse complete(AgentModelRequest request,
                                AgentModelEventListener listener,
                                AgentCancellationToken cancellationToken);
}
