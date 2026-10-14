package dev.agentic.core.observability;

import dev.agentic.core.events.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Reliability metrics derived purely from the audit log, so they are always consistent with it. */
public record MetricsReport(String runId, int nodeSuccesses, int nodeFailures, int retries, int fallbacks, int rollbacks,
                            int replans, int policyDenials, int gateFailures, int approvalsRequested, int approvalsGranted,
                            int rejections, double nodeSuccessRate, double mttrMillis, long endToEndMillis,
                            long humanWaitMillis, long agentActiveMillis) {

    public static MetricsReport from(String runId, List<RunEvent> evs) {
        Map<EventType, Integer> c = new EnumMap<>(EventType.class);
        evs.forEach(e -> c.merge(e.type(), 1, Integer::sum));
        Map<String, Instant> firstFailure = new HashMap<>(), approvalOpen = new HashMap<>(), inputOpen = new HashMap<>();
        List<Long> recoveries = new ArrayList<>();
        long human = 0;
        for (RunEvent e : evs) {
            switch (e.type()) {
                case NODE_RETRY -> firstFailure.putIfAbsent(e.nodeId(), e.ts());
                case NODE_SUCCEEDED, APPROVAL_REQUESTED, INPUT_REQUESTED -> {
                    Instant f = firstFailure.remove(e.nodeId());
                    if (f != null) recoveries.add(Duration.between(f, e.ts()).toMillis());
                    if (e.type() == EventType.APPROVAL_REQUESTED) approvalOpen.put(e.nodeId(), e.ts());
                    if (e.type() == EventType.INPUT_REQUESTED) inputOpen.put(e.nodeId(), e.ts());
                }
                case NODE_FAILED -> firstFailure.remove(e.nodeId());
                case APPROVED, REJECTED -> {
                    Instant o = approvalOpen.remove(e.nodeId());
                    if (o != null) human += Duration.between(o, e.ts()).toMillis();
                }
                case INPUT_PROVIDED -> {
                    for (Instant o : inputOpen.values()) human += Duration.between(o, e.ts()).toMillis();
                    inputOpen.clear();
                }
                default -> {}
            }
        }
        int s = c.getOrDefault(EventType.NODE_SUCCEEDED, 0), f = c.getOrDefault(EventType.NODE_FAILED, 0);
        long e2e = evs.isEmpty() ? 0 : Duration.between(evs.get(0).ts(), evs.get(evs.size() - 1).ts()).toMillis();
        return new MetricsReport(runId, s, f, c.getOrDefault(EventType.NODE_RETRY, 0), c.getOrDefault(EventType.FALLBACK_USED, 0),
            c.getOrDefault(EventType.ROLLBACK, 0), c.getOrDefault(EventType.REPLAN, 0), c.getOrDefault(EventType.POLICY_DENIED, 0),
            c.getOrDefault(EventType.GATE_FAILED, 0), c.getOrDefault(EventType.APPROVAL_REQUESTED, 0), c.getOrDefault(EventType.APPROVED, 0),
            c.getOrDefault(EventType.REJECTED, 0), s + f == 0 ? 1.0 : (double) s / (s + f),
            recoveries.stream().mapToLong(Long::longValue).average().orElse(0), e2e, human, Math.max(0, e2e - human));
    }

    public String toMarkdown() {
        return """
            # Run report: %s
            | Metric | Value |
            |---|---|
            | Node success rate | %.0f%% (%d ok / %d failed) |
            | Retries / fallbacks / rollbacks | %d / %d / %d |
            | Re-plans | %d |
            | Policy denials / gate failures | %d / %d |
            | Approvals requested / granted / rejected | %d / %d / %d |
            | MTTR (failed attempt -> valid output) | %.0f ms |
            | End-to-end latency | %d ms (human wait %d ms, agent-active %d ms) |
            """.formatted(runId, nodeSuccessRate * 100, nodeSuccesses, nodeFailures, retries, fallbacks, rollbacks, replans,
            policyDenials, gateFailures, approvalsRequested, approvalsGranted, rejections, mttrMillis,
            endToEndMillis, humanWaitMillis, agentActiveMillis);
    }
}
