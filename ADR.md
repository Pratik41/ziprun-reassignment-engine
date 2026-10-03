# Architecture Decision Records - ZipRun Reassignment Engine

## Overview
This document captures the architectural decisions behind the AI-powered order reassignment engine. Each entry follows **Context → Options → Decision → Tradeoffs**, and points at the code that implements it so the reasoning can be checked against what actually ships.

Code paths are relative to `backend/reassignment-engine/src/main/java/com/ziprun/`.

---

## ADR-1: Where Routing Logic Lives

**Context**  
"Who should take this order?" is asked from two places: the HTTP endpoint `POST /orders/{id}/suggest`, and the async agentic loop when an agent goes offline. Both need the same strategies, the same candidate list and the same failure behaviour. A service that quietly accumulates routing, fallback, persistence and event publishing becomes the design smell the brief warns about.

**Options considered**
1. *Routing inside the controller / event handler* - fastest to write, but duplicates candidate selection and fallback in two callers.
2. *Routing as a domain-object method* (`order.chooseAgent(...)`) - keeps logic "on the model", but entities would need repositories and an LLM client.
3. *Application Service (`RoutingService`) + Strategy pattern* - one service owns "run the active strategy over the current candidates"; each algorithm is a separate `RoutingStrategy`.

**Decision**  
Option 3. Responsibilities are split deliberately:

