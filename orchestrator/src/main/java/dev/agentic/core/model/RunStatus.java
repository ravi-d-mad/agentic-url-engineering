package dev.agentic.core.model;

public enum RunStatus {
    CREATED, RUNNING, PAUSED, COMPLETED, FAILED, HALTED;

    /** advance() is a no-op in these states; FAILED/HALTED need an explicit human resume. */
    public boolean isStopped() { return this == COMPLETED || this == FAILED || this == HALTED; }
}
