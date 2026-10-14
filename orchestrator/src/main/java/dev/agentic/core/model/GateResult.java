package dev.agentic.core.model;

public record GateResult(Outcome outcome, String reason) {
    public enum Outcome { PASS, FAIL, NEEDS_INPUT }
    public static GateResult pass() { return new GateResult(Outcome.PASS, ""); }
    public static GateResult fail(String reason) { return new GateResult(Outcome.FAIL, reason); }
    public static GateResult needsInput(String reason) { return new GateResult(Outcome.NEEDS_INPUT, reason); }
}
