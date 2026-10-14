package dev.agentic.core.agent;

import dev.agentic.core.model.Artifact;
import java.util.List;
import java.util.Map;

/** inputs = latest consumed artifacts; feedback = earlier failures/rejections to self-correct against. */
public record AgentRequest(String runId, String nodeId, int attempt, Map<String, Artifact> inputs, List<String> feedback) {}
