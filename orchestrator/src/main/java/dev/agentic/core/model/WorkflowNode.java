package dev.agentic.core.model;

import java.util.*;

/** One stage of the SDLC graph. Dependencies are derived from 'consumes' plus optional explicit 'dependsOn'. */
public record WorkflowNode(String id, String agent, Set<String> dependsOn, Set<String> consumes, List<String> produces,
                           Autonomy autonomy, boolean highImpact, int maxAttempts, String fallbackAgent,
                           List<Gate> entryGates, List<Gate> exitGates) {
    public WorkflowNode {
        dependsOn = Collections.unmodifiableSet(new LinkedHashSet<>(dependsOn));
        consumes = Collections.unmodifiableSet(new LinkedHashSet<>(consumes));
        produces = List.copyOf(produces);
        entryGates = List.copyOf(entryGates);
        exitGates = List.copyOf(exitGates);
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
    }

    public static Builder node(String id, String agent) { return new Builder(id, agent); }

    public static final class Builder {
        private final String id, agent;
        private final Set<String> dependsOn = new LinkedHashSet<>(), consumes = new LinkedHashSet<>();
        private final List<String> produces = new ArrayList<>();
        private final List<Gate> entry = new ArrayList<>(), exit = new ArrayList<>();
        private Autonomy autonomy = Autonomy.GUARDED;
        private boolean highImpact;
        private int maxAttempts = 3;
        private String fallback;

        private Builder(String id, String agent) { this.id = id; this.agent = agent; }
        public Builder dependsOn(String... n) { dependsOn.addAll(List.of(n)); return this; }
        public Builder consumes(String... a) { consumes.addAll(List.of(a)); return this; }
        /** Declares outputs that must be non-blank (adds a non-blank exit gate each). */
        public Builder produces(String... a) { for (String s : a) { produces.add(s); exit.add(Gates.nonBlank(s)); } return this; }
        public Builder producesMayBeBlank(String... a) { produces.addAll(List.of(a)); return this; }
        public Builder autonomy(Autonomy a) { autonomy = a; return this; }
        public Builder highImpact() { highImpact = true; return this; }
        public Builder maxAttempts(int n) { maxAttempts = n; return this; }
        public Builder fallback(String agentName) { fallback = agentName; return this; }
        public Builder entryGate(Gate g) { entry.add(g); return this; }
        public Builder exitGate(Gate g) { exit.add(g); return this; }
        public WorkflowNode build() {
            return new WorkflowNode(id, agent, dependsOn, consumes, produces, autonomy, highImpact, maxAttempts, fallback, entry, exit);
        }
    }
}
