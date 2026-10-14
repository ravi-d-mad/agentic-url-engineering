# Architecture Overview

## 1. Goal and shape
A governed, stateful orchestration layer that turns a requirement into reviewable engineering output (design, code, tests,
docs, release readiness). Agents execute; policies constrain; humans approve and own final quality.
The target application is a URL shortener (`shortener-service`), used as the **brownfield baseline**; greenfield runs
produce a new one from the requirement alone.

```
 Human control plane (REST): start | approve/reject | answer clarifications | edit artifact | halt | resume | audit
                                          |
 +---------------------------- OrchestratorEngine (core, framework-free) ----------------------------+
 | WorkflowDefinition: validated DAG (entry gates, exit gates, autonomy level, retries, fallback)     |
 | WorkflowRun: node states, versioned hash-addressed artifacts, checkpoints, safe-stop flags         |
 | wave scheduler: ready set -> parallel nodes -> JOIN -> policies+gates -> [approval] -> commit      |
 +-----+------------------+------------------+--------------------+----------------------+-----------+
       |                  |                  |                    |                      |
   Agents (LLM via   PolicyEngine        BuildValidation      EventStore            Metrics / Lineage /
   Spring AI seam)   secret, path,       (allowlisted         append-only audit     FinalSummary (all
                     size, high-impact   commands, temp ws)   (H2 in app, JSONL     derived from the log)
                                                              in demo)
```

## 2. Components
| Package | Responsibility |
|---|---|
| `core.model` | Domain: workflow DAG, nodes, gates, versioned artifacts, run state, checkpoints |
| `core.engine` | Scheduling, retries, rollback, re-plan, approvals, safe-stop; `WorkspaceWriter` (path-safe file materialisation) |
| `core.policy` | Guardrails returning ALLOW / REQUIRE_APPROVAL / DENY |
| `core.agent` | Agent contract, `LlmGateway` seam, requirements/prompt agents, deterministic mock, fault injector |
| `core.validation` | `BuildValidationAgent`, `AllowlistedCommandRunner`, `SimulatedCommandRunner` |
| `core.events`, `core.observability` | Audit log; metrics, lineage, final summary |
| `core.workflow` | Greenfield and brownfield graphs; `CodebaseScanner` |
| `ai` | Spring AI implementation of `LlmGateway` (Gemini via OpenAI-compatible endpoint) |
| `api` | REST control plane, H2 `JdbcEventStore`, wiring |
| `shortener-service` | Baseline Spring Boot app (create/redirect/expiry/analytics/soft-delete), Flyway + H2, API tests |

## 3. Orchestration model
**Graph.** Nodes declare `consumes`/`produces`; dependencies derive from them (plus optional explicit edges). Construction
rejects duplicates, dangling references and cycles.

**Wave execution.** Each iteration runs every node whose dependencies are `SUCCEEDED` in parallel, then synchronises.
Per attempt: agent call (timeout) -> output-shape check -> policies -> exit gates. Policy DENY and gate FAIL feed their reason
back to the agent on the next attempt (bounded retries with backoff, then optional fallback agent).

**Commit discipline.** Nothing is committed until policies, gates and (if required) human approval pass. A wave is atomic:
if any node fails permanently, no output of that wave is committed and state returns to the pre-wave checkpoint.

**Non-linear re-plan.** Committing a *changed* artifact version marks its consumers `STALE`; they re-run. If a re-run
produces byte-identical output (same SHA-256), the cascade stops (build-system style early cutoff). Human edits to any
upstream artifact use the same path, including after completion.

**Controlled autonomy.**
| Level | Human approval required when |
|---|---|
| SUPERVISED | always (architecture, release readiness) |
| GUARDED | node is high-impact or a policy flags the output |
| AUTONOMOUS | only when a policy flags the output |
A policy `REQUIRE_APPROVAL` can never be bypassed by autonomy level.

**Safe-stop.** Operator halt flag, per-run attempt budget, per-agent timeout, bounded retries / rejections / resumes. A halted
or failed run is resumable only by a human, with an explicit budget extension.

## 4. Control flow of a run (greenfield)
1. `requirements` normalises the request, lists ambiguities, records assumptions, decomposes tasks. Open questions pause the run (`AWAITING_INPUT`); answers re-plan.
2. `architecture` (human sign-off) in parallel with `test-plan`.
3. `implementation`, then `unit-tests`, `integration-tests`, `docs`, `security-review` in parallel.
4. `build-validation` assembles baseline + generated files in a temp workspace and runs engine-owned commands; a failing build fails the run (escalate, do not blindly retry).
5. `release-readiness` (human sign-off) consumes tests, docs, security review and the validation report.
Brownfield adds `impact-analysis` (from a scan of the real baseline), a high-impact `schema-migration` (policy-forced approval) and `regression-tests`.

## 5. Key decisions
| Decision | Why | Cost |
|---|---|---|
| Framework-free core, Spring only as adapter | Testable offline; provider-agnostic | Some wiring code |
| Wave-synchronous scheduling | Deterministic joins, atomic rollback | Idle time when branches differ |
| Wave atomicity | Consistent last-known-good state | Sibling work is redone on resume |
| Commit-after-gates, immutable versions | Rollback = pointer restore; audit is complete | Storage grows with versions |
| Content-hash early cutoff | Bounds re-plan cost | Requires deterministic agents to pay off |
| Engine owns build commands (agents never supply them) | Prevents command injection from model output | Less flexible than agent-chosen tooling |
| Deterministic mock behind `LlmGateway` | Reproducible demos/tests, no key needed | Proves orchestration, not model quality |
| Separate wave and agent thread pools | Avoids nested-task starvation in a bounded pool | Two pools to size |
| Audit in H2, events appended not updated | Durable decision history | Run aggregate itself not yet recoverable |
