package dev.agentic.core.events;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class InMemoryEventStore implements EventStore {
    private final Map<String, List<RunEvent>> byRun = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final List<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public synchronized RunEvent append(String runId, EventType type, String nodeId, Map<String, String> data) {
        RunEvent e = new RunEvent(seq.incrementAndGet(), Instant.now(), runId, type, nodeId == null ? "" : nodeId, Map.copyOf(data));
        byRun.computeIfAbsent(runId, k -> new ArrayList<>()).add(e);
        persist(e);
        listeners.forEach(l -> l.accept(e));
        return e;
    }

    protected void persist(RunEvent e) {}

    @Override
    public synchronized List<RunEvent> events(String runId) { return List.copyOf(byRun.getOrDefault(runId, List.of())); }

    @Override
    public void addListener(Consumer<RunEvent> l) { listeners.add(l); }
}
