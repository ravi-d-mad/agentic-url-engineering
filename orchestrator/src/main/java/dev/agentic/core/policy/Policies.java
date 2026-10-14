package dev.agentic.core.policy;

import dev.agentic.core.model.ArtifactDraft;
import dev.agentic.core.model.WorkflowNode;
import dev.agentic.core.policy.PolicyDecision.Verdict;
import java.util.*;
import java.util.regex.Pattern;

/** Built-in guardrails for security, change control and compliance. */
public final class Policies {
    private Policies() {}

    public static List<Policy> defaults() {
        return List.of(secretScan(), pathSafety(), changeSize(60_000), highImpact());
    }

    /** Security: block credentials in generated output. */
    public static Policy secretScan() {
        List<Pattern> pats = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC )?PRIVATE KEY-----"),
            Pattern.compile("(?i)(api[_-]?key|secret|password|token)\\s*[:=]\\s*[\"'][^\"'\\s]{8,}[\"']"));
        return new Policy() {
            public String name() { return "secret-scan"; }
            public List<PolicyDecision> evaluate(WorkflowNode n, List<ArtifactDraft> drafts) {
                List<PolicyDecision> out = new ArrayList<>();
                for (ArtifactDraft d : drafts)
                    for (Pattern p : pats)
                        if (p.matcher(d.content()).find())
                            out.add(new PolicyDecision(Verdict.DENY, name(), "possible credential in '" + d.name() + "'"));
                return out;
            }
        };
    }

    /** Security: generated files may only be written inside the workspace. */
    public static Policy pathSafety() {
        Pattern ok = Pattern.compile("[A-Za-z0-9._/-]+");
        return new Policy() {
            public String name() { return "path-safety"; }
            public List<PolicyDecision> evaluate(WorkflowNode n, List<ArtifactDraft> drafts) {
                List<PolicyDecision> out = new ArrayList<>();
                for (ArtifactDraft d : drafts) {
                    List<String> paths = new ArrayList<>(Bundles.split(d.content()).keySet());
                    paths.add(d.name());
                    for (String p : paths)
                        if (!ok.matcher(p).matches() || p.contains("..") || p.startsWith("/"))
                            out.add(new PolicyDecision(Verdict.DENY, name(), "unsafe path '" + p + "'"));
                }
                return out;
            }
        };
    }

    /** Change control: cap the blast radius of a single agent step. */
    public static Policy changeSize(int maxChars) {
        return new Policy() {
            public String name() { return "change-size"; }
            public List<PolicyDecision> evaluate(WorkflowNode n, List<ArtifactDraft> drafts) {
                return drafts.stream().filter(d -> d.content().length() > maxChars)
                    .map(d -> new PolicyDecision(Verdict.DENY, name(), "'" + d.name() + "' exceeds " + maxChars + " chars")).toList();
            }
        };
    }

    /** Change control: schema changes, destructive SQL, build/CI/container changes always need a human. */
    public static Policy highImpact() {
        Pattern sql = Pattern.compile("(?i)\\b(DROP|ALTER|TRUNCATE)\\s+TABLE\\b|\\bDELETE\\s+FROM\\b");
        Pattern path = Pattern.compile("(^|/)(db/migration/|\\.github/workflows/|Dockerfile$|pom\\.xml$)");
        return new Policy() {
            public String name() { return "high-impact-change"; }
            public List<PolicyDecision> evaluate(WorkflowNode n, List<ArtifactDraft> drafts) {
                List<PolicyDecision> out = new ArrayList<>();
                for (ArtifactDraft d : drafts) {
                    if (sql.matcher(d.content()).find())
                        out.add(new PolicyDecision(Verdict.REQUIRE_APPROVAL, name(), "destructive/DDL SQL in '" + d.name() + "'"));
                    for (String p : Bundles.split(d.content()).keySet())
                        if (path.matcher(p).find())
                            out.add(new PolicyDecision(Verdict.REQUIRE_APPROVAL, name(), "touches controlled file '" + p + "'"));
                }
                return out;
            }
        };
    }
}
