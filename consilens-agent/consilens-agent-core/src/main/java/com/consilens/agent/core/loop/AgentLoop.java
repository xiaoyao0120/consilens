package com.consilens.agent.core.loop;

import com.consilens.agent.api.model.AgentCancellationToken;

/**
 * Durable agent loop entry point. {@code run} starts a new run;
 * {@code continueRun} resumes after secret/approval/retry. Both execute the
 * model/tool loop synchronously on the calling worker thread.
 */
public interface AgentLoop {

    AgentLoopResult run(AgentLoopRequest request, AgentCancellationToken cancellationToken);

    AgentLoopResult continueRun(AgentLoopRequest request, AgentCancellationToken cancellationToken);
}