| Component | Owns | Does not do |
|---|---|---|
| `routing/RoutingService` | candidate list (AVAILABLE agents minus the order's current agent), pending-load snapshot, active-strategy lookup, last-resort fallback | persistence, events, prompts |
| `routing/RoutingStrategy` implementations | ranking algorithm | data access |
| `service/ai/AIAdvisorService` + `PromptBuilder` | prompt choice, LLM call, JSON parsing | roster validation |
| `service/suggestion/SuggestionService` | persisting suggestions, idempotency, accept/reject | routing |
| `service/event/ReplanEventHandler` | orchestrating the agentic loop | routing or persistence details |

Both callers build a `RoutingContext` (`RoutingContext.initial()` or `RoutingContext.agentOffline(...)`) and call `routingService.route(order, context)`. The contract is the same; only the context differs.

**Tradeoffs accepted**  
- More classes than a single "ReassignmentService"; a reader follows controller → RoutingService → strategy → advisor.
- `RoutingService` reads the suggestion repository (for pending load), which is a read-side dependency between two concerns. I accepted it because spreading a batch of stranded orders is a routing concern.

---

## ADR-2: Runtime Strategy Switching

**Context**  
The active strategy must be switchable at runtime with no restart, from config, and must apply to both call paths. Sprint 2 adds `ZoneAffinityStrategy`; adding it should not modify existing code.

**Options considered**
1. *`@Qualifier` + config property* - picks one bean at startup; switching needs a restart.
2. *Auto-wired `Map<String, RoutingStrategy>` + mutable active name* - Spring builds the map from bean names; the active name is looked up on each call.
3. *Manual factory with a `switch`* - explicit, but every new strategy edits the factory.
4. *Spring Cloud `@RefreshScope`* - real config refresh, but pulls in Spring Cloud and a refresh endpoint for one string.

**Decision**  
Option 2 (`routing/RoutingService`):
- Strategies register by bean name: `@Component("rule-based")`, `@Component("ai")`.
- The active name lives in an `AtomicReference<String>`, seeded from `routing.strategy` (env `ROUTING_STRATEGY`) at startup.
- `PUT /routing/strategy {"strategy":"rule-based"}` (`controller/RoutingController`) swaps it; `GET /routing/strategy` shows active and available. The UI's demo panel has a toggle.
- Every `route()` call reads the reference once, so HTTP and async callers see a switch on their next call. A re-plan batch already running may switch strategy midway, order by order, and that's acceptable.
- **Startup validation:** the constructor throws if `routing.strategy` names an unregistered strategy, or if `rule-based` (the fallback) is missing. Misconfiguration fails the boot, not the first request.

Adding Sprint 2's strategy = one new class `@Component("zone-affinity") class ZoneAffinityStrategy implements RoutingStrategy`. No existing file changes.

**Tradeoffs accepted**  
- Implicit wiring: a reader must know Spring fills the map by bean name.
- The runtime switch is in-memory and per-instance: a restart reverts to config, and multiple instances would each need the PUT. For one ops service that's fine; with several instances I'd move the value to the database or a config server.
- The switch endpoint has no auth (same as the rest of the API in this sprint).

---

## ADR-3: Staying Healthy When the LLM Is Unavailable

**Context**  
LLM calls fail in several distinct ways, and a failure must never stall a request or silently drop a re-plan. The brief also allows Gemini, Groq or Ollama, and free tiers hit quotas.

**Options considered**
1. *Fail fast* - surface errors to the caller; breaks the re-plan loop whenever the LLM is down.
2. *Retry with backoff* - helps transient errors, but adds latency and does nothing for bad output.
3. *Typed failures + provider chain + validate + rule-based fallback.*

**Decision**  
Option 3. Every failure is an `LLMException` with a `Kind`, handled at the layer that understands it:

| Failure | Detected in | Kind | Response |
|---|---|---|---|
| No API key for a provider | `LLMGateway` | `NOT_CONFIGURED` | skip to next provider |
| Slow / hung provider | `LLMConfig` timeouts (`llm.timeout-ms`, default 8s) | `TIMEOUT` | next provider |
| Quota exhausted (HTTP 429) | `LLMException.fromHttp` | `RATE_LIMITED` | next provider |
| Other HTTP / network error | `LLMException.fromHttp` | `HTTP_ERROR` | next provider |
| Empty / blocked reply | provider classes | `EMPTY_RESPONSE` | next provider |
| Prose, not JSON | `AIAdvisorService.parse` | `UNPARSEABLE` | rule-based fallback |
| Agent ID not in the roster sent | `AIRoutingStrategy.validate` | `HALLUCINATED_AGENT` | rule-based fallback |
| Confidence outside [0,1], blank reasoning | `AIRoutingStrategy.validate` | `INVALID_RESPONSE` | drop that option; fall back if none remain |
| Any bug in any strategy | `RoutingService.rank` catch-all | n/a | rule-based fallback |

- **Provider chain** (`routing/gateway/LLMGateway`): `llm.providers=gemini,groq` tries Gemini then Groq. Transport failures move on to the next provider; content failures don't, because a different model's opinion doesn't fix a validation problem we can fix deterministically.
- **The fallback is visible**, not silent: the suggestion's `source` field records e.g. `rule-based (AI fallback: TIMEOUT)`, and the UI shows it as an amber tag. Every fallback also logs the order ID, trigger and kind.
- **Async path:** `AIRoutingStrategy` falls back with the *same* `RoutingContext`, so a failed AI re-plan still produces an `AGENT_OFFLINE` suggestion with recovery reasoning. `AIFallbackIntegrationTest` checks this with the mock provider in `hallucinate` mode.
- **Testing failures on purpose:** `MockLLMProvider` + `llm.mock.fail-mode=timeout|rate-limit|garbage|hallucinate`.

**Tradeoffs accepted**  
- Worst-case latency of the synchronous suggest endpoint is roughly `providers × timeout` (16s with two providers). I chose correctness of the answer over latency here; lowering `llm.timeout-ms` trades the other way.
- No retries within a provider: a transient blip moves straight to the next provider or to rule-based.
- Rule-based reasoning is plainer than the AI's. Ops still gets an actionable suggestion, labelled honestly.

---

## ADR-4: Triggering the Agentic Loop Off the Request Path

**Context**  
`PATCH /agents/{id}/status` must return immediately when an agent goes OFFLINE. Re-planning (several LLM calls) happens elsewhere. It must fire because something changed, not on a timer, and must not run against uncommitted data.

**Options considered**
1. *`@Scheduled` poller* looking for offline agents - not event-driven, adds latency up to the poll interval, and needs "already processed" bookkeeping.
2. *`@EventListener` + `@Async`* - simple, but runs as soon as the event is published, which is inside the status-update transaction, so the listener can read data from before the commit.
3. *`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`* - fires only once the OFFLINE status is committed, on a separate thread pool.
4. *Message broker (Kafka/RabbitMQ)* - durable and retryable, but heavy infrastructure for one in-process event.

**Decision**  
Option 3.
- `AgentServiceImpl.updateStatus` publishes `AgentOfflineEvent` (via `ApplicationEventPublisher`) only on a real transition to OFFLINE.
- `ReplanEventHandler.onAgentOffline` is `@Async @TransactionalEventListener(phase = AFTER_COMMIT)` on the `ziprun-` pool (`spring.task.execution.*`). If the status update rolls back, no re-plan runs. The PATCH returns as soon as the status commits; suggestions land a moment later.
- **No transaction wraps the loop.** Each step (flag order pending, persist suggestion) is its own short transaction in the services, so an 8-second LLM call never holds a DB connection open, and one failing order can't roll back the others.
- Loop shape: **observe** (event) → **reason** (`findActiveOrdersForAgent`: ASSIGNED, REASSIGNED or already PENDING) → **act** (flag all REASSIGNMENT_PENDING first so the UI updates at once, then route each with `RoutingContext.agentOffline(...)` and queue a suggestion) → **checkpoint** (ops decides, see ADR-9).

**When the async re-plan itself fails:** per-order failures are caught and logged with the order ID, and the loop continues. The order stays REASSIGNMENT_PENDING, so it's visible in the UI with "Get Suggestion" and "Reassign" buttons, and the next trigger retries it (it has no pending suggestion, so idempotency doesn't skip it). A run summary logs created / skipped / no-agent / failed counts. Orders with no AVAILABLE agent log a WARN asking ops to add capacity.

