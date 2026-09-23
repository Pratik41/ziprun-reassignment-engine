# Architecture Decision Records - ZipRun Reassignment Engine

## Overview
This document captures architectural decisions made during development of the AI-powered order reassignment engine. Each entry follows: **Context → Options → Decision → Tradeoffs**. Decisions are documented as they're made, not retrospectively.

---

## ADR-1: Routing Logic Architecture

**Context**  
The routing engine is called from two distinct places: an HTTP endpoint for on-demand suggestions (`POST /orders/{id}/suggest`) and an async event handler in the agentic re-planning loop when an agent goes offline. Both need access to the same routing strategies without coupling either to the other.

**Options considered**
1. *Put routing in a Service bean* - Inject the service into both controller and event handler. Clean separation, but service becomes a coordination point.
2. *Put routing in a Domain object method* - Make Order or Agent responsible for its own routing. Mixes domain concerns with strategy selection.
3. *Dedicated RoutingService with strategy injection* - Separate class responsible only for routing orchestration and strategy selection.

**Decision**  
Chose a dedicated `RoutingService` bean (option 3). It encapsulates strategy selection logic, is agnostic to callers (HTTP or event), and provides a single place to add LLM timeout handling and fallback logic. This allows the HTTP controller and event handler to use routing without being tightly coupled to each other or to the strategy implementation details.

**Tradeoffs accepted**  
- Adds another layer (RoutingService) beyond the obvious controller/service split
- Requires dependency injection in two places instead of one
- Readers need to understand Spring's bean lifecycle to follow the flow
- Mitigated by keeping RoutingService focused solely on routing concerns

---

## ADR-2: Runtime Strategy Switchability

**Context**  
The system needs to support multiple routing strategies (rule-based, AI-powered, and later zone-aware). The active strategy must be changeable at runtime via configuration without requiring application restart or code changes. This matters for operational flexibility and for the sprint 2 roadmap.

**Options considered**
1. *Spring `@Qualifier` with config property* - Simple, but changing the qualifier requires restart (Spring re-creates beans on config reload only in certain contexts).
2. *Auto-wired `Map<String, RoutingStrategy>` with config lookup* - Spring automatically populates a map of all beans implementing RoutingStrategy, keyed by name. Active strategy selected at runtime by reading config.
3. *Manual factory with switch statement* - Explicit, but requires modifying the factory every time a new strategy is added (violates open/closed principle).
4. *Strategy registry pattern* - Dynamic registration of strategies, but adds registration boilerplate and requires startup discovery logic.

**Decision**  
Chose option 2: auto-wired `Map<String, RoutingStrategy>` bean map. Spring automatically discovers and injects all beans implementing the strategy interface. The active strategy is selected by reading `routing.strategy` from `application.properties` at call time (not bean creation time), allowing runtime changes via environment variables. Both HTTP endpoint and event handler inject the same map and use the same selection logic.

**Tradeoffs accepted**  
- Less explicit than a factory (readers must know Spring auto-populates maps by interface)
- Runtime failures if `routing.strategy` config is misconfigured (mitigated by adding startup validation)
- Adding a new strategy requires two things: implement interface + register bean (not one)
- Benefit: zero changes to existing code when adding sprint 2 strategies

---

## ADR-3: LLM Resilience & Graceful Degradation

**Context**  
The AI routing strategy makes HTTP calls to an external LLM (Gemini/Groq/Ollama). These calls can fail in multiple distinct ways: network timeout, API quota exhaustion, malformed JSON response, hallucinated agent IDs, service unavailable. The system must remain operational when the LLM is broken or slow.

**Options considered**
1. *Fail fast* - If LLM call fails, return error to caller. Simple, but breaks the entire reassignment flow.
2. *Fallback to rule-based immediately* - Any LLM failure → immediately call rule-based strategy. Fast, but loses tracing of why fallback happened.
3. *Exponential backoff retry* - Retry LLM calls with increasing delays. Masks transient issues, but adds latency to responses.
4. *Validate response, fallback on issues* - Attempt LLM call, validate response thoroughly (agent ID exists, confidence in range, JSON parseable), fallback to rule-based if validation fails. Async re-plan failures also produce rule-based suggestion rather than silent drop.

