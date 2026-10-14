package dev.agentic.core.observability;

import dev.agentic.core.model.*;

/** Mermaid graph of every artifact version and what it was derived from (human edits highlighted). */
public final class LineageExporter {
    private LineageExporter() {}

    public static String mermaid(WorkflowRun run) {
        StringBuilder sb = new StringBuilder("graph LR\n  classDef human fill:#fde68a,stroke:#b45309;\n");
        for (Artifact a : run.allVersions()) {
            String id = id(a.ref());
            sb.append("  ").append(id).append("[\"").append(a.ref()).append("<br/>").append(a.producedBy().replace("\"", "'")).append("\"]\n");
            if (a.producedBy().startsWith("human:")) sb.append("  class ").append(id).append(" human;\n");
            for (String d : a.derivedFrom()) sb.append("  ").append(id(d)).append(" --> ").append(id).append('\n');
        }
        return sb.toString();
    }
    private static String id(String s) { return s.replaceAll("[^A-Za-z0-9]", "_"); }
}
