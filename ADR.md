# Architecture Decision Records - ZipRun Reassignment Engine

## Overview
This document captures the architectural decisions behind the AI-powered order reassignment engine. Each entry follows **Context → Options → Decision → Tradeoffs**, and points at the code that implements it so the reasoning can be checked against what actually ships.

Code paths are relative to `backend/reassignment-engine/src/main/java/com/ziprun/`.

---

## ADR-1: Where Routing Logic Lives

**Context**  
"Who should take this order?" is asked from two places: the HTTP endpoint `POST /orders/{id}/suggest`, and the async agentic loop when an agent goes offline. Both need the same strategies, the same candidate list and the same failure behaviour. A service that quietly accumulates routing, fallback, persistence and event publishing is a design smell I wanted to avoid.

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
The active strategy must be switchable at runtime with no restart, from config, and must apply to both call paths. A planned `ZoneAffinityStrategy` should be addable without modifying existing code.

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

Adding the zone strategy = one new class `@Component("zone-affinity") class ZoneAffinityStrategy implements RoutingStrategy`. No existing file changes.

**Tradeoffs accepted**  
- Implicit wiring: a reader must know Spring fills the map by bean name.
- ~~The runtime switch is in-memory: a restart reverts to config.~~ **Update:** losing the choice on every restart surprised users in practice, so the switch is now saved in an `app_settings` table and restored at startup (`routing.strategy` is only the first-run default). Several instances would still each cache the value in memory until restart; a shared config store would fix that.
- The switch endpoint has no auth (same as the rest of the API for now).

---

## ADR-3: Staying Healthy When the LLM Is Unavailable

**Context**  
LLM calls fail in several distinct ways, and a failure must never stall a request or silently drop a re-plan. Free tiers of Gemini and Groq hit quotas and have occasional outages (Gemini returning 503 "overloaded" is common).

**Options considered**
1. *Fail fast* - surface errors to the caller; breaks the re-plan loop whenever the LLM is down.
2. *Retry with backoff* - helps transient errors, but adds latency and does nothing for bad output.
3. *Typed failures + provider chain + validate + rule-based fallback.*

**Decision**  
Option 3. Every failure is an `LLMException` with a `Kind`, handled at the layer that understands it:

| Failure | Detected in | Kind | Response |
|---|---|---|---|
| No API key for a provider | `LLMGateway` | `NOT_CONFIGURED` | skip to next provider |
| Slow / hung provider | `LLMConfig` timeouts (`llm.timeout-ms`, default 20s) | `TIMEOUT` | next provider |
| Quota exhausted (HTTP 429) | `LLMException.fromHttp` | `RATE_LIMITED` | next provider |
| Other HTTP / network error | `LLMException.fromHttp` | `HTTP_ERROR` | next provider |
| Empty / blocked reply | provider classes | `EMPTY_RESPONSE` | next provider |
| Prose, not JSON | `AIAdvisorService.parse` | `UNPARSEABLE` | rule-based fallback |
| Agent ID not in the roster sent | `AIRoutingStrategy.validate` | `HALLUCINATED_AGENT` | rule-based fallback |
| Confidence outside [0,1], blank reasoning | `AIRoutingStrategy.validate` | `INVALID_RESPONSE` | drop that option; fall back if none remain |
| Any bug in any strategy | `RoutingService.rank` catch-all | n/a | rule-based fallback |

