package dev.agentic.core.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real runner. Safety properties:
 * - exact-match allowlist of argv vectors, no shell, so no injection via metacharacters;
 * - scrubbed environment (only PATH/JAVA_HOME/HOME/MAVEN_OPTS pass through, so API keys never reach generated code);
 * - hard timeout with forced kill; output captured to a file and capped.
 * Note: this is process-level isolation. For untrusted code in production, run inside a container/VM without network or secrets.
 */
public final class AllowlistedCommandRunner implements CommandRunner {
    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final List<String> PASS_THROUGH_ENV = List.of("PATH", "JAVA_HOME", "HOME", "MAVEN_OPTS");
    private final List<List<String>> allowlist;

    public AllowlistedCommandRunner(List<List<String>> allowlist) { this.allowlist = List.copyOf(allowlist); }

    public static List<List<String>> mavenDefaults() {
        return List.of(List.of("mvn", "-q", "-B", "test"), List.of("mvn", "-q", "-B", "-DskipTests", "compile"));
    }

    @Override
    public Result run(Path workdir, List<String> command, Duration timeout) {
        if (!allowlist.contains(command)) throw new SecurityException("command not allowlisted: " + command);
        try {
            Path log = Files.createTempFile("validate-", ".log");
            try {
                ProcessBuilder pb = new ProcessBuilder(command).directory(workdir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
                Map<String, String> env = pb.environment();
                Map<String, String> keep = new java.util.HashMap<>();
                PASS_THROUGH_ENV.forEach(k -> { if (env.containsKey(k)) keep.put(k, env.get(k)); });
                env.clear();
                env.putAll(keep);
                Process p = pb.start();
                boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!done) { p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS); }
                String out = Files.readString(log);
                if (out.length() > MAX_OUTPUT_CHARS) out = "...(truncated)...\n" + out.substring(out.length() - MAX_OUTPUT_CHARS);
                return new Result(done ? p.exitValue() : -1, out, !done, false);
            } finally { Files.deleteIfExists(log); }
        } catch (IOException e) {
            return new Result(-1, "could not start command: " + e.getMessage(), false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted", false, false);
        }
    }
}
