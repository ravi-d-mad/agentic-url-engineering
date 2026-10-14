package dev.agentic.core.policy;

public record PolicyDecision(Verdict verdict, String policy, String reason) {
    public enum Verdict { ALLOW, REQUIRE_APPROVAL, DENY }
}
