package dev.agentic.core.agent;

import dev.agentic.core.model.ArtifactDraft;
import java.util.List;

/** Normalizes the raw request into a spec, surfaces ambiguities, and decomposes work into ordered tasks. */
public class RequirementsAgent implements Agent {
    public static final String SYSTEM = """
        You are a requirements analyst. Normalize the request into an engineering problem statement.
        List every ambiguity as an open question; if 'clarifications' are provided, resolve them and only
        keep questions that remain truly blocking. Record assumptions explicitly. Decompose into tasks,
        each as 'T<n>: <task> (depends: <T ids or none>)'.""";
    private final LlmGateway llm;
    public RequirementsAgent(LlmGateway llm) { this.llm = llm; }
    @Override public String name() { return "requirements-analyst"; }

    @Override
    public List<ArtifactDraft> execute(AgentRequest r) {
        RequirementsSpec s = llm.completeStructured(name(), SYSTEM, PromptAgent.userPrompt(r), RequirementsSpec.class);
        if (s == null || s.problemStatement() == null || s.problemStatement().isBlank()) throw new AgentException("no problem statement");
        StringBuilder md = new StringBuilder("# Requirements\n\n## Problem\n").append(s.problemStatement()).append("\n\n## Assumptions\n");
        nz(s.assumptions()).forEach(a -> md.append("- ").append(a).append('\n'));
        md.append("\n## Task decomposition\n");
        nz(s.tasks()).forEach(t -> md.append("- ").append(t).append('\n'));
        StringBuilder q = new StringBuilder();
        nz(s.openQuestions()).forEach(x -> q.append("- ").append(x).append('\n'));
        return List.of(new ArtifactDraft("requirements", md.toString()), new ArtifactDraft("open-questions", q.toString()));
    }
    private static List<String> nz(List<String> l) { return l == null ? List.of() : l; }
}
