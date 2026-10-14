package dev.agentic.core;

import dev.agentic.core.engine.WorkspaceWriter;
import dev.agentic.core.model.*;
import dev.agentic.core.policy.*;
import dev.agentic.core.policy.PolicyDecision.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PolicyTest {
    static final WorkflowNode NODE = WorkflowNode.node("n", "a").produces("out").build();
    static List<PolicyDecision> eval(String content) {
        return PolicyEngine.defaults().evaluate(NODE, List.of(new ArtifactDraft("out", content)));
    }

    @Test void awsKeyIsDenied() { assertEquals(Verdict.DENY, eval("key AKIAABCDEFGHIJKLMNOP").get(0).verdict()); }
    @Test void pathTraversalIsDenied() { assertTrue(eval("=== FILE: ../../etc/passwd ===\nx").stream().anyMatch(d -> d.verdict() == Verdict.DENY)); }
    @Test void migrationRequiresApproval() { assertEquals(Verdict.REQUIRE_APPROVAL, eval("=== FILE: src/main/resources/db/migration/V2__x.sql ===\nCREATE TABLE t(id int);").get(0).verdict()); }
    @Test void destructiveSqlRequiresApproval() { assertEquals(Verdict.REQUIRE_APPROVAL, eval("DROP TABLE links;").get(0).verdict()); }
    @Test void cleanCodeIsAllowed() { assertTrue(eval("=== FILE: src/main/java/A.java ===\nclass A {}").isEmpty()); }

    @Test
    void workspaceWriterRefusesEscape(@TempDir Path dir) {
        Artifact evil = new Artifact("x", 1, "=== FILE: ../escape.txt ===\nboom", "h", "t", List.of(), Instant.now());
        assertThrows(SecurityException.class, () -> WorkspaceWriter.write(dir, List.of(evil)));
    }
}
