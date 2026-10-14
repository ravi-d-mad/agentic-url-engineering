package dev.agentic.core.model;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** Stateful execution of a definition: node states, versioned artifacts, checkpoints, safe-stop flags. */
public final class WorkflowRun {
    public record CommitResult(Artifact artifact, boolean changed, boolean replacedExisting) {}

    private final String id;
    private final WorkflowDefinition definition;
    private final Instant createdAt = Instant.now();
    private RunStatus status = RunStatus.CREATED;
    private String statusReason = "";
    private final Map<String, NodeState> states = new LinkedHashMap<>();
    private final Map<String, List<Artifact>> history = new LinkedHashMap<>();
    private final Map<String, Integer> heads = new HashMap<>();
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean haltRequested;
    private final AtomicInteger attemptsUsed = new AtomicInteger();
    private volatile int attemptBudget;
    private int resumes;

    public WorkflowRun(String id, WorkflowDefinition definition, int attemptBudget) {
        this.id = id; this.definition = definition; this.attemptBudget = attemptBudget;
        definition.nodes().forEach(n -> states.put(n.id(), new NodeState()));
    }

    public String id() { return id; }
    public WorkflowDefinition definition() { return definition; }
    public Instant createdAt() { return createdAt; }
    public ReentrantLock lock() { return lock; }
    public synchronized RunStatus status() { return status; }
    public synchronized String statusReason() { return statusReason; }
    public synchronized void setStatus(RunStatus s, String reason) { status = s; statusReason = reason; }
    public NodeState state(String nodeId) { return states.get(nodeId); }
    public synchronized Map<String, NodeStatus> statuses() {
        Map<String, NodeStatus> m = new LinkedHashMap<>();
        states.forEach((k, v) -> m.put(k, v.status));
        return m;
    }

    // ---- safe-stop controls
    public void requestHalt() { haltRequested = true; }
    public void clearHalt() { haltRequested = false; }
    public boolean haltRequested() { return haltRequested; }
    public int consumeAttempt() { return attemptsUsed.incrementAndGet(); }
    public int attemptBudget() { return attemptBudget; }
    public void extendBudget(int extra) { attemptBudget += extra; }
    public int attemptsUsed() { return attemptsUsed.get(); }
    public int resumes() { return resumes; }
    public void markResumed() { resumes++; }

    // ---- artifacts
    public synchronized Optional<Artifact> latest(String name) {
        Integer h = heads.get(name);
        return h == null ? Optional.empty() : Optional.of(history.get(name).get(h - 1));
    }
    /** Active version number of an artifact, 0 if none. */
    public synchronized int version(String name) { return heads.getOrDefault(name, 0); }

    public synchronized CommitResult commit(String name, String content, String producedBy, List<String> derivedFrom) {
        Optional<Artifact> cur = latest(name);
        String sha = Artifact.hash(content);
        if (cur.isPresent() && cur.get().sha256().equals(sha)) return new CommitResult(cur.get(), false, false);
        List<Artifact> list = history.computeIfAbsent(name, k -> new ArrayList<>());
        Artifact a = new Artifact(name, list.size() + 1, content, sha, producedBy, derivedFrom, Instant.now());
        list.add(a);
        heads.put(name, a.version());
        return new CommitResult(a, true, cur.isPresent());
    }

    public synchronized List<Artifact> allVersions() {
        return history.values().stream().flatMap(List::stream).sorted(Comparator.comparing(Artifact::createdAt)).toList();
    }
    public synchronized List<Artifact> activeArtifacts() {
        List<Artifact> out = new ArrayList<>();
        heads.keySet().stream().sorted().forEach(n -> out.add(latest(n).orElseThrow()));
        return out;
    }

    // ---- checkpoints / rollback
    public synchronized Checkpoint checkpoint(String reason) {
        Checkpoint cp = new Checkpoint("cp-" + (checkpoints.size() + 1), reason, Instant.now(), new HashMap<>(heads), statuses());
        checkpoints.add(cp);
        return cp;
    }
    public synchronized Optional<Checkpoint> lastCheckpoint() {
        return checkpoints.isEmpty() ? Optional.empty() : Optional.of(checkpoints.get(checkpoints.size() - 1));
    }
    public synchronized List<Checkpoint> checkpoints() { return List.copyOf(checkpoints); }

    public synchronized void restore(Checkpoint cp) {
        heads.clear();
        heads.putAll(cp.heads());
        cp.statuses().forEach((nodeId, st) -> {
            NodeState s = states.get(nodeId);
            s.status = st;
            if (st != NodeStatus.AWAITING_APPROVAL) { s.staged = List.of(); s.stagedReasons = List.of(); }
        });
    }
}
