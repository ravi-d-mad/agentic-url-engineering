package dev.agentic.ai;

import dev.agentic.core.agent.AgentException;
import dev.agentic.core.agent.LlmGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

/**
 * Spring AI implementation of the LlmGateway seam.
 * Prompts are passed as Message objects (not templates) because artifacts contain braces (code, JSON)
 * that Spring AI's template engine would otherwise try to interpret.
 */
public class SpringAiLlmGateway implements LlmGateway {
    private final ChatClient client;

    public SpringAiLlmGateway(ChatClient client) { this.client = client; }

    @Override
    public String complete(String agentName, String system, String user) {
        try {
            return client.prompt(new Prompt(List.of(new SystemMessage(system), new UserMessage(user)))).call().content();
        } catch (RuntimeException e) {
            throw new AgentException("LLM call failed for " + agentName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T completeStructured(String agentName, String system, String user, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String text = complete(agentName, system + "\n\n" + converter.getFormat(), user);
        try {
            return converter.convert(text);
        } catch (RuntimeException e) {
            throw new AgentException("malformed structured output from " + agentName + ": " + e.getMessage(), e);   // engine retries with feedback
        }
    }
}
