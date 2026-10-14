package dev.agentic.core.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;

/** Durable audit trail: one JSON line per event in <dir>/<runId>.jsonl. */
public class JsonlEventStore extends InMemoryEventStore {
    private final Path dir;

    public JsonlEventStore(Path dir) {
        this.dir = dir;
        try { Files.createDirectories(dir); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override
    protected void persist(RunEvent e) {
        try {
            Files.writeString(dir.resolve(e.runId() + ".jsonl"), e.toJson() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
}
