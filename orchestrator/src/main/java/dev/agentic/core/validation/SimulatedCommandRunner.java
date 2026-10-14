package dev.agentic.core.validation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Offline stand-in used by the demo/tests. Results are always labelled SIMULATED in the validation report; nothing is executed. */
public final class SimulatedCommandRunner implements CommandRunner {
    private final boolean pass;
    public SimulatedCommandRunner() { this(true); }
    public SimulatedCommandRunner(boolean pass) { this.pass = pass; }

    @Override
    public Result run(Path workdir, List<String> command, Duration timeout) {
        return new Result(pass ? 0 : 1, pass ? "(simulated) BUILD SUCCESS" : "(simulated) BUILD FAILURE", false, true);
    }
}
