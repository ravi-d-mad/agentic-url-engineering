package dev.agentic.core.validation;

import dev.agentic.core.agent.Agent;
import dev.agentic.core.agent.AgentException;
import dev.agentic.core.agent.AgentRequest;
import dev.agentic.core.engine.WorkspaceWriter;
import dev.agentic.core.model.Artifact;
import dev.agentic.core.model.ArtifactDraft;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Deterministic (non-LLM) agent: assembles baseline + generated files in a throw-away workspace and runs the
 * engine-owned build/test commands. The verdict is an artifact, so it is versioned, audited and gate-checked like any output.
 */
public final class BuildValidationAgent implements Agent {
    public static final String PASS = "RESULT: PASSED";
    public static final String FAIL = "RESULT: FAILED";
    private static final List<String> SKIP_DIRS = List.of("target", ".git", "data", "node_modules");

    private final CommandRunner runner;
    private final Path baseline;               // may be null (greenfield)
    private final List<List<String>> commands;
    private final Duration timeout;

    public BuildValidationAgent(CommandRunner runner, Path baseline, List<List<String>> commands, Duration timeout) {
        this.runner = runner; this.baseline = baseline; this.commands = List.copyOf(commands); this.timeout = timeout;
    }

    @Override public String name() { return "build-validator"; }

    @Override
    public List<ArtifactDraft> execute(AgentRequest r) {
        Path ws = null;
        try {
            ws = Files.createTempDirectory("agentic-validate-");
            if (baseline != null) copyTree(baseline, ws);
            int written = WorkspaceWriter.write(ws, r.inputs().values());   // generated files overlay the baseline
            StringBuilder md = new StringBuilder("# Build validation\n");
            boolean allPassed = true, simulated = false;
            StringBuilder detail = new StringBuilder();
            for (List<String> cmd : commands) {
                CommandRunner.Result res = runner.run(ws, cmd, timeout);
                simulated |= res.simulated();
                allPassed &= res.passed();
                detail.append("\n## `").append(String.join(" ", cmd)).append("`\n- exit: ").append(res.exitCode())
                    .append(res.timedOut() ? " (TIMED OUT)" : "").append("\n```\n").append(res.output().strip()).append("\n```\n");
            }
            md.append("MODE: ").append(simulated ? "SIMULATED (commands were NOT executed)" : "REAL").append('\n')
              .append("FILES OVERLAID: ").append(written).append('\n')
              .append(allPassed ? PASS : FAIL).append('\n').append(detail);
            return List.of(new ArtifactDraft("validation-report", md.toString()));
        } catch (IOException e) {
            throw new AgentException("validation workspace error: " + e.getMessage(), e);
        } finally {
            if (ws != null) deleteTree(ws);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && SKIP_DIRS.contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
