package com.consilens.agent.eval;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.model.AgentModelClient;
import com.consilens.agent.api.model.AgentModelEventListener;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Deterministic model used by closed-loop integration tests. Plays back a
 * pre-recorded script of responses and records every received request.
 */
public final class ScriptedAgentModelClient implements AgentModelClient {

    private final Queue<AgentModelResponse> script;
    private final List<AgentModelRequest> receivedRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<AgentModelResponse> playedResponses = Collections.synchronizedList(new ArrayList<>());

    public ScriptedAgentModelClient(List<AgentModelResponse> script) {
        this.script = new ConcurrentLinkedQueue<>(script);
    }

    public ScriptedAgentModelClient(AgentModelResponse... script) {
        this.script = new ConcurrentLinkedQueue<>(List.of(script));
    }

    @Override
    public AgentModelResponse complete(AgentModelRequest request,
                                       AgentModelEventListener listener,
                                       AgentCancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return AgentModelResponse.failure("MODEL_CANCELLED", false, "cancelled", 0);
        }
        AgentModelResponse response = script.poll();
        if (response == null) {
            throw new IllegalStateException("scripted model script exhausted");
        }
        receivedRequests.add(request);
        playedResponses.add(response);
        listener.onCompleted(response);
        return response;
    }

    public List<AgentModelRequest> receivedRequests() {
        synchronized (receivedRequests) {
            return List.copyOf(receivedRequests);
        }
    }

    public boolean isExhausted() {
        return script.isEmpty();
    }

    public int playedCount() {
        return playedResponses.size();
    }
}