- **Provider chain** (`routing/gateway/LLMGateway`): `llm.providers=gemini,groq` tries Gemini then Groq. Transport failures move on to the next provider; content failures don't, because a different model's opinion doesn't fix a validation problem we can fix deterministically.
- **The fallback is visible**, not silent: the suggestion's `source` field records e.g. `rule-based (AI fallback: TIMEOUT)`, and the UI shows it as an amber tag. Every fallback also logs the order ID, trigger and kind.
- **Confidence guardrail:** a validated answer can still be overconfident. Groq once gave 0.95 to the only available agent, who would have carried 8 orders. `RoutingService.applyRosterLimits` caps every strategy's confidence at 0.60 on a thin roster (fewer candidates than stranded orders, with a note telling ops to add capacity) and at 0.75 with a single candidate. Putting the cap in the routing service rather than the prompt means it holds no matter which model or strategy answered.
- **Async path:** `AIRoutingStrategy` falls back with the *same* `RoutingContext`, so a failed AI re-plan still produces an `AGENT_OFFLINE` suggestion with recovery reasoning. `AIFallbackIntegrationTest` checks this with a test-only fake LLM that returns a hallucinated agent.
- **Testing failures on purpose:** the test suite registers a fake provider (`src/test/.../MockLLMProvider`, `llm.mock.fail-mode=timeout|rate-limit|garbage|hallucinate`). It lives only in test code; the application talks to real LLMs only.

**Tradeoffs accepted**  
- Worst-case latency of the synchronous suggest endpoint is roughly `providers × timeout` (40s with two providers; Gemini usually answers in ~8s, Groq in ~1-2s). I chose correctness of the answer over latency here; lowering `llm.timeout-ms` trades the other way.
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
- In-process events are not durable: if the JVM dies mid-loop, unfinished orders stay PENDING without suggestions until the next trigger or a manual request. A broker or an outbox table would fix this; I judged it overkill at this stage.
- `@Async` failures don't reach the HTTP caller; visibility is through logs and the UI state above.

---

## ADR-5: Extensibility & Deliberate Exclusions

**Context**  
The roadmap includes zones, capacity limits, weight classes and a zone-aware strategy, then SLA-driven proactive re-planning and a full dispatch board. Today's design should make these additive, without speculative abstractions.

### Extension seams in the code

**Zone-aware routing: `ZoneAffinityStrategy`**
- Data: `Order.pickupZone`, `Order.dropoffZone`, `Agent.currentZone`, `Agent.maxCapacity` already exist as nullable columns (`domain/Order.java`, `domain/Agent.java`). The zone feature populates them; no migration.
- Behaviour: implement `RoutingStrategy.recommend(order, availableAgents, context)` and annotate `@Component("zone-affinity")`. `RoutingService` discovers it; `PUT /routing/strategy` activates it. The AI prompt already prints zones when present (`PromptBuilder.describeOrder`).
- Capacity: a filter on `agent.getActiveOrderCount() < agent.getMaxCapacity()` belongs in `RoutingService.rank`'s candidate list, one place for all strategies.

**Proactive SLA loop**
- `Order.slaDeadline` exists (nullable).
- Trigger: add `TriggerReason.SLA_RISK`, a `RoutingContext.slaRisk(...)` factory, and a `@Scheduled` `SlaMonitor` that publishes an `OrderAtRiskEvent`. A timer is right *there* because time passing is the event.
- The routing contract doesn't change: strategies already receive a `RoutingContext`, and `AIAdvisorService` already chooses prompts by context. A third prompt slots in next to the two existing ones.
- Persistence: `SuggestionService.createSuggestion(orderId, result, triggerReason)` already takes the trigger, and the UI badge keys off `triggerReason`.

**Multi-step re-plan / downstream pressure**  
Partly in place: `RoutingContext.pendingLoad` makes each routing call aware of suggestions already queued, so a batch of stranded orders is spread across agents (verified in `ReassignmentFlowIntegrationTest`). The re-plan prompt shows the whole stranded batch.

### Deliberate exclusions

