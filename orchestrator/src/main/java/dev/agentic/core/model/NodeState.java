package dev.agentic.core.model;

import java.util.*;

/** Mutable per-node state. Only the engine mutates it, and only while holding the run lock. */
public final class NodeState {
    public NodeStatus status = NodeStatus.PENDING;
    public int attempts;
    public int rejections;
    public List<ArtifactDraft> staged = List.of();
    public String stagedAgent = "";
    public List<String> stagedReasons = List.of();
    public final List<String> feedback = new ArrayList<>();   // reviewer rejections, fed back into the next attempt
    public String lastError = "";
    public String decidedBy = "";
}
