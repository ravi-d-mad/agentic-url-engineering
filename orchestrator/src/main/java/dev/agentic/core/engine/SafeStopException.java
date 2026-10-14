package dev.agentic.core.engine;

/** Non-retryable: operator halt or exhausted budget. Stops the run in a resumable HALTED state. */
public class SafeStopException extends RuntimeException {
    public SafeStopException(String m) { super(m); }
}