1. **Auto-assigning high-confidence suggestions.** Excluded on purpose (see ADR-9). The checkpoint is a requirement, not a missing feature.
2. **SSE streaming was built last, on purpose.** I only added `POST /orders/{id}/suggest/stream` after the loop's correctness work was done. It's an *add-on* to the same path, not a second one: the endpoint attaches a `ReasoningListener` to the `RoutingContext`; the AI strategy streams from the provider (Gemini `streamGenerateContent?alt=sse`, OpenAI-style `stream:true`) and `ReasoningExtractor` forwards only the `reasoning` text, decoded incrementally from the JSON. The full reply is still parsed and validated after the stream ends. If a provider fails mid-stream or validation fails, the client gets a `restart` event and the stream still ends with the persisted (possibly rule-based) suggestion. Streaming is only on the on-demand path; async re-plans have no one watching.
3. **Full dispatch board / zone map.** Still excluded. Deadlines now exist (ADR-12) and show as countdown badges, but a map needs coordinates, not zones.
4. **Auth on the API.** *Since built:* see ADR-14.
5. **Durable event delivery** (broker / outbox), covered in ADR-4.
6. **Schema migrations (Flyway).** *Since built:* see ADR-15. (Before it, the schema came from `ddl-auto=update`, and adding an enum value like `SuggestionStatus.EXPIRED` needed a manual `ALTER` on existing H2 databases.)

*Update:* the zone and capacity seams were used, but not exactly as sketched above: see ADR-11 for why zones became part of the rule-based score instead of a separate strategy. The SLA loop followed this plan closely (ADR-12).

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
- Polling rather than push: up to 3s delay and constant small requests. *Since replaced by push (ADR-13); polling remains only as the fallback while the live stream is down.*

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
- **Shared output contract:** JSON only, `{"recommendations":[{agent_id, confidence, reasoning}]}`, ranked up to 3. agent_id must be copied from the table, confidence bands are defined, and reasoning is written for an ops manager without invented facts. The parser also accepts a single-object `{"agentId", ...}` form and tolerates markdown fences.
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
- **Stale suggestions.** A suggestion is a snapshot taken when it was created. If the agent it *recommends* later stops being AVAILABLE (goes BUSY or OFFLINE), the loop marks it `EXPIRED` (not `REJECTED`: no human said no) and re-plans the order if it is still waiting (`SuggestionService.expirePendingRecommending`). Without this, ops would see recommendations for agents who can no longer take the order. Accepting such a suggestion was already refused with 409, but it shouldn't be offered in the first place. BUSY publishes its own `AgentBusyEvent`: the agent keeps delivering their own orders, so only suggestions pointing at them are withdrawn. Accepting also requires the recommended agent to still be AVAILABLE. Tested by `suggestionsPointingAtAnAgentWhoGoesOfflineAreWithdrawnAndReplanned` and `...WhoGoesBusy...`.
- **An agent becomes AVAILABLE (re-balance).** Capacity grew, so suggestions made while fewer agents were available may be lopsided (e.g. all on the one agent who was available), and orders with no candidate may now have one. `AgentAvailableEvent` withdraws the open suggestions of every REASSIGNMENT_PENDING order and re-plans them; pending-load balancing spreads them by effective load. Trade-off: suggestions ops is looking at get replaced, and with the AI strategy this costs one LLM call per stranded order each time someone becomes available. A late re-plan for an order ops resolved meanwhile is dropped under the row lock.
- **Agent goes offline *during* routing.** Routing reads the roster, then may wait seconds for the LLM; the recommended agent can go offline in between, after their own clean-up has already run. `SuggestionService.createSuggestion` re-checks the agent right before saving and throws `StaleRecommendationException`; both callers re-route once. (Found while testing against the live Gemini API, where a 503 retry stretched routing to ~7s.)
- Tested: `ReassignmentFlowIntegrationTest.secondOfflineTriggerDoesNotDuplicateSuggestions`.

**Tradeoffs accepted**  
- A row lock per order during the insert (milliseconds; the LLM call happens before the lock is taken).
- If ops *rejects* the offline suggestion, a later trigger will create a new one. I treat that as desirable: the order is still stranded.

---

## ADR-9: The Human Checkpoint

**Context**  
The loop could reassign automatically. I chose a checkpoint where ops approves instead.

**Options considered**
1. *Auto-assign everything* - fastest recovery, no human in the loop.
2. *Queue suggestions, ops decides* - slower, but ops stays in control of irreversible moves.
3. *Hybrid: auto-assign above a confidence threshold.*

