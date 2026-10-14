package dev.agentic.core.observability;

import dev.agentic.core.events.EventType;
import dev.agentic.core.events.RunEvent;
import dev.agentic.core.model.*;
import dev.agentic.core.validation.BuildValidationAgent;

import java.util.List;
import java.util.stream.Collectors;

/** Assignment item 8: the system writes its own final engineering summary from the run state and audit log (no invented numbers). */
public final class FinalSummary {
    private FinalSummary() {}

    public static String generate(WorkflowRun run, List<RunEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Final Engineering Summary: ").append(run.id()).append(" (").append(run.definition().name()).append(")\n\n");
        sb.append("**Outcome:** ").append(run.status()).append(run.statusReason().isBlank() ? "" : " (" + run.statusReason() + ")").append("\n\n");

        sb.append("## 1. Plan and rationale\n");
        run.latest("requirement").ifPresent(a -> sb.append("**Original request:** ").append(a.content().strip()).append("\n\n"));
        run.latest("clarifications").filter(a -> !a.content().isBlank())
            .ifPresent(a -> sb.append("**Human clarifications (").append(a.producedBy()).append("):** ").append(a.content().strip()).append("\n\n"));
        run.latest("requirements").ifPresent(a -> sb.append(a.content().strip().replaceFirst("^# ", "### ")).append("\n\n"));
        sb.append("**Execution graph** (entry/exit gates, parallel branches join before dependants):\n\n```mermaid\n")
          .append(run.definition().toMermaid()).append("```\n\n");

        sb.append("## 2. Artifacts and lineage\n| Artifact | Produced by | SHA-256 | Derived from |\n|---|---|---|---|\n");
        for (Artifact a : run.activeArtifacts())
            sb.append("| ").append(a.ref()).append(" | ").append(a.producedBy()).append(" | ").append(a.sha256(), 0, 10)
              .append(" | ").append(a.derivedFrom().isEmpty() ? "-" : String.join(", ", a.derivedFrom())).append(" |\n");
        sb.append("\nSuperseded versions are retained in the audit trail (").append(run.allVersions().size()).append(" versions total).\n\n");

        sb.append("## 3. Human oversight and guardrail decisions\n");
        List<RunEvent> decisions = events.stream().filter(e -> switch (e.type()) {
            case APPROVAL_REQUESTED, APPROVED, REJECTED, INPUT_REQUESTED, INPUT_PROVIDED, POLICY_DENIED, REPLAN, ROLLBACK, RUN_HALTED, RUN_RESUMED -> true;
            default -> false; }).toList();
        if (decisions.isEmpty()) sb.append("None.\n");
        for (RunEvent e : decisions)
            sb.append("- `").append(e.type()).append("` ").append(e.nodeId().isEmpty() ? "" : e.nodeId() + ": ")
              .append(e.data().values().stream().collect(Collectors.joining(" | "))).append('\n');

        sb.append("\n## 4. Validation and reliability\n");
        run.latest("validation-report").ifPresentOrElse(a -> {
            for (String line : a.content().split("\n"))
                if (line.startsWith("MODE:") || line.startsWith("RESULT:") || line.startsWith("FILES OVERLAID:")) sb.append("- ").append(line).append('\n');
        }, () -> sb.append("- Build validation did not run.\n"));
        sb.append('\n').append(MetricsReport.from(run.id(), events).toMarkdown().replaceFirst("(?s)^# [^\\n]*\\n", ""));

        sb.append("\n## 5. Assumptions\n");
        run.latest("requirements").ifPresent(a -> {
            boolean in = false;
            for (String line : a.content().split("\n")) {
                if (line.startsWith("## Assumptions")) { in = true; continue; }
                if (line.startsWith("## ")) in = false;
                if (in && !line.isBlank()) sb.append(line).append('\n');
            }
        });

        sb.append("\n## 6. Risks, trade-offs and limitations\n");
        boolean simulated = run.latest("validation-report").map(a -> a.content().contains("SIMULATED")).orElse(false);
        if (simulated) sb.append("- **Build validation was SIMULATED in this run**: generated code was not compiled or tested. Real mode runs allowlisted `mvn` commands.\n");
        sb.append("- Waves are atomic: one permanent failure discards sibling results of that wave (consistency over throughput).\n")
          .append("- Policy checks are pattern-based and can miss novel leaks; they complement, not replace, review and SAST.\n")
          .append("- A failed build escalates to a human; there is no automatic repair loop yet.\n")
          .append("- Run state is in memory; the JSONL audit log survives restarts but runs are not yet rebuilt from it.\n");
        if (events.stream().anyMatch(e -> e.type() == EventType.POLICY_DENIED))
            sb.append("- Guardrails fired during this run; see section 3 for what was blocked and regenerated.\n");
        return sb.toString();
    }
}