**Decision**  
Chose option 4. Implementation:
- LLM timeout (> 5s) → immediate fallback to rule-based
- Malformed JSON → log error, fallback to rule-based
- Hallucinated agent ID → validate against roster before persisting, fallback if invalid
- In async re-plan context: LLM failure → create rule-based suggestion anyway (never silent drop)
- All failures logged with context (order ID, strategy attempted, error type)

**Tradeoffs accepted**  
- Adds validation logic (extra code, potential for bugs in validator)
- Rule-based fallback may not be optimal for the specific order, but it's better than nothing
- Logging adds overhead, but necessary for debugging in production
- Never fully silent failures - ops always sees some recommendation

---

## ADR-4: Agentic Loop Trigger Mechanism

**Context**  
When an agent goes OFFLINE, the system must identify affected orders and queue reassignment suggestions asynchronously (without blocking the HTTP response). The `PATCH /agents/{id}/status` endpoint must return immediately. Elsewhere, a background process picks up the status change and runs routing on affected orders.

**Options considered**
1. *Scheduled polling* - A `@Scheduled` job runs every N seconds, queries for OFFLINE agents, and re-plans. Simple, but not event-driven and can miss rapid state changes.
2. *Spring `@EventListener` + `@Async`* - Publish a domain event when agent status changes, listen for it in a separate method annotated with `@Async`. Clean, Spring native.
3. *ApplicationEventPublisher* - Explicit event publishing, with decoupled listener. More verbose than option 2, but same effect.
4. *Manual thread pool executor* - Create a thread pool, submit re-plan tasks to it. Full control, but requires manual error handling and monitoring.

**Decision**  
Chose option 2: Spring `@EventListener` + `@Async`. When an agent's status changes to OFFLINE, the Agent entity publishes a `AgentOfflineEvent`. A separate `ReplanEventHandler` listens and is invoked asynchronously on a thread pool. This keeps the HTTP path fast, decouples agent domain logic from re-planning logic, and integrates with Spring's async/monitoring infrastructure.

Implementation details:
- Agent status change → publish event
- Event handler queries affected orders (where assigned_agent = OFFLINE_AGENT and status = ASSIGNED)
- For each order: check if PENDING suggestion with trigger=AGENT_OFFLINE exists (idempotency check)
- Call routing strategy on each stranded order
- Persist ReassignmentSuggestion with trigger=AGENT_OFFLINE

**Tradeoffs accepted**  
- Adds complexity (event publishing, separate listener)
- Async failures may not be visible to caller (mitigated by comprehensive logging)
- Event handler must handle cases where agent/order state changed during async processing
- Benefit: HTTP endpoint fast, re-planning doesn't block ops

---

## ADR-5: Extensibility & Deliberate Exclusions

**Context**  
This is sprint 1 of a multi-sprint roadmap. Sprint 2 introduces zone awareness, capacity constraints, and weight classes. Sprint 3 adds proactive re-planning (SLA deadlines) and a full dispatch board. Design must accommodate these without structural rework, but also avoid premature abstraction.

### Extensibility: Where Sprint 2 Plugs In

**Zone-Aware Routing (Sprint 2)**
- Current: Order has pickup/dropoff description text
- Future: Order has `pickupZone`, `dropoffZone` fields (nullable, placed in schema now)
- Future: Agent has `currentZone` field (nullable, placed in schema now)
- Future: New `ZoneAffinityStrategy` implements `RoutingStrategy`
- Activation: Add bean, set `routing.strategy=zone-aware` in config

*Where it lives in current code:*
- Schema: Order.java line ~30 (pickupZone field, marked @Nullable for future)
- Schema: Agent.java line ~20 (currentZone field, marked @Nullable for future)
- Strategy interface: RoutingStrategy.java (already generic enough)

**SLA-Driven Re-Planning (Sprint 3)**
- Current: Re-planning triggered only on OFFLINE event
- Future: Re-planning also triggered when order approaches SLA deadline
- Implementation: Create `OrderApproachingSLAEvent`, publish from scheduled monitor
- Same event handler mechanism handles both trigger types