**Decision**  
Option 2. The loop only ever creates PENDING suggestions; nothing in `ReplanEventHandler` changes `assignedAgentId`. The checkpoint is `PATCH /suggestions/{id}` → `SuggestionService.updateStatus`:
- **ACCEPTED:** in one transaction, `OrderService.reassignToAgent` releases the old agent's load, adds to the new agent's, sets status REASSIGNED, and rejects any other PENDING suggestions for that order. If the recommended agent went OFFLINE in the meantime, it returns 409 and nothing changes.
- **REJECTED:** the order stays REASSIGNMENT_PENDING; the UI offers "Get Suggestion" or manual reassign.
- **Original agent is back:** if the order's own agent is AVAILABLE again before ops decides (BUSY doesn't count: they aren't taking orders), the UI offers "Keep with <agent>" (`POST /orders/{id}/keep`): the order returns to ASSIGNED and its open suggestions are `EXPIRED`, in one transaction. The system does *not* do this automatically when the agent returns. Whether a recovered agent should take the orders back (they may still be unwell, or the order may already be late) is the kind of call the checkpoint exists for.

**When I'd remove the checkpoint:** for re-plans where (a) the suggestion came from the AI rather than a fallback, (b) confidence ≥ 0.9, (c) the recommended agent's effective load stays below `maxCapacity`, and (d) the order's SLA would breach before a typical ops response time. The capacity and deadline data now exist (ADR-11, ADR-12); what's still missing is an audit trail of auto-decisions, and a track record from Insights, before it's trusted.

**Fleet guardrail: at least one agent stays AVAILABLE.** Taking the last AVAILABLE agent to BUSY or OFFLINE is refused with 409 ("make another agent AVAILABLE first"). Otherwise routing would have no candidates, and every stranded order would sit without a suggestion. The check locks the AVAILABLE rows (`AgentRepository.findByStatusForUpdate`), so two concurrent requests can't both remove the last two. The UI disables those buttons and says why. *Trade-off:* this models the ops console, where a human is changing statuses. In production, an agent's own app reporting OFFLINE (a crash, a breakdown) is a fact, not a request, and can't be refused. That path would bypass the rule and page ops instead.

**Tradeoffs accepted**  
Recovery speed is bounded by ops response time. That is the intended trade: wrong automatic reassignments cost more than a minute of delay.

---

## ADR-10: Noticing Offline Agents Automatically (Heartbeats)

**Context**  
The loop reacted to status changes, but someone still had to *notice* an agent was gone and click Offline. A dead phone, crash or dropped connection could go unnoticed for a long time: exactly the "fails silently when no one is watching" problem the project exists to solve.

**Options considered**
1. *Keep it manual* - simplest, but the "observe" step depends on a person.
2. *Agents' apps send heartbeats; a monitor marks silent agents OFFLINE* - small API surface, works with any client.
3. *Persistent connections (WebSocket) and offline on disconnect* - instant, but needs connection management and misfires on brief network blips.

**Decision**  
Option 2. `POST /agents/{id}/heartbeat` records `lastHeartbeatAt`. `HeartbeatMonitor` runs every 5 s and, for on-duty agents silent for longer than `agents.heartbeat.timeout-seconds` (60), calls `AgentService.markOfflineIfSilentSince`. That re-checks under a transaction (a heartbeat may just have arrived), sets OFFLINE with an explanatory `statusNote`, publishes the normal `AgentOfflineEvent` and logs an `AGENT_AUTO_OFFLINE` activity. A scheduled check is the right tool *here* (unlike for the re-plan loop in ADR-4) because the event being detected is time passing. Rules:
- Only agents whose app has reported at least once are monitored (`lastHeartbeatAt` not null), so hand-managed agents are unaffected.
- Auto-offline bypasses the "keep one agent Available" guardrail: it reports a fact, it isn't a request.
- An app reconnecting does **not** put the agent back on duty. The note says so, and ops decides (the ADR-9 checkpoint).
- A manual status change clears the note and pauses monitoring until the next heartbeat, so a stale timestamp can't instantly undo ops' decision.

