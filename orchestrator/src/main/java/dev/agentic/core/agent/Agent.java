package dev.agentic.core.agent;

import dev.agentic.core.model.ArtifactDraft;
import java.util.List;

public interface Agent {
    String name();
    List<ArtifactDraft> execute(AgentRequest request);

    interface Fn { List<ArtifactDraft> apply(AgentRequest r); }
    static Agent of(String name, Fn fn) {
        return new Agent() {
            public String name() { return name; }
            public List<ArtifactDraft> execute(AgentRequest r) { return fn.apply(r); }
        };
    }
}
