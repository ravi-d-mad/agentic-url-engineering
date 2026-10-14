package dev.agentic.core.agent;

public class AgentException extends RuntimeException {
    public AgentException(String m) { super(m); }
    public AgentException(String m, Throwable c) { super(m, c); }
}
