package dev.agentic.core.model;

import java.time.Instant;
import java.util.Map;

/** Snapshot of artifact head pointers and node statuses. Artifact history is immutable, so restore is O(nodes). */
public record Checkpoint(String id, String reason, Instant at, Map<String, Integer> heads, Map<String, NodeStatus> statuses) {}
