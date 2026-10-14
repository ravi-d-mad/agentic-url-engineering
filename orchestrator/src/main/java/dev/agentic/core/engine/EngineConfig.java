package dev.agentic.core.engine;

import java.time.Duration;

/** Safety limits. attemptBudget bounds total agent calls per run (cost/runaway guard). */
public record EngineConfig(Duration agentTimeout, long backoffBaseMillis, int maxRejections, int maxResumes, int attemptBudget) {
    public static EngineConfig defaults() { return new EngineConfig(Duration.ofSeconds(120), 500, 3, 3, 100); }
    public static EngineConfig fast() { return new EngineConfig(Duration.ofSeconds(10), 0, 2, 3, 100); }
}