**Tradeoffs accepted**  
- Detection lag of up to timeout + check interval (~65 s). Shorter timeouts catch failures faster but risk false offlines on flaky mobile networks.
- The monitor runs in every backend instance; with several instances one should be elected (or use a shared scheduler lock).
- There is no real agent app yet; the Fleet page simulates one from the browser.

---

## ADR-11: Capacity Limits and Zone-Aware Routing

**Context**  
Routing ranked on effective load alone. Nothing stopped one agent collecting a dozen orders when they were the only one available, and an agent across town ranked the same as one next door.

**Options considered**
1. *A separate `ZoneAffinityStrategy`* (the ADR-5 sketch), selectable next to `ai` and `rule-based`.
2. *Zones and capacity as inputs to every strategy:* capacity as a candidate filter in `RoutingService`, zone distance as part of the rule-based score and as extra columns for the AI.

**Decision**  
Option 2. A separate strategy would make ops choose between "balanced" and "nearby", when a dispatcher wants both; and the AI would never see location. So:
- **Capacity** (`RoutingService.rank` + `applyCapacityLimits`): agents whose effective load is at their capacity (`maxCapacity`, else `agents.default-max-capacity` = 6) are left out for every strategy, including the AI and its fallback. If *everyone* is full the agents stay in (the order still needs someone) but confidence is capped at 0.40 with an "over capacity" note. Counting queued suggestions keeps a batch from overfilling anyone.
- **Zones** (`routing/Zones`): 16 areas with a neighbour map, and a coarse distance (same / neighbour / far / unknown). Rule-based score = effective load + penalty 0/1/3/2. The AI's agent table gains capacity, zone and distance columns, and the prompt asks it to prefer nearby agents.

**Tradeoffs accepted**  
- Zones are a proxy for travel time. Penalties in "orders" are a judgement call (a neighbour is worth one order, far is worth three); they're constants in one enum, easy to tune.
- Zone data is maintained by hand on the Fleet page until an agent app reports location.

---

## ADR-12: Re-Planning Before an Order Is Late (SLA Monitor)

**Context**  
Re-plans only happened when an agent's status changed. An order could sit behind five others with a busy agent and miss its deadline with nobody prompted to act.

**Decision**  
Orders get a deadline at creation (`orders.default-sla-minutes`, or chosen in the dialog). `SlaMonitor` runs every 30 s, as the ADR-5 plan described (time passing is the event, so a schedule is right). For each ASSIGNED / REASSIGNED order due within `orders.sla.at-risk-minutes` (30) and not yet flagged:
1. claim it under a row lock (`slaAlertedAt`), so it's handled exactly once even with concurrent runs;
2. route it with a new `TriggerReason.SLA_RISK` context (same capacity, zone and AI rules; the AI gets a "deadline at risk" situation line);
3. queue a suggestion **only if** the best agent has fewer orders ahead than the current one; otherwise record that it stays.

The order stays with its agent while ops decides (ADR-9): accept moves it, reject keeps it. Delivering an order withdraws its open suggestions.

**Tradeoffs accepted**  
- "Fewer orders ahead" is a count, not a time estimate.
- Flag-once means a rejected order isn't suggested again even as it gets later; repeated alerts would be noise, and the queue still shows it as late.
- Existing orders don't get backfilled deadlines (they would all be "late" at once).

---

## ADR-13: Live Updates with Server-Sent Events

**Context**  
The console polled four endpoints every 3 s: up to 3 s of lag, and constant traffic from every open tab.

**Options considered**
1. *WebSocket (STOMP)* - two-way, but the console never sends anything over it, and it brings a broker abstraction.
2. *Server-Sent Events carrying the changed data* - fast, but duplicates the API's shapes and permission logic in a second channel.
3. *SSE carrying only "what kind of data changed"*; the console re-reads through the normal API.

