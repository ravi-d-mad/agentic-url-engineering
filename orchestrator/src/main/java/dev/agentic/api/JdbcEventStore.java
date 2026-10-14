package dev.agentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentic.core.events.EventStore;
import dev.agentic.core.events.EventType;
import dev.agentic.core.events.RunEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** H2-backed append-only audit event store used by the REST application. */
final class JdbcEventStore implements EventStore {
    private static final TypeReference<Map<String, String>> DATA_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();

    JdbcEventStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public RunEvent append(String runId, EventType type, String nodeId, Map<String, String> data) {
        Instant now = Instant.now();
        Map<String, String> safeData = Map.copyOf(data);
        try {
            String jsonData = mapper.writeValueAsString(safeData);
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO agentic_run_events(event_ts, run_id, event_type, node_id, event_data) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setString(2, runId);
                ps.setString(3, type.name());
                ps.setString(4, nodeId == null ? "" : nodeId);
                ps.setString(5, jsonData);
                return ps;
            }, keys);
            Number key = keys.getKey();
            RunEvent event = new RunEvent(key == null ? 0L : key.longValue(), now, runId, type, nodeId == null ? "" : nodeId, safeData);
            listeners.forEach(listener -> listener.accept(event));
            return event;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist audit event for run " + runId, e);
        }
    }

    @Override
    public List<RunEvent> events(String runId) {
        return jdbc.query("SELECT seq, event_ts, run_id, event_type, node_id, event_data FROM agentic_run_events WHERE run_id = ? ORDER BY seq",
            (rs, row) -> new RunEvent(rs.getLong("seq"), rs.getTimestamp("event_ts").toInstant(),
                rs.getString("run_id"), EventType.valueOf(rs.getString("event_type")), rs.getString("node_id"),
                parse(rs.getString("event_data"))), runId);
    }

    /** Jackson's readValue throws a checked exception, which a RowMapper lambda cannot propagate. */
    private Map<String, String> parse(String json) {
        try { return mapper.readValue(json, DATA_TYPE); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("corrupt audit event payload", e); }
    }

    @Override
    public void addListener(Consumer<RunEvent> listener) { listeners.add(listener); }
}
