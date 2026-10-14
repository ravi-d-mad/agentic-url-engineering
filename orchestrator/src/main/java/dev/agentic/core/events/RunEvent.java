package dev.agentic.core.events;

import java.time.Instant;
import java.util.Map;

public record RunEvent(long seq, Instant ts, String runId, EventType type, String nodeId, Map<String, String> data) {
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"seq\":").append(seq)
            .append(",\"ts\":\"").append(ts).append("\",\"runId\":\"").append(esc(runId))
            .append("\",\"type\":\"").append(type).append("\",\"node\":\"").append(esc(nodeId)).append("\",\"data\":{");
        boolean first = true;
        for (var e : data.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append("}}").toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }
}