**Decision**  
Option 3. `GET /events` (`LiveUpdates`) sends `change {topics}`. A JPA entity listener (`ChangeTracker`) on every entity reports writes **after commit**, so every write path is covered without touching services and no console sees rolled-back data. Changes within 150 ms are merged into one event (a re-plan of many orders is one refresh). A comment every 25 s keeps idle connections alive; nginx doesn't buffer `/api`. The console re-reads on each event and on reconnect, and falls back to 3-second polling only while the stream is down. SSE works with the session cookie (ADR-14), which a WebSocket handshake or `EventSource` with custom headers would complicate.

**Tradeoffs accepted**  
- A change event causes a full re-read of the four lists rather than a patch: simple and always consistent, slightly more data per change.
- Connections are held per backend instance; several instances would need a shared pub/sub (e.g. Redis) to fan events out.

---

## ADR-14: Sign-In and API Security

**Context**  
Anyone who could reach the API could change statuses, move orders or switch the routing strategy. The old CORS filter also trusted any origin *containing* `localhost:4200` with credentials (`localhost:4200.evil.com` would match), harmless only because there were no credentials yet.

**Options considered**
1. *JWT bearer tokens* - stateless, but the token has to live in JavaScript-readable storage, and `EventSource` (ADR-13) can't send an Authorization header.
2. *Session cookie + CSRF protection* - the cookie is HttpOnly and sent automatically, including on the event stream.
3. *An external identity provider (OIDC)* - right for an organisation, heavy for one ops team.

**Decision**  
Option 2 with Spring Security (`config/SecurityConfig`):
- `POST /auth/login` with `OPS_USERNAME` / `OPS_PASSWORD` creates a session (new id on sign-in; HttpOnly, SameSite=Lax, 12 h). HTTP Basic is also accepted for scripts. Unauthenticated calls get a bare 401 (no `WWW-Authenticate`, so browsers never prompt or cache Basic credentials).
- CSRF double-submit: an `XSRF-TOKEN` cookie that the console echoes in `X-XSRF-TOKEN`, which Angular's HttpClient does automatically. Header-authenticated requests (Basic, agent token) are exempt because a browser won't add those headers on its own.
- Agents' phone apps authenticate heartbeats with a shared `X-Agent-Token` and can do nothing else.
- 5 failed sign-ins from one address → 10-minute lockout.
- CORS is an exact allow-list; the console doesn't need it because it calls `/api` on its own origin.

**Tradeoffs accepted**  
- One shared ops account: the activity log can't name the person. Named users and roles are the next step.
- Sessions and the lockout counter are in memory: a backend restart signs everyone out, and several instances would need shared session storage.

---

## ADR-15: Flyway Migrations and PostgreSQL

**Context**  
`ddl-auto=update` only adds, can't convert types, and turned H2 enum columns into native `ENUM`s that needed manual `ALTER`s for every new value. Docker ran H2 in a volume, which is not a production database.

**Decision**  
Flyway owns the schema (`db/migration`), Hibernate creates nothing (`ddl-auto=none`), and Docker Compose runs PostgreSQL 16 (`DATABASE_URL`); local runs and tests keep H2.
- `V1` is written to be **idempotent** (`IF NOT EXISTS`, `SET DATA TYPE`) and existing databases are baselined at version 0, so V1 runs on them too: it adds whatever columns their age lacks and converts the `ENUM` columns to `VARCHAR`. This upgraded the real development database in place, verified on a copy first.
- Sample data moved from `data.sql` into `V2` (inserted only into an empty database).
- Migrations use SQL valid on both H2 and PostgreSQL; CI runs the main flow on a real PostgreSQL to keep that true.

**Tradeoffs accepted**  
- Two database engines to keep compatible. The cost is a little care in SQL; the benefit is a zero-setup local run.

---

## ADR-16: Hardening After a Code Review

**Context**  
An external review found real failures, not style points: deadlines shown 5½ hours off when the server's time zone differs from the browser's; the sign-in lockout locking *everyone* out behind a proxy; lost updates between concurrent writes (and a drifting order counter); one scheduler thread shared by every timed job; unsafe defaults that could ship; AI prompts logged by default; raw user text in prompts.

