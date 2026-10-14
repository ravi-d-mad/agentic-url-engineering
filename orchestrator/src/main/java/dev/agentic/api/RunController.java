package dev.agentic.api;

import dev.agentic.core.engine.OrchestratorEngine;
import dev.agentic.core.events.RunEvent;
import dev.agentic.core.model.*;
import dev.agentic.core.observability.LineageExporter;
import dev.agentic.core.observability.MetricsReport;
import dev.agentic.core.workflow.CodebaseScanner;
import dev.agentic.core.workflow.Workflows;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Human-facing control plane: start runs, approve/reject, answer clarifications, edit artifacts, halt/resume, audit. */
@RestController
@RequestMapping("/api/runs")
class RunController {
    record StartRequest(String scenario, String requirement, String baselinePath) {}
    record DecisionRequest(String decision, String by, String comment) {}
    record ArtifactRequest(String content, String by) {}
    record HaltRequest(String reason) {}
    record ResumeRequest(Integer extraAttempts) {}

    private final OrchestratorEngine engine;
    RunController(OrchestratorEngine engine) { this.engine = engine; }

    @PostMapping
    RunView start(@RequestBody StartRequest req) throws IOException {
        Map<String, String> inputs = new HashMap<>(Map.of("requirement", Objects.toString(req.requirement(), "")));
        WorkflowDefinition def;
        switch (Objects.toString(req.scenario(), "greenfield")) {
            case "greenfield", "ambiguous" -> def = Workflows.greenfield();
            case "brownfield" -> {
                def = Workflows.brownfield();
                if (req.baselinePath() == null) throw new IllegalArgumentException("brownfield requires baselinePath");
                inputs.put("codebase-snapshot", CodebaseScanner.scan(Path.of(req.baselinePath())));
            }
            default -> throw new IllegalArgumentException("unknown scenario " + req.scenario());
        }
        WorkflowRun run = engine.start(def, inputs);
        engine.advance(run.id());   // synchronous for the prototype; see README limitations
        return RunView.of(run);
    }


    @GetMapping("/{id}") RunView get(@PathVariable String id) { return RunView.of(engine.getRun(id)); }

    @PostMapping("/{id}/approvals/{node}")
    RunView decide(@PathVariable String id, @PathVariable String node, @RequestBody DecisionRequest d) {
        if ("APPROVE".equalsIgnoreCase(d.decision())) engine.approve(id, node, d.by(), d.comment());
        else if ("REJECT".equalsIgnoreCase(d.decision())) engine.reject(id, node, d.by(), d.comment());
        else throw new IllegalArgumentException("decision must be APPROVE or REJECT");
        return RunView.of(engine.advance(id));
    }

    /** Answer clarifications (artifact 'clarifications') or edit any upstream artifact; downstream work is re-planned. */
    @PostMapping("/{id}/artifacts/{name}")
    RunView submit(@PathVariable String id, @PathVariable String name, @RequestBody ArtifactRequest a) {
        engine.submitArtifact(id, name, a.content(), a.by());
        return RunView.of(engine.advance(id));
    }

    @PostMapping("/{id}/halt") RunView halt(@PathVariable String id, @RequestBody HaltRequest h) { return RunView.of(engine.halt(id, h.reason())); }

    @PostMapping("/{id}/resume")
    RunView resume(@PathVariable String id, @RequestBody ResumeRequest r) {
        return RunView.of(engine.resume(id, r.extraAttempts() == null ? 25 : r.extraAttempts()));
    }

    @GetMapping("/{id}/events") List<RunEvent> events(@PathVariable String id) { return engine.events(id); }
    @GetMapping("/{id}/report") MetricsReport report(@PathVariable String id) { return MetricsReport.from(id, engine.events(id)); }
    @GetMapping(value = "/{id}/summary", produces = MediaType.TEXT_PLAIN_VALUE)
    String summary(@PathVariable String id) { return dev.agentic.core.observability.FinalSummary.generate(engine.getRun(id), engine.events(id)); }
    @GetMapping(value = "/{id}/lineage", produces = MediaType.TEXT_PLAIN_VALUE)
    String lineage(@PathVariable String id) { return LineageExporter.mermaid(engine.getRun(id)); }
    @GetMapping(value = "/{id}/artifacts/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    String artifact(@PathVariable String id, @PathVariable String name) {
        return engine.getRun(id).latest(name).orElseThrow(() -> new NoSuchElementException("no artifact " + name)).content();
    }
}
