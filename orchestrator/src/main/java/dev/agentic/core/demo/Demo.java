package dev.agentic.core.demo;

import dev.agentic.core.agent.*;
import dev.agentic.core.engine.*;
import dev.agentic.core.events.*;
import dev.agentic.core.model.*;
import dev.agentic.core.observability.*;
import dev.agentic.core.policy.PolicyEngine;
import dev.agentic.core.workflow.*;

import java.nio.file.*;
import java.util.*;

/**
 * Offline end-to-end demo (no Spring, no API key). Usage:
 *   java dev.agentic.core.demo.Demo [greenfield|brownfield|ambiguous|replan|chaos|all] [outDir] [baselineDir]
 */
public final class Demo {
    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "all";
        Path out = Path.of(args.length > 1 ? args[1] : "runs");
        Path baseline = args.length > 2 ? Path.of(args[2]) : null;
        switch (which) {
            case "greenfield" -> greenfield(out);
            case "brownfield" -> brownfield(out, baseline);
            case "ambiguous" -> ambiguous(out);
            case "replan" -> replan(out);
            case "chaos" -> chaos(out);
            default -> { greenfield(out); brownfield(out, baseline); ambiguous(out); replan(out); chaos(out); }
        }
    }

    static final String GREENFIELD_REQ = "Build a URL shortener: create short links (optional expiry), redirect by code, "
        + "per-link click stats, and basic reliability (validation, rate limiting, clear error codes).";

    static void greenfield(Path out) throws Exception {
        banner("GREENFIELD");
        Ctx c = new Ctx(out, false);
        WorkflowRun run = c.engine.start(Workflows.greenfield(), Map.of("requirement", GREENFIELD_REQ));
        c.drive(run, new Human());
        c.finish(run);
    }

    static void brownfield(Path out, Path baseline) throws Exception {
        banner("BROWNFIELD");
        if (baseline == null && Files.isDirectory(Path.of("shortener-service/src/main/java"))) baseline = Path.of("shortener-service/src/main/java");
        String snapshot = baseline != null ? CodebaseScanner.scan(baseline)
            : "# Codebase snapshot (sample)\n## LinkController.java\n- class LinkController\n- endpoint @PostMapping(\"/api/links\")\n- endpoint @GetMapping(\"/{code}\")\n## Link.java\n- JPA entity\n";
        Ctx c = new Ctx(out, false);
        WorkflowRun run = c.engine.start(Workflows.brownfield(), Map.of(
            "requirement", "Add per-IP rate limiting on link creation and a clicks-per-day analytics breakdown to the existing shortener without changing the redirect contract.",
            "codebase-snapshot", snapshot));
        c.drive(run, new Human());
        c.finish(run);
    }

    static void ambiguous(Path out) throws Exception {
        banner("AMBIGUOUS");
        Ctx c = new Ctx(out, false);
        WorkflowRun run = c.engine.start(Workflows.greenfield(), Map.of("requirement", "Make links safer and the service scale better."));
        Human h = new Human();
        h.answers = "Safer = block malicious URLs (scheme allowlist + denylist) and rate-limit creation. "
            + "Scale = 1k redirects/s, 100:1 read/write, single region, cache hot codes.";
        c.drive(run, h);
        c.finish(run);
    }

    static void replan(Path out) throws Exception {
        banner("RE-PLAN (requirements change after completion)");
        Ctx c = new Ctx(out, false);
        WorkflowRun run = c.engine.start(Workflows.greenfield(), Map.of("requirement", GREENFIELD_REQ));
        c.drive(run, new Human());
        System.out.println("\n>>> Human edits 'requirements' after completion: adds a 90-day default expiry rule");
        String edited = run.latest("requirements").orElseThrow().content() + "\n- NEW: links expire after 90 days unless overridden\n";
        c.engine.submitArtifact(run.id(), "requirements", edited, "pm");
        c.drive(run, new Human());
        c.finish(run);
    }

    static void chaos(Path out) throws Exception {
        banner("CHAOS (retries, guardrail denial, rejection with feedback, fallback-free recovery)");
        Ctx c = new Ctx(out, true);
        WorkflowRun run = c.engine.start(Workflows.greenfield(), Map.of("requirement", GREENFIELD_REQ));
        Human h = new Human();
        h.rejectOnce.add("architecture");
        c.drive(run, h);
        c.finish(run);
    }

    // ------------------------------------------------------------------

    static final class Human {
        String answers = "No further constraints.";
        final Set<String> rejectOnce = new HashSet<>();
    }

    static final class Ctx {
        final EventStore events;
        final OrchestratorEngine engine;
        final Path out;

        Ctx(Path out, boolean chaos) {
            this.out = out;
            this.events = new JsonlEventStore(out);
            AgentRegistry agents = Workflows.agents(new MockLlmGateway());
            if (chaos) {
                agents.decorate("architect", FlakyAgent.failingFirst(2));                                   // transient failures -> retries
                agents.decorate("implementer", FlakyAgent.poisoningFirst(1, "api_key = \"sk-live-1234567890abcdef\""));  // leaks a secret -> DENY -> retry
            }
            this.engine = new OrchestratorEngine(agents, PolicyEngine.defaults(), events, EngineConfig.fast());
            events.addListener(e -> {
                switch (e.type()) {
                    case NODE_RETRY, FALLBACK_USED, POLICY_DENIED, GATE_FAILED, NODE_FAILED, APPROVAL_REQUESTED, APPROVED, REJECTED,
                         INPUT_REQUESTED, INPUT_PROVIDED, REPLAN, ROLLBACK, RUN_COMPLETED, RUN_PAUSED, RUN_FAILED, RUN_HALTED, WAVE_STARTED ->
                        System.out.printf("  %-20s %-18s %s%n", e.type(), e.nodeId(), e.data().values().stream().findFirst().map(Demo::shorten).orElse(""));
                    default -> {}
                }
            });
        }

        void drive(WorkflowRun run, Human human) {
            engine.advance(run.id());
            for (int guard = 0; guard < 30 && run.status() == RunStatus.PAUSED; guard++) {
                boolean acted = false;
                for (WorkflowNode n : run.definition().nodes()) {
                    NodeStatus st = run.state(n.id()).status;
                    if (st == NodeStatus.AWAITING_APPROVAL) {
                        if (human.rejectOnce.remove(n.id())) engine.reject(run.id(), n.id(), "reviewer", "Add an explicit rate-limiting section");
                        else engine.approve(run.id(), n.id(), "reviewer", "LGTM");
                        acted = true;
                    } else if (st == NodeStatus.AWAITING_INPUT) {
                        engine.submitArtifact(run.id(), "clarifications", human.answers, "product-owner");
                        acted = true;
                    }
                }
                if (!acted) break;
                engine.advance(run.id());
            }
        }

        void finish(WorkflowRun run) throws Exception {
            Path dir = out.resolve(run.id());
            Files.createDirectories(dir);
            MetricsReport m = MetricsReport.from(run.id(), events.events(run.id()));
            Files.writeString(dir.resolve("report.md"), m.toMarkdown());
            Files.writeString(dir.resolve("SUMMARY.md"), FinalSummary.generate(run, events.events(run.id())));
            Files.writeString(dir.resolve("lineage.mmd"), LineageExporter.mermaid(run));
            Files.writeString(dir.resolve("workflow.mmd"), run.definition().toMermaid());
            int files = WorkspaceWriter.write(dir.resolve("workspace"), run.activeArtifacts());
            for (Artifact a : run.activeArtifacts()) Files.writeString(dir.resolve("artifact-" + a.name() + ".md"), a.content());
            System.out.println("\n  FINAL STATUS: " + run.status() + "  nodes=" + run.statuses());
            System.out.println("  generated " + files + " source files -> " + dir.resolve("workspace"));
            System.out.println(m.toMarkdown());
        }
    }

    static String shorten(String s) { return s.length() > 90 ? s.substring(0, 87) + "..." : s; }
    static void banner(String s) { System.out.println("\n==================== " + s + " ===================="); }
}
