# Validation status

## Verified in the authoring environment
- Orchestrator core compiles at Java 17 language level (`javac -source 17`), no framework dependencies.
- 20 tests exercised by reflection against a minimal JUnit shim: all pass (engine, policy, validation, runner).
- All five offline scenarios run end to end and reach the expected states.
- `AllowlistedCommandRunner` executed a real allowlisted command (`java -version`) and refused non-allowlisted ones.
- Fixed a compile error in `JdbcEventStore` (checked `JsonProcessingException` inside a `RowMapper` lambda).

## NOT verified (needs JDK 17 + Maven + network)
- `mvn test` with real JUnit 5 for both modules; Spring context start-up; `shortener-service` API tests (`UrlFlowTest`).
- Flyway on H2 with `flyway-core` only (Boot 3.4.5 / Flyway 10); if H2 support needs a separate module, add it.
- Spring AI 1.0.0 against a live model (Gemini via OpenAI-compatible endpoint; set `AGENTIC_MODEL` to an available model).
- Real build validation (`AGENTIC_VALIDATION=real`, `AGENTIC_BASELINE_DIR=shortener-service`) end to end.
- REST flow, H2 event store (`/api/runs/{id}/events` after a run), actuator health.

## Not implemented
- Recovery of the run aggregate from the event log after restart.
- Automatic repair loop after a failed build; container sandbox for generated code.
