package dev.agentic.core.agent;

import java.util.List;

/** Structured requirement analysis: normalized problem, ambiguity list, assumptions and a dependency-ordered task list. */
public record RequirementsSpec(String problemStatement, List<String> openQuestions, List<String> assumptions, List<String> tasks) {}
