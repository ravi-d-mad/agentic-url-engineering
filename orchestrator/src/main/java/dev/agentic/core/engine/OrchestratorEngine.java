package dev.agentic.core.engine;

import dev.agentic.core.agent.*;
import dev.agentic.core.events.*;
import dev.agentic.core.model.*;
import dev.agentic.core.policy.*;
import dev.agentic.core.policy.PolicyDecision.Verdict;

import java.util.*;
import java.util.concurrent.*;

import static dev.agentic.core.events.EventStore.data;
import static dev.agentic.core.events.EventType.*;

/**
 * Stateful DAG executor with governance.
 * - Execution proceeds in waves: all ready nodes run in parallel, then synchronize (join).
 * - A wave is atomic: if any node fails permanently, no output of the wave is committed (rollback to the pre-wave checkpoint).
 * - Outputs are committed only after policies + exit gates (+ human approval when required).
 * - Committing a CHANGED version of an artifact invalidates its consumers (re-plan); unchanged output stops the cascade.
 */
public final class OrchestratorEngine {
    private enum Kind { SUCCESS, NEEDS_APPROVAL, NEEDS_INPUT, FAILED }
    private record Attempt(List<ArtifactDraft> drafts, List<String> approvalReasons, String question) {}
    private record NodeResult(Kind kind, List<ArtifactDraft> drafts, String agent, List<String> reasons, int attempts, String error) {}

