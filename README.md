# Agentic SDLC Orchestrator (URL Shortener assignment)

An orchestration layer that takes a requirement through requirements -> design -> implementation -> tests -> docs -> release
readiness with **controlled autonomy**: agents execute, policies constrain, humans approve high-impact work.
Agents are LLM-backed via **Spring AI**; the same engine runs offline against a deterministic mock so every scenario is reproducible.

## Quick start

```bash
# 0. Layout: shortener-service/ (baseline app, :8081)  orchestrator/ (engine + REST, :8080)  docs/  scenarios/
# 1. Offline demo, JDK 21 only (no Maven, no API key). Runs all five scenarios and writes evidence to ./runs
javac -d out $(find orchestrator/src/main/java/dev/agentic/core -name '*.java')
java -cp out dev.agentic.core.demo.Demo all runs        # or: greenfield | brownfield | ambiguous | replan | chaos

# 2. Tests
mvn -q test

# 3. REST control plane (mock LLM by default)
mvn -q -pl orchestrator spring-boot:run
# real model through Spring AI (Gemini via its OpenAI-compatible endpoint):
GEMINI_API_KEY=... AGENTIC_LLM=spring-ai mvn -q -pl orchestrator spring-boot:run
```

REST walk-through:

```bash
curl -s localhost:8080/api/runs -H 'content-type: application/json' \
  -d '{"scenario":"ambiguous","requirement":"Make links safer and scale better."}'      # -> PAUSED, requirements AWAITING_INPUT
curl -s localhost:8080/api/runs/$ID/artifacts/open-questions                            # read the questions
curl -s localhost:8080/api/runs/$ID/artifacts/clarifications -H 'content-type: application/json' \
  -d '{"content":"Safer = block malicious URLs. Scale = 1k rps.","by":"po"}'            # re-plan + continue
curl -s localhost:8080/api/runs/$ID/approvals/architecture -H 'content-type: application/json' \
  -d '{"decision":"APPROVE","by":"me","comment":"ok"}'
curl -s localhost:8080/api/runs/$ID/report ; curl -s localhost:8080/api/runs/$ID/lineage
```

## Architecture

```
            +----------------------- Human control plane (REST) ------------------------+
            | start | approve/reject | answer clarifications | edit artifact | halt/resume |
            +------------------------------------+-------------------------------------+
                                                 v
+-------------------------------- OrchestratorEngine ---------------------------------+
|  WorkflowDefinition (validated DAG)   WorkflowRun (state, versioned artifacts, checkpoints)
|  wave scheduler -> parallel nodes -> JOIN -> policy + exit gates -> [approval] -> commit
|  re-plan (invalidate consumers) | retry/backoff/fallback | wave rollback | safe-stop
+------+------------------+-------------------+---------------------------+-------------+
       v                  v                   v                           v
   Agents (Spring AI)  PolicyEngine        EventStore (JSONL)        MetricsReport / Lineage
   via LlmGateway      secret, path,       append-only audit         derived from the log
                       size, high-impact
```

Packages: `core.model` (domain), `core.engine` (orchestration), `core.policy`, `core.agent` (agents + `LlmGateway` seam),
`core.events`, `core.observability`, `core.workflow` (SDLC graphs), `ai` (Spring AI adapter), `api` (REST).
The core has **no framework dependency**; Spring is only the adapter and the control plane.

### Control flow of one wave
1. **Ready set**: nodes whose dependencies are all `SUCCEEDED`; entry gates run (`PENDING`/`STALE` nodes only).
2. **Parallel execution** on virtual threads, each with timeout, bounded retries, exponential backoff and optional fallback agent.
3. **Per attempt**: agent -> output-shape check -> **policies** (DENY triggers retry with feedback) -> **exit gates** (FAIL retries; NEEDS_INPUT pauses).
4. **Join**. If any node failed permanently, the **whole wave is rolled back** to its pre-wave checkpoint (nothing committed), run is `FAILED` and resumable.
5. Otherwise results apply: commit, or stage for **approval** (`AWAITING_APPROVAL`), or pause for **input**.
6. Committing a *changed* artifact version **invalidates its consumers** (re-plan). Unchanged output (same SHA-256) stops the cascade.

### Governance model
| Control | Mechanism |
|---|---|
| Autonomy boundary | `SUPERVISED` (always approve), `GUARDED` (approve high-impact), `AUTONOMOUS` (approve only if a policy flags it). A policy flag overrides any level. |
| Human checkpoints | architecture sign-off, release readiness, schema migration, anything matching `high-impact-change` (DDL/destructive SQL, `db/migration/`, `pom.xml`, CI, Dockerfile) |
| Guardrails | secret scan, path-traversal guard (policy + `WorkspaceWriter`), change-size cap, high-impact detection |
| Safe-stop | operator `halt`, per-run attempt budget, per-agent timeout, bounded retries/rejections/resumes |
| Rollback | pre-wave checkpoints (artifact head pointers + node statuses; history is immutable) |
| Audit | every state change is an event; artifacts carry `derivedFrom` lineage and `producedBy` (agent / agent+approver / human) |
| Metrics | success rate, retries, fallbacks, rollbacks, re-plans, denials, approvals, MTTR, end-to-end latency split into human-wait vs agent-active |

