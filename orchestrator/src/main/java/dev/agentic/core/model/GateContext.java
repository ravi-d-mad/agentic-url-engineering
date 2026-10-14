package dev.agentic.core.model;

import java.util.Map;

/** outputs = candidate (uncommitted) output by artifact name; empty for entry gates. */
public record GateContext(WorkflowRun run, WorkflowNode node, Map<String, String> outputs) {}
