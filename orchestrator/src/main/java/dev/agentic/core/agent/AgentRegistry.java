package dev.agentic.core.agent;

import java.util.*;

public final class AgentRegistry {
    private final Map<String, Agent> agents = new HashMap<>();
    public AgentRegistry register(Agent a) { agents.put(a.name(), a); return this; }
    public Agent get(String name) {
        Agent a = agents.get(name);
        if (a == null) throw new NoSuchElementException("no agent registered: " + name);
        return a;
    }
    /** Replace an agent (used to inject chaos/faults in demos and tests). */
    public AgentRegistry decorate(String name, java.util.function.UnaryOperator<Agent> f) { agents.put(name, f.apply(get(name))); return this; }
}
