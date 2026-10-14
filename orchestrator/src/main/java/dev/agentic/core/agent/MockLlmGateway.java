package dev.agentic.core.agent;

import dev.agentic.core.model.Artifact;
import java.util.List;
import java.util.Locale;

/** Deterministic offline stand-in for an LLM so every scenario is reproducible and testable without an API key. */
public final class MockLlmGateway implements LlmGateway {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T completeStructured(String agent, String system, String user, Class<T> type) {
        if (type != RequirementsSpec.class) throw new AgentException("mock supports RequirementsSpec only");
        String lower = user.toLowerCase(Locale.ROOT);
        boolean vague = lower.matches("(?s).*(safer|scale|better|improve).*");
        boolean clarified = user.contains("### clarifications");
        boolean brownfield = user.contains("### codebase-snapshot");
        List<String> questions = vague && !clarified
            ? List.of("What does 'safer' mean: malware/phishing blocking, abuse/rate limiting, or access control on links?",
                      "What load is 'scale'? Target redirects/second, read/write ratio, and single vs multi-region?")
            : List.of();
        List<String> assumptions = clarified || !vague
            ? List.of("Short codes are Base62, 7 chars", "Redirects use HTTP 302 so analytics are not bypassed by caches",
                      "Single region; a relational DB is the system of record")
            : List.of("Proceeding with provisional defaults until questions are answered");
        List<String> tasks = brownfield
            ? List.of("T1: Impact analysis of existing controllers/entities (depends: none)",
                      "T2: Design analytics + rate limiting (depends: T1)", "T3: Schema migration (depends: T2)",
                      "T4: Implement (depends: T2, T3)", "T5: Regression + new tests (depends: T4)", "T6: Docs and release notes (depends: T4)")
            : List.of("T1: Design API, schema and components (depends: none)", "T2: Test plan (depends: none)",
                      "T3: Implement service (depends: T1, T2)", "T4: Unit and integration tests (depends: T3)",
                      "T5: Security review and docs (depends: T3)", "T6: Release readiness (depends: T4, T5)");
        return (T) new RequirementsSpec("Deliver the requested URL shortener capability with measurable reliability and safe change control.",
            questions, assumptions, tasks);
    }

    @Override
    public String complete(String agent, String system, String user) {
        String digest = Artifact.hash(user).substring(0, 8);
        boolean hasMigration = user.contains("### schema-migration");
        return switch (agent) {
            case "architect" -> "# Architecture (input digest " + digest + ")\n"
                + "- Components: LinkController, LinkService, LinkRepository, ClickRecorder, RateLimiter\n"
                + "- API: POST /api/links, GET /{code} (302), GET /api/links/{code}/stats\n"
                + "- Schema: links(code PK, url, created_at, expires_at), click_events(id, code FK, ts)\n"
                + "- Trade-off: synchronous click write now, queue later if p99 redirect latency suffers\n"
                + (user.contains("### feedback") ? "- Review feedback addressed: see revised sections above\n" : "");
            case "test-planner" -> "# Test plan\n- Unit: Base62, expiry, validation\n- Integration: create->redirect->stats\n- Failure: expired link 410, unknown code 404, rate limit 429";
            case "impact-analyst" -> "# Impact analysis (grounded in the codebase snapshot)\n"
                + "- Modified: UrlController (rate-limit responses, new GET /api/v1/urls/{code}/analytics/daily)\n"
                + "- Modified: ShortUrlService.resolveAndRecordClick (redirect hot path: keep 302 contract, keep latency)\n"
                + "- Modified: ClickEventRepository (daily aggregation query); AnalyticsResponse stays backward compatible\n"
                + "- New: RateLimitFilter (per-IP token bucket on POST /api/v1/urls), ClickDaily rollup table\n"
                + "- Data flow: redirect -> ClickEvent insert (unchanged); rollup computed on read from click_events; no write amplification\n"
                + "- Existing schema: V1__create_url_tables.sql defines short_urls and click_events; new migration must be additive\n"
                + "- Risks: 429 behaviour change for bulk clients; index idx_click_events_url_time must cover the daily query";
            case "migration-author" -> "=== FILE: src/main/resources/db/migration/V2__click_daily_rollup.sql ===\nCREATE TABLE click_daily (\n  short_url_id BIGINT NOT NULL REFERENCES short_urls(id),\n  day DATE NOT NULL,\n  clicks BIGINT NOT NULL,\n  PRIMARY KEY (short_url_id, day)\n);";
            case "implementer" -> hasMigration
                ? "=== FILE: src/main/java/dev/shortener/api/RateLimitFilter.java ===\npackage dev.shortener.api;\npublic class RateLimitFilter { /* per-IP token bucket on POST /api/v1/urls, 429 + Retry-After */ }\n"
                  + "=== FILE: src/main/java/dev/shortener/repository/ClickDailyQuery.java ===\npackage dev.shortener.repository;\npublic interface ClickDailyQuery { /* clicks per day for a short url, from click_events */ }"
                : "=== FILE: src/main/java/dev/shortener/Base62.java ===\npackage dev.shortener;\npublic final class Base62 {\n  private static final String A = \"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\";\n  public static String encode(long n) { StringBuilder s = new StringBuilder(); do { s.append(A.charAt((int) (n % 62))); n /= 62; } while (n > 0); return s.reverse().toString(); }\n}\n"
                  + "=== FILE: src/main/java/dev/shortener/LinkService.java ===\npackage dev.shortener;\npublic class LinkService { /* create, resolve with expiry check */ }";
            case "unit-test-writer" -> "=== FILE: src/test/java/dev/shortener/Base62Test.java ===\npackage dev.shortener;\nclass Base62Test { /* encode(0), encode(61), encode(62), monotonic uniqueness */ }";
            case "integration-test-writer" -> "=== FILE: src/test/java/dev/shortener/LinkFlowIT.java ===\npackage dev.shortener;\nclass LinkFlowIT { /* create -> redirect -> stats; expired -> 410 */ }";
            case "regression-test-writer" -> "=== FILE: src/test/java/dev/shortener/RedirectRegressionTest.java ===\npackage dev.shortener;\nclass RedirectRegressionTest { /* existing redirect contract unchanged: 302 + Location; 410 inactive; 404 unknown */ }";
            case "docs-writer" -> "# Service documentation\nOpenAPI summary, configuration, runbook, and the architecture decisions recorded above.";
            case "security-reviewer" -> "# Security review\nNo blocking findings. Notes: validate URL scheme (http/https only), cap URL length, rate-limit creates.";
            case "release-manager" -> "# Release readiness\n- [x] Tests defined  - [x] Security review clean  - [x] Docs present\n- Rollback: redeploy previous image; migration is additive\n- Recommendation: GO";
            default -> throw new AgentException("mock has no fixture for agent " + agent);
        };
    }
}
