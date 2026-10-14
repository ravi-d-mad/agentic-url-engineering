package dev.agentic.core.engine;

import dev.agentic.core.model.Artifact;
import dev.agentic.core.policy.Bundles;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Materializes file-bundle artifacts into a directory, refusing any path that escapes it (defence in depth after policy). */
public final class WorkspaceWriter {
    private WorkspaceWriter() {}

    public static int write(Path root, Iterable<Artifact> artifacts) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        int n = 0;
        for (Artifact a : artifacts)
            for (Map.Entry<String, String> f : Bundles.split(a.content()).entrySet()) {
                Path target = base.resolve(f.getKey()).normalize();
                if (!target.startsWith(base)) throw new SecurityException("path escapes workspace: " + f.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.getValue());
                n++;
            }
        return n;
    }
}
