package dev.agentic.core;

import dev.agentic.core.agent.*;
import dev.agentic.core.engine.*;
import dev.agentic.core.events.*;
import dev.agentic.core.model.*;
import dev.agentic.core.observability.MetricsReport;
import dev.agentic.core.policy.PolicyEngine;
import dev.agentic.core.workflow.Workflows;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    static final String REQ = "Build a URL shortener with expiry and click stats.";

    record Fixture(OrchestratorEngine engine, InMemoryEventStore events, AgentRegistry agents) {}

    static Fixture fixture(EngineConfig cfg) {
        InMemoryEventStore ev = new InMemoryEventStore();
        AgentRegistry agents = Workflows.agents(new MockLlmGateway());
        return new Fixture(new OrchestratorEngine(agents, PolicyEngine.defaults(), ev, cfg), ev, agents);
    }

    /** Simulated human: approves everything and answers clarifications. */
    static void drive(OrchestratorEngine e, WorkflowRun run) {
        e.advance(run.id());
        for (int i = 0; i < 30 && run.status() == RunStatus.PAUSED; i++) {
            boolean acted = false;
            for (WorkflowNode n : run.definition().nodes()) {
                NodeStatus st = run.state(n.id()).status;
                if (st == NodeStatus.AWAITING_APPROVAL) { e.approve(run.id(), n.id(), "t", "ok"); acted = true; }
                if (st == NodeStatus.AWAITING_INPUT) { e.submitArtifact(run.id(), "clarifications", "Block malware; 1k rps.", "t"); acted = true; }
            }
            if (!acted) break;
            e.advance(run.id());
        }
    }

    static int count(Fixture f, WorkflowRun r, EventType t, String node) {
        return (int) f.events().events(r.id()).stream().filter(e -> e.type() == t && e.nodeId().equals(node)).count();
    }

    @Test
    void cyclicWorkflowIsRejected() {
        var a = WorkflowNode.node("a", "x").consumes("q").produces("p").build();
        var b = WorkflowNode.node("b", "x").consumes("p").produces("q").build();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition("cyc", List.of(a, b), java.util.Set.of()));
    }

    @Test
    void greenfieldPausesForDesignApprovalThenCompletes() {
        var f = fixture(EngineConfig.fast());
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", REQ));
        f.engine().advance(run.id());
        assertEquals(RunStatus.PAUSED, run.status());
        assertEquals(NodeStatus.AWAITING_APPROVAL, run.state("architecture").status);
        assertEquals(NodeStatus.PENDING, run.state("implementation").status);   // gated behind the human checkpoint
        drive(f.engine(), run);
        assertEquals(RunStatus.COMPLETED, run.status());
    }

    @Test
    void ambiguousRequirementBlocksUntilClarified() {
        var f = fixture(EngineConfig.fast());
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", "Make links safer and scale better."));
        f.engine().advance(run.id());
        assertEquals(NodeStatus.AWAITING_INPUT, run.state("requirements").status);
        assertFalse(run.latest("open-questions").orElseThrow().content().isBlank());
        assertEquals(NodeStatus.PENDING, run.state("architecture").status);
        drive(f.engine(), run);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertTrue(run.latest("open-questions").orElseThrow().content().isBlank());
    }

    @Test
    void transientFailuresAreRetriedWithinBound() {
        var f = fixture(EngineConfig.fast());
        f.agents().decorate("architect", FlakyAgent.failingFirst(2));
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", REQ));
        drive(f.engine(), run);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(2, MetricsReport.from(run.id(), f.events().events(run.id())).retries());
    }

    @Test
    void leakedSecretIsDeniedAndNeverCommitted() {
        var f = fixture(EngineConfig.fast());
        f.agents().decorate("implementer", FlakyAgent.poisoningFirst(1, "password = \"hunter2hunter2\""));
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", REQ));
        drive(f.engine(), run);
        assertEquals(1, count(f, run, EventType.POLICY_DENIED, "implementation"));
        assertFalse(run.latest("implementation").orElseThrow().content().contains("hunter2"));
    }

    @Test
    void permanentFailureRollsBackWholeWave() {
        var f = fixture(EngineConfig.fast());
        f.agents().decorate("unit-test-writer", FlakyAgent::alwaysFailing);
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", REQ));
        drive(f.engine(), run);
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals(NodeStatus.FAILED, run.state("unit-tests").status);
        assertTrue(run.latest("docs").isEmpty(), "sibling output must not be committed when the wave fails");
        assertEquals(1, count(f, run, EventType.ROLLBACK, ""));
    }

    @Test
    void budgetExhaustionHaltsAndHumanCanResume() {
        var f = fixture(new EngineConfig(Duration.ofSeconds(5), 0, 2, 3, 3));
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", REQ));
        f.engine().advance(run.id());
        f.engine().approve(run.id(), "architecture", "t", "ok");
        f.engine().advance(run.id());
        assertEquals(RunStatus.HALTED, run.status());
        f.engine().resume(run.id(), 50);
        assertNotEquals(RunStatus.HALTED, run.status());
    }

    @Test
    void replanRerunsOnlyWhatChangedAndStopsAtUnchangedOutput() {
        var f = fixture(EngineConfig.fast());
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", REQ));
        drive(f.engine(), run);
        f.engine().submitArtifact(run.id(), "requirements", run.latest("requirements").orElseThrow().content() + "\n- NEW rule", "pm");
        drive(f.engine(), run);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(2, count(f, run, EventType.NODE_STARTED, "architecture"));
        assertEquals(2, count(f, run, EventType.NODE_STARTED, "implementation"));
        assertEquals(1, count(f, run, EventType.NODE_STARTED, "unit-tests"), "implementation output was unchanged: cascade must stop");
    }

    @Test
    void repeatedRejectionFailsTheRun() {
        var f = fixture(EngineConfig.fast());   // maxRejections = 2
        var run = f.engine().start(Workflows.greenfield(), Map.of("requirement", REQ));
        for (int i = 0; i < 3; i++) {
            f.engine().advance(run.id());
            f.engine().reject(run.id(), "architecture", "t", "no");
        }
        assertEquals(RunStatus.FAILED, run.status());
    }

    @Test
    void humanApprovalIsRequiredForDestructiveMigration() {
        var f = fixture(EngineConfig.fast());
        var run = f.engine().start(Workflows.brownfield(), Map.of("requirement", REQ, "codebase-snapshot", "# snapshot\n- class LinkController"));
        f.engine().advance(run.id());
        f.engine().approve(run.id(), "architecture", "t", "ok");
        f.engine().advance(run.id());
        assertEquals(NodeStatus.AWAITING_APPROVAL, run.state("schema-migration").status);
        assertTrue(run.latest("schema-migration").isEmpty(), "staged output is not committed before approval");
    }
}
