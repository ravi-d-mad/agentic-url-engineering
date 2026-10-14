package dev.agentic.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Immutable, versioned, hash-addressed output. derivedFrom holds "name@version" refs: the decision lineage. */
public record Artifact(String name, int version, String content, String sha256, String producedBy,
                       List<String> derivedFrom, Instant createdAt) {
    public Artifact { derivedFrom = List.copyOf(derivedFrom); }

    public String ref() { return name + "@" + version; }

    public static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
