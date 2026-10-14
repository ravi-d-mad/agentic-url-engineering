package dev.agentic.core.events;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Append-only audit log. Every state change in the engine goes through here. */
public interface EventStore {
    RunEvent append(String runId, EventType type, String nodeId, Map<String, String> data);
    List<RunEvent> events(String runId);
    void addListener(Consumer<RunEvent> listener);

    static Map<String, String> data(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
