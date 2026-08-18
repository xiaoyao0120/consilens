package com.consilens.agent.core;

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
 * Deterministic model stub for core loop tests (eval module owns the reusable
 * ScriptedAgentModelClient for server-side closed-loop tests).
 */
public final class TestAgentModelClient implements AgentModelClient {

    private final Queue<AgentModelResponse> script;
    private final List<AgentModelRequest> receivedRequests = Collections.synchronizedList(new ArrayList<>());

    public TestAgentModelClient(AgentModelResponse... script) {
        this.script = new ConcurrentLinkedQueue<>(List.of(script));
    }

    public TestAgentModelClient(List<AgentModelResponse> script) {
        this.script = new ConcurrentLinkedQueue<>(script);
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
        listener.onCompleted(response);
        return response;
    }

    public List<AgentModelRequest> receivedRequests() {
        synchronized (receivedRequests) {
            return List.copyOf(receivedRequests);
        }
    }

    public int playedCount() {
        return receivedRequests.size();
    }
}
