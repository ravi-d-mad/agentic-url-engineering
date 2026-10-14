package dev.agentic.core.agent;

import dev.agentic.core.model.Artifact;
import dev.agentic.core.model.ArtifactDraft;
import java.util.List;

/** Generic LLM-backed agent: system prompt + consumed artifacts + prior feedback -> one output artifact. */
public class PromptAgent implements Agent {
    private final String name, system, output;
    private final LlmGateway llm;

    public PromptAgent(String name, String system, String output, LlmGateway llm) {
        this.name = name; this.system = system; this.output = output; this.llm = llm;
    }
    @Override public String name() { return name; }

    @Override
    public List<ArtifactDraft> execute(AgentRequest r) {
        String text = llm.complete(name, system, userPrompt(r));
        if (text == null || text.isBlank()) throw new AgentException("empty LLM response");
        return List.of(new ArtifactDraft(output, text.strip()));
    }

    static String userPrompt(AgentRequest r) {
        StringBuilder sb = new StringBuilder();
        for (Artifact a : r.inputs().values())
            if (!a.content().isBlank()) sb.append("### ").append(a.name()).append(" (v").append(a.version()).append(")\n").append(a.content()).append("\n\n");
        if (!r.feedback().isEmpty()) {
            sb.append("### feedback (fix these problems)\n");
            r.feedback().forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        return sb.toString();
    }
}
