package dev.agentic.core.model;

public interface Gate {
    String name();
    GateResult check(GateContext ctx);
}
