package dev.agentic.core.policy;

import dev.agentic.core.model.ArtifactDraft;
import dev.agentic.core.model.WorkflowNode;
import java.util.List;

public interface Policy {
    String name();
    List<PolicyDecision> evaluate(WorkflowNode node, List<ArtifactDraft> drafts);
}