*Where it lives in current code:*
- ReplanEventHandler.java: `handleOfflineEvent()` is already trigger-agnostic; can add `handleSLAEvent()` without changes to existing logic
- Event interface: Generic enough to support multiple event types

### Deliberate Exclusions

**What's NOT in sprint 1, and why:**

1. **Full Dispatch Board** (Sprint 3, +8 pts ceiling)
   - Current floor UI shows only REASSIGNMENT_PENDING orders
   - Full board shows all statuses, real-time agent load, zone map
   - *Why deferred:* Agentic loop correctness is the must-have. A gorgeous UI that costs you the core event-driven loop isn't a win.
   - *When to build:* Sprint 3, after zone/capacity logic stabilizes

2. **SLA Deadline Tracking** (Sprint 3)
   - Current: Orders have no deadline field
   - Future: Orders get `slaDeadline` field, proactive re-planning when approaching breach
   - *Why deferred:* Reactive re-planning (on OFFLINE) is the immediate requirement. Proactive re-planning is an enhancement.
   - *When to build:* Sprint 3, after ops validates reactive loop works at scale

3. **Priority/Tier System** (Sprint 3)
   - *Why deferred:* Not mentioned in current requirements. Adds complexity to routing without immediate business need.

4. **Traffic/Weather Integration** (Sprint 3 future)
   - *Why deferred:* Requires external data sources, adds latency to routing calls. Start with agent capacity and zone, validate the model works, then add external tools.

5. **Multi-Agent Recommendation** (Current: returns one agent per order)
   - *Why deferred:* One recommendation per order is clearer for ops. Future: return ranked list of options. Current design doesn't prevent this; adding it later is additive.

---

## ADR-6: Frontend Framework Choice

**Context**  
The ops interface needs to display reassignment suggestions, accept/reject controls, agent status, and a re-plan badge showing when suggestions came from the agentic loop. Framework choices: React 18 or Angular 17.

**Options considered**
1. *React 18 + Vite* - Lighter, faster dev server, simpler mental model, large ecosystem
2. *Angular 17 + standalone API* - More opinionated structure, built-in patterns, better for large teams

**Decision**  
Chose React 18 + Vite. Reasons:
- Faster iteration (dev server startup in ms, not seconds)
- Smaller learning curve for solo hackathon
- Vite's HMR (hot module reloading) excellent for UI tweaking
- Hooks-based state management easier to trace than Angular's dependency injection

**Tradeoffs accepted**  
- No built-in form validation (need to add or use library)
- Fewer conventions (more decisions to make on file structure)
- Smaller default framework (more choice of libraries needed)
- Benefit: Faster to prototype and iterate on UI

---

## ADR-7: Initial vs Re-Plan Prompts (Why They Must Differ)

**Context**  
The AI routing strategy sends structured prompts to an LLM and expects back: recommended agent, confidence score, reasoning. There are two scenarios:
1. **Initial assignment:** Normal routing - which of these available agents should take this order?
2. **Re-plan (agent offline):** Recovery routing - an agent failed, these orders are now stranded, who should take them?

**Options considered**
1. *Single prompt with a flag* - One template, vary a single field: `{ scenario: "initial" | "recovery" }`
2. *Separate prompts with different context* - Write two distinct prompts, each tailored to its scenario

**Decision**  
Chose option 2: separate, context-rich prompts.

**Initial Prompt Example:**
```
Order: Electronics delivery, Koramangala → Indiranagar
Available agents:
- Priya (2 active orders, BUSY)
- Rahul (0 active orders, AVAILABLE) ← Good fit
- Ananya (1 active order, BUSY)

Recommend the best agent and explain why.
Return JSON: {"agentId": "...", "confidence": 0.9, "reasoning": "..."}
```

