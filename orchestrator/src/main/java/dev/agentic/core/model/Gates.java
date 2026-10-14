package dev.agentic.core.model;

/** Reusable entry/exit gates. */
public final class Gates {
    private Gates() {}

    public static Gate nonBlank(String artifact) {
        return new Gate() {
            public String name() { return "non-blank:" + artifact; }
            public GateResult check(GateContext c) {
                String v = c.outputs().get(artifact);
                return v == null || v.isBlank() ? GateResult.fail("artifact '" + artifact + "' is empty") : GateResult.pass();
            }
        };
    }

    public static Gate mustNotContain(String artifact, String token) {
        return new Gate() {
            public String name() { return "must-not-contain:" + artifact + ":" + token; }
            public GateResult check(GateContext c) {
                String v = c.outputs().getOrDefault(artifact, "");
                return v.contains(token) ? GateResult.fail("'" + artifact + "' contains blocking finding '" + token + "'") : GateResult.pass();
            }
        };
    }

    /** Pauses for a human while 'open-questions' is non-blank, for at most maxRounds rounds; then proceeds on recorded assumptions. */
    public static Gate clarification(String questionsArtifact, String answersArtifact, int maxRounds) {
        return new Gate() {
            public String name() { return "clarification"; }
            public GateResult check(GateContext c) {
                String q = c.outputs().getOrDefault(questionsArtifact, "");
                if (q.isBlank()) return GateResult.pass();
                int rounds = c.run().version(answersArtifact) - 1;   // v1 is the empty seed
                return rounds >= maxRounds ? GateResult.pass() : GateResult.needsInput(q);
            }
        };
    }
}
