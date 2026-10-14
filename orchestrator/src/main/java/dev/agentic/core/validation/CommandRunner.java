package dev.agentic.core.validation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Executes a build/test command in an isolated working directory. Agents never supply commands: the engine owns them. */
public interface CommandRunner {
    record Result(int exitCode, String output, boolean timedOut, boolean simulated) {
        public boolean passed() { return exitCode == 0 && !timedOut; }
    }
    Result run(Path workdir, List<String> command, Duration timeout);
}
