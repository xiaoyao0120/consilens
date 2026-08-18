package com.consilens.agent.core.runtime;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.store.AgentRunRecord;

/**
 * Executes one claimed run through the durable loop. The worker is the only
 * place that calls the model; HTTP threads never run the loop directly.
 */
public interface AgentRunWorker {

    void process(AgentRunRecord run, AgentCancellationToken cancellationToken);
}