## The three scenarios (evidence in `scenarios/`)

| Scenario | Request | Shows |
|---|---|---|
| **Greenfield** | Build a URL shortener | Task decomposition in `requirements`; `architecture ‖ test-plan`; then `unit-tests ‖ integration-tests ‖ docs ‖ security-review` with a join; approvals at design and release |
| **Brownfield** | Add click analytics + rate limiting to existing service | `CodebaseScanner` -> `impact-analysis`; migration is **forced to human approval by policy**; regression tests; additive-migration rollback plan |
| **Ambiguous** | "Make links safer and scale better" | Ambiguity list -> run pauses (`AWAITING_INPUT`) -> human answers -> **re-plan** -> continues |
| *Re-plan* | Edit requirements after completion | Only architecture, test-plan, implementation and docs re-run; unit/integration tests and security review are skipped because regenerated implementation was byte-identical |
| *Chaos* | Injected faults | 2 transient failures retried; leaked credential denied by policy and regenerated; architecture rejected with feedback and revised |

Each folder contains `report.md` (metrics), `lineage.mmd`, `workflow.mmd`, the `.jsonl` audit log, every artifact, and the generated `workspace/`.

## Key decisions and trade-offs
- **Wave-synchronous scheduling** instead of fully dynamic: simple, deterministic join points and atomic rollback, at the cost of idle time when branches differ in duration.
- **Wave atomicity**: a failed sibling discards successful siblings' work (re-run on resume). Chosen for a consistent last-known-good state; cheaper per-node rollback needs result caching.
- **Commit after gates**: artifacts only exist once validated/approved, so rollback is pointer restoration, not undo.
- **Early cutoff by content hash** (as in build systems) bounds re-plan cost.
- **Deterministic mock behind `LlmGateway`**: reproducible tests and demos; also the seam for swapping providers.
- **Prompts are passed as Message objects**, not templates, because generated code contains `{}` that Spring AI's template engine would parse.
- **In-memory run state + JSONL log** (no DB): minimal moving parts for a prototype.

## Limitations (honest)
- Run state is in memory; after restart only the audit log survives (event-sourced rebuild is a straightforward extension, not implemented).
- `advance()` is synchronous per HTTP request; production should run it on a worker and poll/stream status.
- Gates such as `BLOCKER:` keyword detection are crude; a production gate would use structured verdicts and real scanners (SAST, dependency audit) and actually compile/run generated tests in a sandbox. Offline scenarios use SIMULATED build validation (labelled in every report); real validation is opt-in.
- Approvals are identity-by-string; real use needs authN/Z, four-eyes rules, and signed audit entries.
- Single node, no distributed locking.
- The mock proves orchestration behaviour, not LLM output quality; quality needs evals on a real model.
- The Spring AI/REST layer is thin and was not compiled in the authoring sandbox (no network for Maven). Verify dependency versions (`spring-ai.version` 1.0.0, Boot 3.4.5) and the model name in `application.yml` when you first build.

## Testing approach
`EngineTest` and `PolicyTest` cover: cycle rejection, human checkpoints, clarification pause, bounded retry, secret denial, wave rollback, budget halt + resume, re-plan early cutoff, rejection limit, policy-forced approval, workspace path escape. Engine tests use the mock gateway so they are deterministic and need no network.

## Windows setup (PowerShell)
1. Install JDK 17 and confirm `java -version` reports 17.
2. Install Maven if needed: `winget install --id Apache.Maven -e`; reopen PowerShell; confirm `mvn -version`.
3. From the repository root run `mvn test`, then `mvn -pl orchestrator spring-boot:run`.
4. Open `http://localhost:8080/actuator/health`. Audit events persist in `./data/agentic_sdlc` (H2 file DB).
   The H2 console is **off by default**; enable with `H2_CONSOLE=true` (local use only, no authentication).
5. Real build validation: `AGENTIC_VALIDATION=real AGENTIC_BASELINE_DIR=shortener-service` (runs allowlisted `mvn` commands in a temp copy).

## Documents
`docs/ARCHITECTURE.md` (components, orchestration model, control flow, decisions) | `docs/ENGINEERING_SUMMARY.md` (plan, traceability, risk register, assumptions, limitations) | `docs/validation.md` (what is and is not verified).
