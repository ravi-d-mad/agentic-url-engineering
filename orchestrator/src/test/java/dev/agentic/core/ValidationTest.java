package dev.agentic.core;

import dev.agentic.core.agent.MockLlmGateway;
import dev.agentic.core.engine.EngineConfig;
import dev.agentic.core.engine.OrchestratorEngine;
import dev.agentic.core.events.InMemoryEventStore;
import dev.agentic.core.model.*;
import dev.agentic.core.observability.FinalSummary;
import dev.agentic.core.policy.PolicyEngine;
import dev.agentic.core.validation.*;
import dev.agentic.core.workflow.Workflows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    static OrchestratorEngine engine(boolean buildPasses, InMemoryEventStore ev) {
        var validator = new BuildValidationAgent(new SimulatedCommandRunner(buildPasses), null, AllowlistedCommandRunner.mavenDefaults(), Duration.ofSeconds(5));
        return new OrchestratorEngine(Workflows.agents(new MockLlmGateway(), validator), PolicyEngine.defaults(), ev, EngineConfig.fast());
    }

    @Test
    void failingBuildStopsTheRunBeforeRelease() {
        var ev = new InMemoryEventStore();
        var e = engine(false, ev);
        var run = e.start(Workflows.greenfield(), Map.of("requirement", "Build a URL shortener with expiry."));
        EngineTest.drive(e, run);
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals(NodeStatus.FAILED, run.state("build-validation").status);
        assertTrue(run.latest("release-notes").isEmpty(), "release must not be produced on a failed build");
    }

    @Test
    void passingBuildIsReportedAsSimulatedAndSummarised() {
        var ev = new InMemoryEventStore();
        var e = engine(true, ev);
        var run = e.start(Workflows.greenfield(), Map.of("requirement", "Build a URL shortener with expiry."));
        EngineTest.drive(e, run);
        assertEquals(RunStatus.COMPLETED, run.status());
        String report = run.latest("validation-report").orElseThrow().content();
        assertTrue(report.contains("SIMULATED"), "simulated validation must never be presented as real");
        String summary = FinalSummary.generate(run, ev.events(run.id()));
        assertTrue(summary.contains("## 6. Risks, trade-offs and limitations"));
        assertTrue(summary.contains("SIMULATED"));
    }

    @Test
    void runnerRefusesCommandsOutsideTheAllowlist(@TempDir Path dir) {
        var runner = new AllowlistedCommandRunner(List.of(List.of("java", "-version")));
        assertThrows(SecurityException.class, () -> runner.run(dir, List.of("rm", "-rf", "/"), Duration.ofSeconds(5)));
        assertThrows(SecurityException.class, () -> runner.run(dir, List.of("sh", "-c", "echo hi"), Duration.ofSeconds(5)));
    }

    @Test
    void runnerExecutesAllowlistedCommandWithScrubbedEnvironment(@TempDir Path dir) {
        var runner = new AllowlistedCommandRunner(List.of(List.of("java", "-version")));
        var res = runner.run(dir, List.of("java", "-version"), Duration.ofSeconds(30));
        assertFalse(res.simulated());
        assertTrue(res.passed(), res.output());
    }
}
