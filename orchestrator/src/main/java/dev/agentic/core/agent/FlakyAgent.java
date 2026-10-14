package dev.agentic.core.agent;

import dev.agentic.core.model.ArtifactDraft;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/** Fault injection: fails or poisons the first N calls, then behaves. Used for chaos demos and resilience tests. */
public final class FlakyAgent implements Agent {
    private final Agent delegate; private final int faults; private final String poison;
    private final AtomicInteger calls = new AtomicInteger();

    private FlakyAgent(Agent d, int faults, String poison) { delegate = d; this.faults = faults; this.poison = poison; }
    public static UnaryOperator<Agent> failingFirst(int n) { return d -> new FlakyAgent(d, n, null); }
    /** Appends the given text to every output of the first n calls (e.g. a fake secret to trip a policy). */
    public static UnaryOperator<Agent> poisoningFirst(int n, String text) { return d -> new FlakyAgent(d, n, text); }
    public static Agent alwaysFailing(Agent d) { return new FlakyAgent(d, Integer.MAX_VALUE, null); }

    @Override public String name() { return delegate.name(); }
    @Override
    public List<ArtifactDraft> execute(AgentRequest r) {
        boolean faulty = calls.incrementAndGet() <= faults;
        if (faulty && poison == null) throw new AgentException("injected failure (call " + calls.get() + ")");
        List<ArtifactDraft> out = delegate.execute(r);
        return faulty ? out.stream().map(d -> new ArtifactDraft(d.name(), d.content() + "\n" + poison)).toList() : out;
    }
}
