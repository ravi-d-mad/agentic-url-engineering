package dev.agentic.core.agent;

/** The only seam to an LLM. Spring AI implements it in production; a deterministic mock makes runs reproducible offline. */
public interface LlmGateway {
    String complete(String agentName, String system, String user);
    <T> T completeStructured(String agentName, String system, String user, Class<T> type);
}
