package dev.agentic.api;

import dev.agentic.core.model.*;

import java.util.List;

record RunView(String id, String workflow, RunStatus status, String reason, List<NodeView> nodes, List<ArtifactView> artifacts) {
    record NodeView(String id, NodeStatus status, int attempts, List<String> awaitingReasons, String lastError) {}
    record ArtifactView(String ref, String producedBy, String sha256) {}

    static RunView of(WorkflowRun r) {
        List<NodeView> nodes = r.definition().nodes().stream().map(n -> {
            NodeState s = r.state(n.id());
            return new NodeView(n.id(), s.status, s.attempts, s.stagedReasons, s.lastError);
        }).toList();
        List<ArtifactView> arts = r.activeArtifacts().stream()
            .map(a -> new ArtifactView(a.ref(), a.producedBy(), a.sha256().substring(0, 12))).toList();
        return new RunView(r.id(), r.definition().name(), r.status(), r.statusReason(), nodes, arts);
    }
}