    private final AgentRegistry agents;
    private final PolicyEngine policies;
    private final EventStore events;
    private final EngineConfig cfg;
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
    // Separate pools avoid nested-task starvation: wave tasks invoke agents and wait for their results.
    private final ExecutorService pool = Executors.newFixedThreadPool(
        Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors())), daemonFactory("agentic-wave-"));
    private final ExecutorService agentPool = Executors.newFixedThreadPool(
        Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors())), daemonFactory("agentic-agent-"));

    public OrchestratorEngine(AgentRegistry agents, PolicyEngine policies, EventStore events, EngineConfig cfg) {
        this.agents = agents; this.policies = policies; this.events = events; this.cfg = cfg;
    }

    public WorkflowRun getRun(String id) {
        WorkflowRun r = runs.get(id);
        if (r == null) throw new NoSuchElementException("unknown run " + id);
        return r;
    }
    public Collection<WorkflowRun> runs() { return runs.values(); }
    public List<RunEvent> events(String runId) { return events.events(runId); }

    // ------------------------------------------------------------------ lifecycle

    public WorkflowRun start(WorkflowDefinition def, Map<String, String> inputs) {
        if (inputs.getOrDefault("requirement", "").isBlank()) throw new IllegalArgumentException("'requirement' is required");
        String id = "run-" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowRun run = new WorkflowRun(id, def, cfg.attemptBudget());
        runs.put(id, run);
        for (String in : def.externalInputs()) run.commit(in, inputs.getOrDefault(in, ""), "human:requester", List.of());
        run.checkpoint("run-start");
        emit(run, RUN_STARTED, "", "workflow", def.name());
        return run;
    }

    public WorkflowRun advance(String runId) {
        WorkflowRun run = getRun(runId);
        run.lock().lock();
        try {
            if (run.status().isStopped()) return run;
            run.setStatus(RunStatus.RUNNING, "");
            try {
                while (true) {
                    guard(run, false);
                    List<WorkflowNode> ready = readyNodes(run);
                    if (ready.isEmpty()) break;
                    Checkpoint cp = run.checkpoint("before-wave");
                    emit(run, WAVE_STARTED, "", "nodes", String.join(",", ready.stream().map(WorkflowNode::id).toList()));
                    Map<String, NodeResult> results;
                    try { results = executeWave(run, ready); }
                    catch (SafeStopException e) { run.restore(cp); throw e; }

                    List<String> failed = ready.stream().filter(n -> results.get(n.id()).kind() == Kind.FAILED).map(WorkflowNode::id).toList();
                    if (!failed.isEmpty()) { failWave(run, ready, results, failed, cp); return run; }
                    for (WorkflowNode n : ready) finalizeNode(run, n, results.get(n.id()));
                }
                settle(run);
            } catch (SafeStopException e) {
                run.setStatus(RunStatus.HALTED, e.getMessage());
                emit(run, RUN_HALTED, "", "reason", e.getMessage());
            }
            return run;
        } finally { run.lock().unlock(); }
    }

    // ------------------------------------------------------------------ human controls

    public WorkflowRun approve(String runId, String nodeId, String by, String comment) {
        WorkflowRun run = getRun(runId);
        run.lock().lock();
        try {
            NodeState s = run.state(nodeId);
            requireStatus(s, nodeId, NodeStatus.AWAITING_APPROVAL);
            run.checkpoint("before-approval:" + nodeId);
            commitDrafts(run, run.definition().node(nodeId), s.staged, "agent:" + s.stagedAgent + "+approved-by:" + by);
            s.staged = List.of(); s.stagedReasons = List.of();
            s.status = NodeStatus.SUCCEEDED; s.decidedBy = by; s.feedback.clear();
            emit(run, APPROVED, nodeId, "by", by, "comment", nz(comment));
            emit(run, NODE_SUCCEEDED, nodeId, "attempts", String.valueOf(s.attempts), "via", "approval");
            return run;
        } finally { run.lock().unlock(); }
    }

    public WorkflowRun reject(String runId, String nodeId, String by, String comment) {
        WorkflowRun run = getRun(runId);
        run.lock().lock();
        try {
            NodeState s = run.state(nodeId);
            requireStatus(s, nodeId, NodeStatus.AWAITING_APPROVAL);
            s.rejections++;
            s.feedback.add("Reviewer " + by + " rejected: " + nz(comment));
            s.staged = List.of(); s.stagedReasons = List.of(); s.decidedBy = by;
            emit(run, REJECTED, nodeId, "by", by, "comment", nz(comment), "rejections", String.valueOf(s.rejections));
            if (s.rejections > cfg.maxRejections()) {
                Checkpoint cp = run.lastCheckpoint().orElseThrow();
                run.restore(cp);
                s.status = NodeStatus.FAILED; s.lastError = "rejected " + s.rejections + " times";
                emit(run, NODE_FAILED, nodeId, "error", s.lastError);
                emit(run, ROLLBACK, nodeId, "checkpoint", cp.id(), "reason", "rejection limit exceeded");
                run.setStatus(RunStatus.FAILED, s.lastError);
                emit(run, RUN_FAILED, "", "reason", s.lastError);
            } else s.status = NodeStatus.STALE;   // re-run with reviewer feedback
            return run;
        } finally { run.lock().unlock(); }
    }

    /** Human supplies or edits any artifact (clarification answers, requirement change, design edit). Triggers re-plan. */
    public WorkflowRun submitArtifact(String runId, String name, String content, String by) {
        WorkflowRun run = getRun(runId);
        run.lock().lock();
        try {
            if (!run.definition().knowsArtifact(name)) throw new IllegalArgumentException("unknown artifact " + name);
            run.checkpoint("before-human-edit:" + name);
            WorkflowRun.CommitResult r = run.commit(name, content, "human:" + by, List.of());
            if (!r.changed()) { emit(run, ARTIFACT_UNCHANGED, "", "artifact", r.artifact().ref()); return run; }
            emit(run, INPUT_PROVIDED, "", "artifact", r.artifact().ref(), "by", by);
            if (r.replacedExisting()) invalidateConsumers(run, name, "human:" + by);
            if (run.status() == RunStatus.COMPLETED) run.setStatus(RunStatus.PAUSED, "reopened by edit to " + name);
            return run;
        } finally { run.lock().unlock(); }
    }

    /** Operator kill switch. Resumable. */
    public WorkflowRun halt(String runId, String reason) {
        WorkflowRun run = getRun(runId);
        run.requestHalt();
        if (run.lock().tryLock()) {   // not executing: halt immediately; otherwise the executing wave notices the flag
            try {
                if (run.status() != RunStatus.COMPLETED) {
                    run.setStatus(RunStatus.HALTED, reason);
                    emit(run, RUN_HALTED, "", "reason", reason);
                }
            } finally { run.lock().unlock(); }
        }
        return run;
    }

    /** Human-authorised retry of a FAILED/HALTED run; bounded by maxResumes. */
    public WorkflowRun resume(String runId, int extraAttempts) {
        WorkflowRun run = getRun(runId);
        run.lock().lock();
        try {
            if (run.status() != RunStatus.FAILED && run.status() != RunStatus.HALTED)
                throw new IllegalStateException("run is " + run.status() + ", nothing to resume");
            if (run.resumes() >= cfg.maxResumes()) throw new IllegalStateException("resume limit reached; escalate to a human owner");
            run.markResumed(); run.clearHalt(); run.extendBudget(extraAttempts);
            for (WorkflowNode n : run.definition().nodes()) {
                NodeState s = run.state(n.id());
                if (s.status == NodeStatus.FAILED) { s.status = NodeStatus.PENDING; s.lastError = ""; s.rejections = 0; }
            }
            run.setStatus(RunStatus.PAUSED, "resumed");
            emit(run, RUN_RESUMED, "", "extraAttempts", String.valueOf(extraAttempts));
        } finally { run.lock().unlock(); }
        return advance(runId);
    }

    // ------------------------------------------------------------------ scheduling

    private List<WorkflowNode> readyNodes(WorkflowRun run) {
        List<WorkflowNode> ready = new ArrayList<>();
        for (WorkflowNode n : run.definition().nodes()) {
            NodeState s = run.state(n.id());
            if (s.status != NodeStatus.PENDING && s.status != NodeStatus.STALE) continue;
            if (!run.definition().dependenciesOf(n.id()).stream().allMatch(d -> run.state(d).status == NodeStatus.SUCCEEDED)) continue;
            if (admit(run, n, s)) ready.add(n);
        }
        return ready;
    }

    /** Entry gates. */
    private boolean admit(WorkflowRun run, WorkflowNode n, NodeState s) {
        for (Gate g : n.entryGates()) {
            GateResult r = g.check(new GateContext(run, n, Map.of()));
            if (r.outcome() == GateResult.Outcome.FAIL) {
                s.status = NodeStatus.FAILED; s.lastError = r.reason();
                emit(run, GATE_FAILED, n.id(), "gate", g.name(), "reason", r.reason());
                emit(run, NODE_FAILED, n.id(), "error", r.reason());
                return false;
            }
            if (r.outcome() == GateResult.Outcome.NEEDS_INPUT) {
                s.status = NodeStatus.AWAITING_INPUT;
                emit(run, INPUT_REQUESTED, n.id(), "questions", r.reason());
                return false;
            }
        }
        return true;
    }

    private Map<String, NodeResult> executeWave(WorkflowRun run, List<WorkflowNode> ready) {
        Map<String, Future<NodeResult>> futures = new LinkedHashMap<>();
        for (WorkflowNode n : ready) {
            NodeState s = run.state(n.id());
            s.status = NodeStatus.RUNNING;
            Map<String, Artifact> inputs = new LinkedHashMap<>();
            for (String c : n.consumes()) run.latest(c).ifPresent(a -> inputs.put(c, a));
            List<String> feedback = List.copyOf(s.feedback);
            emit(run, NODE_STARTED, n.id(), "agent", n.agent(), "autonomy", n.autonomy().name());
            futures.put(n.id(), pool.submit(() -> runNode(run, n, inputs, feedback)));
        }
        Map<String, NodeResult> out = new LinkedHashMap<>();
        SafeStopException stop = null;
        for (var e : futures.entrySet()) {   // synchronization point: wait for every parallel branch
            try { out.put(e.getKey(), e.getValue().get()); }
            catch (ExecutionException ex) {
                if (ex.getCause() instanceof SafeStopException s) stop = s;
                else out.put(e.getKey(), new NodeResult(Kind.FAILED, List.of(), "", List.of(), 0, String.valueOf(ex.getCause())));
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new SafeStopException("interrupted"); }
        }
        if (stop != null) throw stop;
        return out;
    }

    // ------------------------------------------------------------------ node execution (runs on worker threads)

    private NodeResult runNode(WorkflowRun run, WorkflowNode node, Map<String, Artifact> inputs, List<String> prior) {
        List<String> feedback = new ArrayList<>(prior);
        String lastError = "";
        int attempts = 0;
        for (int a = 1; a <= node.maxAttempts(); a++) {
            guard(run, true);
            attempts++;
            try { return success(attempt(run, node, node.agent(), inputs, feedback, attempts), node.agent(), attempts); }
            catch (SafeStopException e) { throw e; }
            catch (RuntimeException e) {
                lastError = String.valueOf(e.getMessage());
                feedback.add("Attempt " + a + " rejected: " + lastError);
                emit(run, NODE_RETRY, node.id(), "attempt", String.valueOf(a), "error", lastError);
                backoff(a);
            }
        }
        if (node.fallbackAgent() != null) {
            guard(run, true);
            attempts++;
            emit(run, FALLBACK_USED, node.id(), "fallback", node.fallbackAgent());
            try { return success(attempt(run, node, node.fallbackAgent(), inputs, feedback, attempts), node.fallbackAgent(), attempts); }
            catch (SafeStopException e) { throw e; }
            catch (RuntimeException e) { lastError = String.valueOf(e.getMessage()); }
        }
        return new NodeResult(Kind.FAILED, List.of(), node.agent(), List.of(), attempts, lastError);
    }

    private NodeResult success(Attempt at, String agent, int attempts) {
        Kind k = at.question() != null ? Kind.NEEDS_INPUT : !at.approvalReasons().isEmpty() ? Kind.NEEDS_APPROVAL : Kind.SUCCESS;
        List<String> reasons = k == Kind.NEEDS_INPUT ? List.of(at.question()) : at.approvalReasons();
        return new NodeResult(k, at.drafts(), agent, reasons, attempts, "");
    }

    private Attempt attempt(WorkflowRun run, WorkflowNode node, String agentName, Map<String, Artifact> inputs, List<String> feedback, int n) {
        Agent agent = agents.get(agentName);
        List<ArtifactDraft> drafts = callWithTimeout(agent, new AgentRequest(run.id(), node.id(), n, inputs, List.copyOf(feedback)));

        Set<String> got = new HashSet<>();
        drafts.forEach(d -> got.add(d.name()));
        if (!got.equals(new HashSet<>(node.produces())))
            throw new AgentException("malformed output: expected artifacts " + node.produces() + " but got " + got);

        List<PolicyDecision> findings = policies.evaluate(node, drafts);
        List<String> denied = findings.stream().filter(f -> f.verdict() == Verdict.DENY).map(f -> f.policy() + ": " + f.reason()).toList();
        if (!denied.isEmpty()) {
            emit(run, POLICY_DENIED, node.id(), "violations", String.join("; ", denied));
            throw new AgentException("guardrail violation (" + String.join("; ", denied) + ")");
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        drafts.forEach(d -> outputs.put(d.name(), d.content()));
        String question = null;
        for (Gate g : node.exitGates()) {
            GateResult r = g.check(new GateContext(run, node, outputs));
            if (r.outcome() == GateResult.Outcome.FAIL) {
                emit(run, GATE_FAILED, node.id(), "gate", g.name(), "reason", r.reason());
                throw new AgentException("gate failed (" + g.name() + "): " + r.reason());
            }
            if (r.outcome() == GateResult.Outcome.NEEDS_INPUT) question = r.reason();
        }

        List<String> approval = new ArrayList<>();
        findings.stream().filter(f -> f.verdict() == Verdict.REQUIRE_APPROVAL).forEach(f -> approval.add(f.policy() + ": " + f.reason()));
        if (node.autonomy() == Autonomy.SUPERVISED) approval.add("supervised node: human sign-off required");
        if (node.autonomy() == Autonomy.GUARDED && node.highImpact()) approval.add("high-impact node");
        return new Attempt(drafts, approval, question);
    }

    private List<ArtifactDraft> callWithTimeout(Agent agent, AgentRequest req) {
        Future<List<ArtifactDraft>> f = agentPool.submit(() -> agent.execute(req));
        try { return f.get(cfg.agentTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { f.cancel(true); throw new AgentException("agent timed out after " + cfg.agentTimeout()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SafeStopException("interrupted"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new AgentException("agent error", e.getCause());
        }
    }

    private void backoff(int attempt) {
        long ms = cfg.backoffBaseMillis() * (1L << Math.min(attempt - 1, 6));
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Safe-stop: operator halt flag and per-run attempt budget. */
    private void guard(WorkflowRun run, boolean consume) {
        if (run.haltRequested()) throw new SafeStopException("operator halt requested");
        if (consume && run.consumeAttempt() > run.attemptBudget())
            throw new SafeStopException("attempt budget exhausted (" + run.attemptBudget() + ")");
    }

    // ------------------------------------------------------------------ applying results (engine thread, under lock)

    private void failWave(WorkflowRun run, List<WorkflowNode> ready, Map<String, NodeResult> results, List<String> failed, Checkpoint cp) {
        for (WorkflowNode n : ready) run.state(n.id()).attempts += results.get(n.id()).attempts();
        run.restore(cp);   // wave atomicity: nothing from this wave was committed; statuses return to pre-wave
        for (String id : failed) {
            NodeState s = run.state(id);
            s.status = NodeStatus.FAILED; s.lastError = results.get(id).error();
            emit(run, NODE_FAILED, id, "error", s.lastError, "attempts", String.valueOf(results.get(id).attempts()));
        }
        emit(run, ROLLBACK, "", "checkpoint", cp.id(), "reason", "wave atomicity, failed nodes: " + failed);
        run.setStatus(RunStatus.FAILED, "node(s) failed: " + failed);
        emit(run, RUN_FAILED, "", "reason", "node(s) failed: " + failed);
    }

    private void finalizeNode(WorkflowRun run, WorkflowNode n, NodeResult r) {
        NodeState s = run.state(n.id());
        s.attempts += r.attempts();
        switch (r.kind()) {
            case SUCCESS -> {
                commitDrafts(run, n, r.drafts(), "agent:" + r.agent());
                s.status = NodeStatus.SUCCEEDED; s.feedback.clear();
                emit(run, NODE_SUCCEEDED, n.id(), "attempts", String.valueOf(r.attempts()), "agent", r.agent());
            }
            case NEEDS_APPROVAL -> {
                s.staged = r.drafts(); s.stagedAgent = r.agent(); s.stagedReasons = r.reasons();
                s.status = NodeStatus.AWAITING_APPROVAL;
                emit(run, APPROVAL_REQUESTED, n.id(), "reasons", String.join(" | ", r.reasons()));
            }
            case NEEDS_INPUT -> {
                commitDrafts(run, n, r.drafts(), "agent:" + r.agent());   // questions are informational and must be readable
                s.status = NodeStatus.AWAITING_INPUT;
                emit(run, INPUT_REQUESTED, n.id(), "questions", r.reasons().get(0));
            }
            case FAILED -> throw new IllegalStateException("failed results are handled by failWave");
        }
    }

    private void commitDrafts(WorkflowRun run, WorkflowNode node, List<ArtifactDraft> drafts, String producedBy) {
        List<String> derived = node.consumes().stream().map(run::latest).flatMap(Optional::stream).map(Artifact::ref).toList();
        for (ArtifactDraft d : drafts) {
            WorkflowRun.CommitResult r = run.commit(d.name(), d.content(), producedBy, derived);
            if (!r.changed()) { emit(run, ARTIFACT_UNCHANGED, node.id(), "artifact", r.artifact().ref()); continue; }
            emit(run, ARTIFACT_COMMITTED, node.id(), "artifact", r.artifact().ref(), "sha256", r.artifact().sha256().substring(0, 12),
                "by", producedBy, "derivedFrom", String.join(",", derived));
            if (r.replacedExisting()) invalidateConsumers(run, d.name(), node.id());
        }
    }

    /** Dynamic re-plan: consumers of a changed artifact go STALE and re-run; unchanged outputs later stop the cascade. */
    private void invalidateConsumers(WorkflowRun run, String artifact, String cause) {
        List<String> stale = new ArrayList<>();
        for (WorkflowNode c : run.definition().consumersOf(artifact)) {
            NodeState s = run.state(c.id());
            switch (s.status) {
                case SUCCEEDED, AWAITING_APPROVAL, AWAITING_INPUT, FAILED -> {
                    s.status = NodeStatus.STALE; s.staged = List.of(); s.stagedReasons = List.of();
                    stale.add(c.id());
                    emit(run, ARTIFACT_INVALIDATED, c.id(), "artifact", artifact, "cause", cause);
                }
                default -> {}
            }
        }
        if (!stale.isEmpty()) emit(run, REPLAN, "", "trigger", artifact, "staleNodes", String.join(",", stale));
    }

    private void settle(WorkflowRun run) {
        Collection<NodeStatus> st = run.statuses().values();
        if (st.stream().allMatch(x -> x == NodeStatus.SUCCEEDED)) {
            run.setStatus(RunStatus.COMPLETED, "");
            emit(run, RUN_COMPLETED, "", "attemptsUsed", String.valueOf(run.attemptsUsed()));
        } else if (st.contains(NodeStatus.AWAITING_APPROVAL) || st.contains(NodeStatus.AWAITING_INPUT)) {
            String why = "awaiting human: " + run.statuses().entrySet().stream()
                .filter(e -> e.getValue() == NodeStatus.AWAITING_APPROVAL || e.getValue() == NodeStatus.AWAITING_INPUT)
                .map(e -> e.getKey() + "(" + e.getValue() + ")").toList();
            run.setStatus(RunStatus.PAUSED, why);
            emit(run, RUN_PAUSED, "", "reason", why);
        } else {
            String why = st.contains(NodeStatus.FAILED) ? "node failure" : "no runnable nodes (deadlock)";
            run.setStatus(RunStatus.FAILED, why);
            emit(run, RUN_FAILED, "", "reason", why);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireStatus(NodeState s, String nodeId, NodeStatus expected) {
        if (s.status != expected) throw new IllegalStateException("node " + nodeId + " is " + s.status + ", expected " + expected);
    }
    private static String nz(String s) { return s == null ? "" : s; }
    private void emit(WorkflowRun run, EventType t, String node, String... kv) { events.append(run.id(), t, node, data(kv)); }
}
