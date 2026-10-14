package dev.agentic.core.workflow;

import dev.agentic.core.agent.*;
import dev.agentic.core.model.*;
import dev.agentic.core.validation.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import static dev.agentic.core.model.WorkflowNode.node;

/** Workflow catalogue and the agents that serve it. */
public final class Workflows {
    private Workflows() {}
    private static final String FILES = " Emit code as blocks introduced by a line '=== FILE: relative/path ==='. Never include credentials.";

    /** Offline default: validation is SIMULATED and reported as such. */
    public static AgentRegistry agents(LlmGateway g) {
        return agents(g, new BuildValidationAgent(new SimulatedCommandRunner(), null, AllowlistedCommandRunner.mavenDefaults(), Duration.ofMinutes(5)));
    }

    /** Real validation: baseline project + generated files, engine-owned allowlisted commands. */
    public static AgentRegistry agents(LlmGateway g, Agent buildValidator) {
        return new AgentRegistry()
            .register(buildValidator)
            .register(new RequirementsAgent(g))
            .register(new PromptAgent("architect", "You are a software architect. Produce components, API contract, schema, key decisions and trade-offs for the requirements.", "architecture", g))
            .register(new PromptAgent("test-planner", "You are a QA lead. Produce a test plan covering unit, integration and failure scenarios.", "test-plan", g))
            .register(new PromptAgent("impact-analyst", "You analyse an existing codebase. Identify impacted modules, APIs, data flows and risks.", "impact-analysis", g))
            .register(new PromptAgent("migration-author", "You write additive, reversible database migrations." + FILES, "schema-migration", g))
            .register(new PromptAgent("implementer", "You are a senior Java/Spring Boot engineer. Implement the design with clean, testable code." + FILES, "implementation", g))
            .register(new PromptAgent("unit-test-writer", "Write JUnit 5 unit tests per the test plan." + FILES, "unit-tests", g))
            .register(new PromptAgent("integration-test-writer", "Write integration tests per the test plan." + FILES, "integration-tests", g))
            .register(new PromptAgent("regression-test-writer", "Write regression tests protecting existing behaviour listed in the impact analysis." + FILES, "regression-tests", g))
            .register(new PromptAgent("docs-writer", "Write concise service documentation: API summary, configuration, runbook.", "docs", g))
            .register(new PromptAgent("security-reviewer", "Review the implementation for security issues. For any issue that must stop release write a line starting with 'BLOCKER:'; otherwise write none.", "security-review", g))
            .register(new PromptAgent("release-manager", "Assess release readiness: checklist, rollback plan, GO/NO-GO.", "release-notes", g));
    }

    /** New system: requirements -> (architecture || test-plan) -> implementation -> (4 parallel branches) -> release. */
    public static WorkflowDefinition greenfield() {
        return new WorkflowDefinition("greenfield", List.of(
            requirements(),
            node("architecture", "architect").consumes("requirements").produces("architecture").autonomy(Autonomy.SUPERVISED).build(),
            node("test-plan", "test-planner").consumes("requirements").produces("test-plan").autonomy(Autonomy.AUTONOMOUS).build(),
            node("implementation", "implementer").consumes("architecture", "test-plan").produces("implementation").build(),
            node("unit-tests", "unit-test-writer").consumes("implementation", "test-plan").produces("unit-tests").autonomy(Autonomy.AUTONOMOUS).build(),
            node("integration-tests", "integration-test-writer").consumes("implementation", "test-plan").produces("integration-tests").autonomy(Autonomy.AUTONOMOUS).build(),
            node("docs", "docs-writer").consumes("architecture", "implementation").produces("docs").autonomy(Autonomy.AUTONOMOUS).build(),
            validation("unit-tests", "integration-tests"),
            security(),
            release("unit-tests", "integration-tests")
        ), Set.of("requirement", "clarifications"));
    }

    /** Existing system: adds codebase reconnaissance, impact analysis, a high-impact migration and regression tests. */
    public static WorkflowDefinition brownfield() {
        return new WorkflowDefinition("brownfield", List.of(
            requirements(),
            node("impact-analysis", "impact-analyst").consumes("requirements", "codebase-snapshot").produces("impact-analysis").autonomy(Autonomy.AUTONOMOUS).build(),
            node("architecture", "architect").consumes("requirements", "impact-analysis").produces("architecture").autonomy(Autonomy.SUPERVISED).build(),
            node("test-plan", "test-planner").consumes("requirements", "impact-analysis").produces("test-plan").autonomy(Autonomy.AUTONOMOUS).build(),
            node("schema-migration", "migration-author").consumes("architecture", "impact-analysis").produces("schema-migration").highImpact().build(),
            node("implementation", "implementer").consumes("architecture", "schema-migration", "test-plan").produces("implementation").build(),
            node("regression-tests", "regression-test-writer").consumes("implementation", "impact-analysis").produces("regression-tests").autonomy(Autonomy.AUTONOMOUS).build(),
            node("unit-tests", "unit-test-writer").consumes("implementation", "test-plan").produces("unit-tests").autonomy(Autonomy.AUTONOMOUS).build(),
            node("docs", "docs-writer").consumes("architecture", "implementation").produces("docs").autonomy(Autonomy.AUTONOMOUS).build(),
            validation("unit-tests", "regression-tests"),
            security(),
            release("unit-tests", "regression-tests")
        ), Set.of("requirement", "clarifications", "codebase-snapshot"));
    }

    private static WorkflowNode requirements() {
        return node("requirements", "requirements-analyst").consumes("requirement", "clarifications")
            .produces("requirements").producesMayBeBlank("open-questions")
            .exitGate(Gates.clarification("open-questions", "clarifications", 2)).autonomy(Autonomy.AUTONOMOUS).build();
    }
    /** Deterministic quality gate: generated code + tests must build and pass. One attempt: a failing build is escalated, not blindly retried. */
    private static WorkflowNode validation(String... tests) {
        String[] in = new String[tests.length + 1];
        in[0] = "implementation";
        System.arraycopy(tests, 0, in, 1, tests.length);
        return node("build-validation", "build-validator").consumes(in).produces("validation-report")
            .exitGate(Gates.mustNotContain("validation-report", BuildValidationAgent.FAIL))
            .autonomy(Autonomy.AUTONOMOUS).maxAttempts(1).build();
    }
    private static WorkflowNode security() {
        return node("security-review", "security-reviewer").consumes("implementation").produces("security-review")
            .exitGate(Gates.mustNotContain("security-review", "BLOCKER:")).autonomy(Autonomy.AUTONOMOUS).build();
    }
    private static WorkflowNode release(String... tests) {
        String[] in = new String[tests.length + 3];
        System.arraycopy(tests, 0, in, 0, tests.length);
        in[tests.length] = "docs"; in[tests.length + 1] = "security-review"; in[tests.length + 2] = "validation-report";
        return node("release-readiness", "release-manager").consumes(in).produces("release-notes").autonomy(Autonomy.SUPERVISED).build();
    }
}