**Tradeoffs accepted**  
- In-process events are not durable: if the JVM dies mid-loop, unfinished orders stay PENDING without suggestions until the next trigger or a manual request. A broker or an outbox table would fix this; I judged it overkill for this sprint.
- `@Async` failures don't reach the HTTP caller; visibility is through logs and the UI state above.

---

## ADR-5: Extensibility & Deliberate Exclusions

**Context**  
Sprint 2 brings zones, capacity, weight classes and a third strategy; Sprint 3 brings SLA-driven proactive re-planning and a dispatch board. Today's design should make these additive, without speculative abstractions.

### Extension seams in the code

**Sprint 2: `ZoneAffinityStrategy`**
- Data: `Order.pickupZone`, `Order.dropoffZone`, `Agent.currentZone`, `Agent.maxCapacity` already exist as nullable columns (`domain/Order.java`, `domain/Agent.java`). Sprint 2 populates them; no migration.
- Behaviour: implement `RoutingStrategy.recommend(order, availableAgents, context)` and annotate `@Component("zone-affinity")`. `RoutingService` discovers it; `PUT /routing/strategy` activates it. The AI prompt already prints zones when present (`PromptBuilder.describeOrder`).
- Capacity: a filter on `agent.getActiveOrderCount() < agent.getMaxCapacity()` belongs in `RoutingService.rank`'s candidate list, one place for all strategies.

**Sprint 3: proactive SLA loop**
- `Order.slaDeadline` exists (nullable).
- Trigger: add `TriggerReason.SLA_RISK`, a `RoutingContext.slaRisk(...)` factory, and a `@Scheduled` `SlaMonitor` that publishes an `OrderAtRiskEvent`. A timer is right *there* because time passing is the event.
- The routing contract doesn't change: strategies already receive a `RoutingContext`, and `AIAdvisorService` already chooses prompts by context. A third prompt slots in next to the two existing ones.
- Persistence: `SuggestionService.createSuggestion(orderId, result, triggerReason)` already takes the trigger, and the UI badge keys off `triggerReason`.

**Sprint 3: multi-step re-plan / downstream pressure**  
Partly in place: `RoutingContext.pendingLoad` makes each routing call aware of suggestions already queued, so a batch of stranded orders is spread across agents (verified in `ReassignmentFlowIntegrationTest`). The re-plan prompt shows the whole stranded batch.

### Deliberate exclusions

