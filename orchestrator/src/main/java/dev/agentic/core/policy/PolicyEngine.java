package dev.agentic.core.policy;

import dev.agentic.core.model.ArtifactDraft;
import dev.agentic.core.model.WorkflowNode;
import java.util.List;

/** Evaluates all guardrails; returns only non-ALLOW findings. DENY beats REQUIRE_APPROVAL. */
public final class PolicyEngine {
    private final List<Policy> policies;
    public PolicyEngine(List<Policy> policies) { this.policies = List.copyOf(policies); }
    public static PolicyEngine defaults() { return new PolicyEngine(Policies.defaults()); }

    public List<PolicyDecision> evaluate(WorkflowNode node, List<ArtifactDraft> drafts) {
        return policies.stream().flatMap(p -> p.evaluate(node, drafts).stream())
            .filter(d -> d.verdict() != PolicyDecision.Verdict.ALLOW).toList();
    }
}