**Re-Plan Prompt Example:**
```
RECOVERY MODE: AGENT OFFLINE EVENT

Agent AGT-001 (Priya) just went offline.
Orders now STRANDED (were assigned to Priya):
- ORD-001: Electronics, Koramangala → Indiranagar
- ORD-002: Groceries, HSR Layout → BTM

Available agents for reassignment:
- Rahul (0 active orders, AVAILABLE)
- Ananya (1 active order, BUSY)
- Deepak (3 active orders, BUSY)

For each stranded order, recommend a reassignment agent. 
Consider: Which agent can most quickly absorb these orders?
Return JSON array: [{"orderId": "ORD-001", "agentId": "...", ...}, ...]
```

**Why different?** The model must understand it's in **recovery mode**, not normal routing:
- Tone: "We have an emergency, fix it" vs "Assign this normally"
- Context: "This agent failed; these orders are stranded" vs "New order arrived"
- Constraint: "Previous assignments to Priya are void" vs "Respect current assignments"

Same prompt template means the model treats recovery like a normal assignment, leading to hallucinations or suboptimal decisions.

**Tradeoffs accepted**  
- Maintains two prompts (doubles the code paths to test)
- Requires two validation paths (different parsing per scenario)
- Benefit: Model reasons correctly about each scenario

---

## ADR-8: Idempotency in Agentic Re-Planning

**Context**  
When an agent goes offline, the system queries for affected orders and creates ReassignmentSuggestion records. In edge cases (rapid status changes, retry logic), the same event might fire multiple times or overlapping events might affect the same order.

**Scenario:** Agent AGT-001 goes offline (event fires, creates suggestions for ORD-001, ORD-002). Five seconds later, AGT-001 goes offline again due to a retry or network glitch. Without idempotency, we'd create duplicate suggestions.

**Options considered**
1. *Don't worry about it* - Simple, but ops sees duplicate suggestions
2. *Lock during re-plan* - Acquire a lock on the agent, process all orders, release lock. Prevents duplicates but adds complexity.
3. *Check before inserting* - Before creating a suggestion, query: does PENDING suggestion with trigger=AGENT_OFFLINE already exist for this order?

**Decision**  
Chose option 3. Implementation:
```sql
SELECT * FROM reassignment_suggestions 
WHERE order_id = ? 
  AND status = 'PENDING' 
  AND trigger_reason = 'AGENT_OFFLINE'
```
If exists, skip creating a new suggestion. This is idempotent: calling the re-plan handler twice produces the same result (one suggestion).

**Tradeoffs accepted**  
- Adds a query per order (N+1 risk, mitigated by indexed queries)
- "Skip if already pending" means quick re-plans don't overwrite slow ones (OK; don't overwrite someone's pending decision)
- Benefit: Clean idempotency without locks

---

## Summary Table

| ADR | Topic | Decision | Key Insight |
|-----|-------|----------|------------|
| 1 | Routing Architecture | Dedicated RoutingService | Decouple HTTP and event handlers |
| 2 | Strategy Switchability | Map bean + config lookup | No restart needed for new strategies |
| 3 | LLM Resilience | Validate → fallback | Never silent failures; always show suggestion |
| 4 | Agentic Loop Trigger | @EventListener + @Async | Fast HTTP, async re-planning |
| 5 | Extensibility | Nullable fields for future, strategy interface open | Sprint 2/3 additions are additive |
| 6 | Frontend Framework | React 18 + Vite | Speed and iteration over convention |
| 7 | Two Prompts | Separate context-rich prompts | Model must understand recovery mode |
| 8 | Idempotency | Check before insert | No duplicate suggestions |

---

## Next Steps / Open Questions for Walkthrough

1. **Why not auto-assign high-confidence suggestions?** 
   - Answer: Intentional human checkpoint. By sprint 3, conditional auto-assign based on urgency is reasonable.

2. **How do you measure success of the agentic loop?**
   - Answer: Suggestions created within N seconds of agent going offline, zero missed orders, zero duplicates.

3. **What's the most likely failure mode in production?**
   - Answer: LLM quota exhaustion; fallback to rule-based handles it, but ops sees degradation in reasoning quality.

4. **How does zone-aware strategy plug in?**
   - Answer: New bean implementing RoutingStrategy, registered with Spring, selected by config. Zero changes to existing code.

---

*Document started:* 2026-09-23  
*Last updated:* 2026-09-23