1. **Auto-assigning high-confidence suggestions.** Excluded on purpose (see ADR-9). The checkpoint is a requirement, not a missing feature.
2. **SSE streaming was built last, on purpose.** I only added `POST /orders/{id}/suggest/stream` after the loop's correctness work was done. It's an *add-on* to the same path, not a second one: the endpoint attaches a `ReasoningListener` to the `RoutingContext`; the AI strategy streams from the provider (Gemini `streamGenerateContent?alt=sse`, OpenAI-style `stream:true`) and `ReasoningExtractor` forwards only the `reasoning` text, decoded incrementally from the JSON. The full reply is still parsed and validated after the stream ends. If a provider fails mid-stream or validation fails, the client gets a `restart` event and the stream still ends with the persisted (possibly rule-based) suggestion. Streaming is only on the on-demand path; async re-plans have no one watching.
3. **Full dispatch board / SLA countdown / zone map.** The "orders by agent" view covers agent load; a board with SLA colours needs SLA data that doesn't exist yet.
4. **Auth on the API.** Out of scope for the sprint; noted because `PUT /routing/strategy` is an operational lever that would need it in production.
5. **Durable event delivery** (broker / outbox), covered in ADR-4.

---

## ADR-6: Frontend Framework Choice

**Context**  
The ops interface shows pending reassignments with reasoning, accept/reject, the re-plan badge, agent status, and refreshes on its own. Choices: React 18 or Angular 17.

**Options considered**
1. *React 18 + Vite* - lighter, fast dev server, larger ecosystem.
2. *Angular 17 standalone components* - opinionated structure, built-in HttpClient, RxJS, DI.

**Decision**  
Angular 17 with standalone components (`frontend/reassignment-ui`). DI and services mirror the Spring side; RxJS makes polling simple: `RefreshService` merges on-demand refreshes with a 3-second `interval`, so suggestions created by the async loop appear without a click. Background polls don't flash spinners, and backend error messages (`{status, error, message}`) are shown verbatim in a dismissible banner.

**Tradeoffs accepted**  
- Heavier bundle and more boilerplate than React for a small UI.
- Polling rather than push: up to 3s delay and constant small requests. SSE/WebSocket push is a Sprint 3 candidate alongside the streaming bonus.

---

## ADR-7: Initial vs Re-Plan Prompts

**Context**  
A first assignment and a recovery after an agent goes offline are different situations. The model has to know which one it's in.

**Options considered**
1. *One template with a `scenario` flag.*
2. *Two prompts written for their situation, sharing only the roster table and output contract.*

**Decision**  
Option 2 (`service/ai/PromptBuilder`).

- **Initial prompt:** "routine assignment request, nothing has failed, aim for a balanced fleet". Gives the order (id, description, current agent, zones if known), the roster table (`active`, `pending`, `effective` load), and decision rules.
- **Re-plan prompt:** an *incident report*. It names who went offline and states their assignments are void. It lists every stranded order in the batch and marks which one is being decided now. It explains that the `pending` column shows where earlier batch orders are heading. Recovery priorities: speed, **spreading the batch**, and **flagging a thin roster** (fewer agents than stranded orders, so lower confidence and suggest calling in capacity). It also asks the reasoning to open by naming the recovery.
- **Shared output contract:** JSON only, `{"recommendations":[{agent_id, confidence, reasoning}]}`, ranked up to 3. agent_id must be copied from the table, confidence bands are defined, and reasoning is written for an ops manager without invented facts. The parser also accepts the brief's single-object `{"agentId", ...}` form and tolerates markdown fences.
- `AIAdvisorServiceTest.promptsAreGenuinelyDifferent` pins the differences.

**Tradeoffs accepted**  
- Two prompts to maintain. Mitigated by sharing the roster formatter and output contract, so parsing and validation are one code path.
- The re-plan routes stranded orders one call at a time rather than asking the model to plan the whole batch in one response. More calls, but each response is validated independently and one bad answer can't spoil the batch.

---

## ADR-8: Idempotency in Agentic Re-Planning

**Context**  
The same agent can flip OFFLINE → AVAILABLE → OFFLINE quickly, and two triggers can race on the same orders. Duplicate PENDING suggestions for one order confuse ops and break "accept means done".