**Decisions**
- **Time zones:** keep `LocalDateTime` in the database (no risky data migration), but serialize every timestamp with the server's UTC offset (`JacksonConfig`). The browser converts it. *Alternative considered:* `Instant` end to end. Cleaner, but it would reinterpret existing rows written in local time.
- **Concurrency:** `@Version` on agents, orders and suggestions; a conflict is a 409 for people and a retry-next-tick for monitors. Withdrawing suggestions is a conditional bulk `UPDATE` (idempotent, so two withdrawals don't conflict) that still bumps the version so a racing accept fails. *Alternative considered for the counter:* atomic `UPDATE … SET n = n + 1`. The version check covers it with one mechanism.
- **Client address:** trust `X-Forwarded-For` only as nginx sets it (overwritten, not appended), and keep 8080 off the network in Docker.
- **Threads:** separate pools for re-planning, streams (no queue, 503 when full) and scheduled jobs.
- **Profiles:** `dev` by default; Docker runs `prod`, where `ProductionSafetyCheck` fails startup on the default password, sign-in off or the H2 console. INFO logging by default.
- **API:** response records instead of entities; paged lists; metrics aggregated in SQL; 48-bit collision-checked ids.
- **AI:** typed text cleaned and fenced in the prompt; JSON mode / response schema on the providers; validation stays the guarantee.
- **Console:** re-read only the lists a change event names; "backend unreachable" is no longer treated as "signed out".

**Tradeoffs accepted**  
- Optimistic locking surfaces rare 409s to people instead of silently last-write-wins; the message asks them to try again.
- Gemini's response schema couldn't be verified live (quota exhausted at the time); a rejection would degrade to Groq, not break routing.

---

## Summary Table

| ADR | Topic | Decision | Key point |
|-----|-------|----------|-----------|
| 1 | Routing location | Application service + Strategy | One `route(order, context)` for HTTP and async |
| 2 | Strategy switching | Bean map + `AtomicReference`, `PUT /routing/strategy` | No restart; startup validation; new strategy = new class |
| 3 | LLM resilience | Typed failures, provider chain, validation, labelled fallback | Never silent; source shows what really answered |
| 4 | Loop trigger | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` | Sees committed state; no DB tx across LLM calls |
| 5 | Extensibility | Nullable placeholder columns, context-based contract | Zone strategy and SLA trigger are additive |
| 6 | Frontend | Angular 17 standalone (polling, now push: ADR-13) | Async results appear without a click |
| 7 | Prompts | Routine request vs incident report | Model knows it's recovering and sees the whole batch |
| 8 | Idempotency | Pre-check + re-check under row lock | No duplicates even with concurrent triggers |
| 9 | Checkpoint | Queue, never auto-assign | Accept is atomic; criteria for removing it defined |
| 10 | Offline detection | Heartbeats + scheduled monitor → normal OFFLINE event | Nobody has to notice; ops still decides when they're back |
| 11 | Capacity & zones | Capacity filter for all strategies; zone distance in the score and the AI table | Balanced *and* nearby, nobody silently overloaded |
| 12 | Deadlines | Scheduled SLA monitor, flag once, suggest only if someone is faster | Re-plans before an order is late, not just after a failure |
| 13 | Live updates | SSE "what changed" events from a JPA listener, after commit | Instant, one source of truth, polling only as fallback |
| 14 | Security | Session cookie + CSRF, Basic for scripts, agent token for heartbeats | Works with the event stream; no tokens in JavaScript |
| 15 | Schema | Flyway (idempotent baseline) + PostgreSQL in Docker | Existing databases upgraded in place; no more manual ALTERs |
| 16 | Hardening | Offsets on timestamps, @Version, real client IP, split pools, prod fail-fast, DTOs, paging | Fixes found by review, each a real failure |

---

*Document started:* 2026-09-23  
*Last updated:* 2026-10-07
