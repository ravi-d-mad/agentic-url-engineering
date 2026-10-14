package dev.agentic.core.model;

/** Uncommitted agent output. Becomes an Artifact only after policies, gates and (if needed) approval. */
public record ArtifactDraft(String name, String content) {}