**Options considered**
1. *Ignore it.*
2. *Check-then-insert* with a query.
3. *Check-then-insert under a row lock*, plus a cheap pre-check before the expensive LLM call.
4. *DB unique constraint* - a partial unique index (`WHERE status='PENDING' AND trigger_reason='AGENT_OFFLINE'`) isn't portable across H2/Postgres via JPA.

**Decision**  
Option 3:
- Pre-check in `ReplanEventHandler.replanOrder` (`existsByOrderIdAndStatusAndTriggerReason`) skips orders that are already covered before spending an LLM call.
- `SuggestionService.createReplanSuggestionIfAbsent` re-checks inside a transaction after `OrderRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`). A concurrent run blocks on the lock, then sees the first run's suggestion and skips.
- Stranded orders include ones already REASSIGNMENT_PENDING, so an order whose earlier re-plan failed (no suggestion) is retried on the next trigger, while covered ones are skipped.
- `AgentServiceImpl` only publishes the event on a real transition to OFFLINE, so a repeated OFFLINE→OFFLINE PATCH is a no-op.
- Tested: `ReassignmentFlowIntegrationTest.secondOfflineTriggerDoesNotDuplicateSuggestions`.

**Tradeoffs accepted**  
- A row lock per order during the insert (milliseconds; the LLM call happens before the lock is taken).
- If ops *rejects* the offline suggestion, a later trigger will create a new one. I treat that as desirable: the order is still stranded.

---

## ADR-9: The Human Checkpoint

**Context**  
The loop could reassign automatically. The brief asks for a checkpoint where ops approves.

**Options considered**
1. *Auto-assign everything* - fastest recovery, no human in the loop.
2. *Queue suggestions, ops decides* - slower, but ops stays in control of irreversible moves.
3. *Hybrid: auto-assign above a confidence threshold.*

**Decision**  
Option 2. The loop only ever creates PENDING suggestions; nothing in `ReplanEventHandler` changes `assignedAgentId`. The checkpoint is `PATCH /suggestions/{id}` → `SuggestionService.updateStatus`:
- **ACCEPTED:** in one transaction, `OrderService.reassignToAgent` releases the old agent's load, adds to the new agent's, sets status REASSIGNED, and rejects any other PENDING suggestions for that order. If the recommended agent went OFFLINE in the meantime, it returns 409 and nothing changes.
- **REJECTED:** the order stays REASSIGNMENT_PENDING; the UI offers "Get Suggestion" or manual reassign.

**When I'd remove the checkpoint:** for re-plans where (a) the suggestion came from the AI rather than a fallback, (b) confidence ≥ 0.9, (c) the recommended agent's effective load stays below `maxCapacity`, and (d) the order's SLA would breach before a typical ops response time. That requires Sprint 2 capacity and Sprint 3 SLA data, and an audit trail of auto-decisions before it's trusted.

**Tradeoffs accepted**  
Recovery speed is bounded by ops response time. That is the intended trade: wrong automatic reassignments cost more than a minute of delay.

---

## Summary Table

| ADR | Topic | Decision | Key point |
|-----|-------|----------|-----------|
| 1 | Routing location | Application service + Strategy | One `route(order, context)` for HTTP and async |
| 2 | Strategy switching | Bean map + `AtomicReference`, `PUT /routing/strategy` | No restart; startup validation; new strategy = new class |
| 3 | LLM resilience | Typed failures, provider chain, validation, labelled fallback | Never silent; source shows what really answered |
| 4 | Loop trigger | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` | Sees committed state; no DB tx across LLM calls |
| 5 | Extensibility | Nullable sprint-2/3 columns, context-based contract | Zone strategy and SLA trigger are additive |
| 6 | Frontend | Angular 17 standalone + RxJS polling | Async results appear without a click |
| 7 | Prompts | Routine request vs incident report | Model knows it's recovering and sees the whole batch |
| 8 | Idempotency | Pre-check + re-check under row lock | No duplicates even with concurrent triggers |
| 9 | Checkpoint | Queue, never auto-assign | Accept is atomic; criteria for removing it defined |

---

*Document started:* 2026-09-23  
*Last updated:* 2026-10-04
