package dev.agentic.core.model;

/**
 * Autonomy boundary of a node. Policy-flagged high-impact output ALWAYS needs a human, whatever the level.
 * SUPERVISED: every output waits for approval. GUARDED: approval when node is high-impact or policy flags it.
 * AUTONOMOUS: approval only when a policy flags it.
 */
public enum Autonomy { SUPERVISED, GUARDED, AUTONOMOUS }
