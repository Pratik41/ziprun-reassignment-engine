# How ZipRun works

This guide explains the whole system: what happens when an agent's status changes or an order is about to be late, how the **rule-based** and **AI** strategies each pick an agent (load, capacity and location), what protects the AI path from bad answers, how the console stays live and signed in, and the rules that keep the data consistent. Diagrams are drawn with Mermaid, which GitHub renders automatically.

Code paths are relative to `backend/reassignment-engine/src/main/java/com/ziprun/`.

**Contents**
1. [The big picture](#1-the-big-picture)
2. [The core idea: suggestions, not assignments](#2-the-core-idea-suggestions-not-assignments)
3. [The agentic loop: what happens when an agent's status changes](#3-the-agentic-loop)
4. [How an agent is chosen: the routing pipeline](#4-how-an-agent-is-chosen-the-routing-pipeline)
5. [Rule-based strategy in detail](#5-rule-based-strategy-in-detail)
6. [AI strategy in detail](#6-ai-strategy-in-detail)
7. [Saving a suggestion safely](#7-saving-a-suggestion-safely)
8. [The ops decisions: accept, reject, reassign, keep](#8-the-ops-decisions)
9. [Lifecycles: orders, suggestions, agents](#9-lifecycles)
10. [Live reasoning (SSE streaming)](#10-live-reasoning-sse-streaming)
11. [Fleet rules and guardrails](#11-fleet-rules-and-guardrails)
12. [Things you might not know](#12-things-you-might-not-know)
13. [Known limitations](#13-known-limitations)
14. [Troubleshooting](#14-troubleshooting)
15. [History and metrics (Insights)](#15-history-and-metrics-insights)
16. [Capacity, zones and delivery deadlines](#16-capacity-zones-and-delivery-deadlines)
17. [Live updates](#17-live-updates)
18. [Sign-in and security](#18-sign-in-and-security)
19. [The database and migrations](#19-the-database-and-migrations)

---

## 1. The big picture

```mermaid
flowchart LR
    subgraph UI["Angular UI (localhost:4200)"]
        QP["Queue<br/>KPIs, waiting orders,<br/>accept / reject / reassign / keep"]
        FP["Fleet panel + Fleet page<br/>agent status controls"]
        OP["Orders page + New order<br/>routing toggle in the top bar"]
    end

    subgraph API["Spring Boot backend (localhost:8080)"]
        C["REST controllers"]
        S["Services<br/>Agent / Order / Suggestion"]
        EV(["Domain events<br/>Offline / Busy / Available"])
        H["ReplanEventHandler<br/>(the agentic loop)"]
        SM["SlaMonitor<br/>(deadlines, every 30 s)"]
        LU["LiveUpdates<br/>GET /events"]
        R["RoutingService"]
        RB["Rule-based strategy"]
        AI["AI strategy"]
        GW["LLMGateway"]
    end

    DB[("Database<br/>PostgreSQL (Docker) / H2 (local)<br/>agents, orders, suggestions")]
    G["Gemini"]
    Q["Groq"]

    UI -- "HTTP /api (signed in)" --> C
    LU -. "change events (pushed)" .-> UI
    C --> S
    S --> DB
    DB -. "after commit" .-> LU
    SM --> R
    S -- "status change" --> EV
    EV -- "after commit, async" --> H
    H --> R
    C -- "Get suggestion" --> R
    R --> RB
    R --> AI
    AI --> GW
    GW -- "1st" --> G
    GW -- "2nd if 1st fails" --> Q
    H --> S
```

Three moving parts:

| Part | Job |
|---|---|
| **Services** | Own the data and its rules: order state machine, agent load, suggestion lifecycle. |
| **Agentic loop** (`service/event/ReplanEventHandler`) | Reacts to agent status changes in the background and decides which orders need a new suggestion. |
| **Routing** (`routing/*`) | Answers one question for one order: *"which agent should take this?"*, using the active strategy. |

The UI never talks to the AI directly. It only reads data and sends ops decisions; everything else happens on the server.

---

## 2. The core idea: suggestions, not assignments

The system **never moves an order on its own**. It creates a **suggestion**: a recommended agent, a confidence score (0–1), a plain-English reason, and a tag showing what produced it. A human then accepts or rejects it.

```mermaid
flowchart LR
    A["Something changes<br/>(agent goes offline)"] --> B["System re-plans<br/>in the background"]
    B --> C["PENDING suggestion<br/>agent + confidence + reasoning"]
    C --> D{"Ops decides"}
    D -- "Accept" --> E["Order moves to the<br/>recommended agent"]
    D -- "Reject" --> F["Order keeps waiting<br/>(ask again or reassign)"]
    D -- "Reassign manually" --> G["Order moves to the<br/>agent ops picked"]
```

Why: a wrong automatic reassignment (sending an order to someone who can't take it) costs more than the minute it takes a person to click Accept. ADR-9 lists the conditions under which auto-accept could be allowed later.

Each suggestion records:

| Field | Example | Meaning |
|---|---|---|
| `recommendedAgentId` | `AGT-004` | who should take the order |
| `confidence` | `0.80` | how clear-cut the choice is |
| `reasoning` | "Kiran Nair has 0 active orders…" | shown to ops word for word |
| `triggerReason` | `AGENT_OFFLINE` / `INITIAL` | created by the loop (**⚡ Auto re-plan** tag) or by "Get suggestion" (**Requested**) |
| `source` | `ai:gemini`, `rule-based`, `rule-based (AI fallback: TIMEOUT)` | what *actually* produced it |
| `status` | `PENDING` → `ACCEPTED` / `REJECTED` / `EXPIRED` | see [section 9](#9-lifecycles) |

---

## 3. The agentic loop

Every agent status change goes through `AgentServiceImpl.updateStatus`, which saves the new status and publishes an **event**. The loop handles the event **after the database commit, on a background thread**, so the button click returns immediately.

### Which change fires which event

| Change | Event | What the loop does |
|---|---|---|
| any → `OFFLINE` | `AgentOfflineEvent` | Their orders are stranded: mark them `REASSIGNMENT_PENDING` and re-plan them. Withdraw open suggestions *recommending* them and re-plan those orders too. |
| `AVAILABLE` → `BUSY` | `AgentBusyEvent` | Their own orders stay with them (they're still delivering). Withdraw open suggestions recommending them and re-plan those orders. |
| `BUSY`/`OFFLINE` → `AVAILABLE` | `AgentAvailableEvent` | More capacity: withdraw the open suggestions of **every** waiting order and re-plan all of them, so they spread across the bigger roster. |
| `OFFLINE` → `BUSY` | none | The agent wasn't taking orders before and still isn't; nothing to re-plan. |

### Automatic offline detection (heartbeats)

A person doesn't have to notice that someone dropped out. Each agent's phone app calls `POST /agents/{id}/heartbeat` every few seconds. A background check (`service/agent/HeartbeatMonitor`, every 5 s) looks for on-duty agents whose last heartbeat is older than `AGENTS_HEARTBEAT_TIMEOUT_SECONDS` (default 60) and marks them `OFFLINE`. That publishes the same `AgentOfflineEvent`, so everything below happens exactly as if ops had clicked Offline.

```mermaid
flowchart LR
    APP["Agent's phone app"] -- "heartbeat every ~10s" --> API["POST /agents/{id}/heartbeat<br/>lastHeartbeatAt = now"]
    MON["HeartbeatMonitor<br/>every 5s"] --> Q{"On duty and silent<br/>for over 60s?"}
    Q -- "no" --> MON
    Q -- "yes" --> OFF["Mark OFFLINE<br/>note: 'Auto-offline: no heartbeat since …'"]
    OFF --> EV(["AgentOfflineEvent<br/>(same loop as a manual Offline)"])
```

- **Only agents whose app has reported are monitored.** An agent with no `lastHeartbeatAt` is never auto-offlined, so agents managed by hand are unaffected.
- **It's a fact, not a request,** so the "keep one agent Available" guardrail doesn't block it.
- **The app reconnecting doesn't put them back on duty.** The note changes to "App reconnected…", but whether they're back on shift is ops' decision.
- **A manual status change wins.** It clears the note and pauses monitoring until the app sends its next heartbeat, so a stale heartbeat can't immediately flip the agent back.
- **Try it:** on the Fleet page, **Connect app** simulates an agent's phone (a heartbeat every 10 s from your browser). **Disconnect app** and watch them go Offline about a minute later.

### Step by step: an agent goes offline

```mermaid
sequenceDiagram
    autonumber
    actor Ops
    participant UI as Angular UI
    participant API as AgentController / AgentService
    participant DB as Database
    participant Handler as ReplanEventHandler
    participant Route as RoutingService
    participant Sug as SuggestionService

    Ops->>UI: Set Priya to Offline
    UI->>API: PATCH /agents/AGT-001/status OFFLINE
    API->>DB: check another agent stays AVAILABLE, save status
    API-->>UI: 200 OK (returns immediately)
    Note over API,Handler: after the commit, AgentOfflineEvent runs on a background thread
    Handler->>DB: find Priya's active orders (ORD-001, 002, 008)
    Handler->>Sug: withdraw suggestions that recommend Priya
    Handler->>DB: mark orders REASSIGNMENT_PENDING
    loop each stranded order, one at a time
        Handler->>Sug: already has a pending re-plan suggestion?
        alt no
            Handler->>Route: route(order, recovery context)
            Route-->>Handler: best agent + confidence + reasoning
            Handler->>Sug: save suggestion if still needed (row lock)
        else yes
            Handler->>Handler: skip (idempotent)
        end
    end
    API-->>UI: change event (GET /events), the UI re-reads
    Note over UI: new suggestions appear with the Auto re-plan tag
    Ops->>UI: Accept
```

Things worth noticing:

- **The click is never slowed down by the AI.** The PATCH returns before any AI call starts.
- **Orders are processed one at a time.** Each saved suggestion counts toward the next order's routing (see *effective load* in [section 5](#5-rule-based-strategy-in-detail)), which is how a batch gets spread out.
- **Each step is its own small database transaction.** An 8-second AI call never holds a database connection, and one failing order can't undo the others.
- **Running twice is harmless.** An order that already has a pending re-plan suggestion is skipped.
- **The AI is told *who* stranded each order.** Orders are grouped by their assigned agent, so the incident report names the right person even when the re-plan was triggered by someone else becoming Busy or Available.

---

## 4. How an agent is chosen: the routing pipeline

Every caller uses the same pipeline (`RoutingService.rank(order, context)`):
- the agentic loop, with a **recovery** context (who failed, the whole stranded batch)
- the "Get suggestion" button / `POST /orders/{id}/suggest`, with an **initial** context
- the **New order** dialog / `POST /routing/recommend`, with an initial context and a draft order that isn't saved (see below)
- the **SLA monitor**, with a deadline-risk context ([section 16](#16-capacity-zones-and-delivery-deadlines))

```mermaid
flowchart TD
    START(["route(order, context)"]) --> CAND["Candidates = AVAILABLE agents<br/>minus the order's own agent"]
    CAND --> LOAD["Add pending load:<br/>count PENDING suggestions per agent"]
    LOAD --> CAP{"Anyone below<br/>capacity?"}
    CAP -- "yes" --> KEEP["Leave out agents<br/>at capacity"]
    CAP -- "no, all full" --> ALL["Keep everyone,<br/>flag 'over capacity'"]
    KEEP --> WHICH{"Active strategy?<br/>(toggle in the UI)"}
    ALL --> WHICH
    WHICH -- "rule-based" --> RB["Rule-based:<br/>rank by load + zone distance"]
    WHICH -- "ai" --> AI["AI strategy<br/>(section 6)"]
    AI -- "valid answer" --> OUT
    AI -- "any failure" --> RB2["Rule-based fallback<br/>source = rule-based (AI fallback: KIND)"]
    RB --> OUT(["Ranked list, best first"])
    RB2 --> OUT
    WHICH -.->|"strategy crashes (safety net)"| RB3["Rule-based fallback<br/>source = rule-based (fallback: X failed)"]
    RB3 --> OUT
    OUT --> EMPTY{"Empty?"}
    EMPTY -- "yes" --> NONE["No suggestion:<br/>'no available agent'"]
    EMPTY -- "no" --> SAVE["Save the top result<br/>(section 7)"]
```

- **Candidates** are only `AVAILABLE` agents. Busy agents are still delivering and don't take more; Offline agents can't.
- **The order's own agent is excluded**, because "reassign to the same person" isn't a reassignment. That's why an order whose agent is back shows **"Keep with …"** instead.
- **Agents at capacity are left out** (effective load ≥ their capacity). The reasoning names who was skipped. If *everyone* is full, nobody is left out (the order still needs someone) but the suggestion is flagged, see the table below.
- **Switching strategy** (`PUT /routing/strategy` or the toggle) affects the next routing call on both paths. The choice is saved in the database (`app_settings`) and restored on restart; `ROUTING_STRATEGY` is only the first-run default. Existing suggestions keep the tag of whatever made them.
- **Confidence guardrail.** Whatever the strategy says, `RoutingService` caps confidence when the roster can't support certainty:

  | Situation | Max confidence | Extra reasoning |
  |---|---|---|
  | Re-plan with fewer available agents than stranded orders ("thin roster") | 0.60 | "Thin roster: N stranded order(s) but only M available agent(s); consider making more agents available." |
  | Only one candidate (nothing to compare against) | 0.75 | none |
  | Every available agent is at capacity | 0.40 | "Over capacity: every available agent is at their limit (…); this would overload them. Make more agents available or raise a capacity." |
  | Some agents left out for being full | unchanged | "Skipped (at capacity): Amit Patel 6/6." |

  This was added after the AI gave 0.95 confidence to the only available agent, who would have ended up with 8 orders.
- **Timing.** Each routing call is timed, including any AI calls, and the time is stored on the suggestion (`routingMillis`) for the Insights page.

### Recommending an agent for a new order

The New order dialog doesn't make ops guess who has room. When it opens, and again after a pause in typing the description (0.8 s), it calls `POST /routing/recommend`:

```mermaid
sequenceDiagram
    participant UI as New order dialog
    participant API as RoutingController
    participant RS as RoutingService
    participant S as Active strategy
    UI->>API: POST /routing/recommend {description}
    API->>RS: recommendForNewOrder(description, 3)
    RS->>S: rank(draft order, initial context)
    S-->>RS: ranked agents (same guardrails and fallback)
    RS-->>UI: top 3: agent, confidence, reasoning, source
    Note over UI: best pick pre-selected,<br/>ops can choose anyone Available
    UI->>API: POST /orders {description, assignedAgentId, recommendedAgentId}
    Note over API: order stores recommendedAgentId<br/>+ followedRecommendation
```

- It is the **same pipeline**: the same candidates, effective load (active + queued suggestions), strategy, AI validation, rule-based fallback and confidence caps. Only the order is a draft with no id and no agent, and **nothing is saved**.
- With **rule-based**, the lightest effective load comes first. With **AI**, the model also weighs the description (fragile, perishable, documents) but is told to prefer the lowest effective load.
- A slower answer never overwrites a newer one: typing again cancels the request in flight. Once ops picks an agent themselves, new answers don't move that choice.
- On create, the order records which agent was recommended and whether ops followed it. Insights shows this as **Recommended pick used** (followed / orders created with a recommendation), and the activity log says "(recommended pick)" or "(recommendation was …)".

---

## 5. Rule-based strategy in detail

`routing/strategy/RuleBasedStrategy.java`. Deterministic: same inputs, same answer, no network.

### The rule

> **Pick the candidate with the lowest *score*. Break ties by load, then agent ID.**

```
effective load = active orders (orders they are carrying now)
               + pending suggestions already recommending them

score = effective load + zone penalty
zone penalty   = 0 already in the pickup zone
               + 1 in a neighbouring zone
               + 2 location unknown
               + 3 further away
               (0 for everyone when the order has no pickup zone)
```

Counting pending suggestions matters. Without it, every stranded order in a batch would go to the same "least loaded" agent, because nothing has been accepted yet. The zone penalty is in "orders": a nearby agent with one more order beats a far one, but an agent across town still wins if everyone nearby is swamped. Agents at capacity never get here; `RoutingService` already left them out ([section 4](#4-how-an-agent-is-chosen-the-routing-pipeline)).

### Confidence

```mermaid
flowchart TD
    T{"How many candidates?"} -- "1" --> C70["0.70<br/>no alternative to compare"]
    T -- "2 or more" --> GAP{"Gap between best and<br/>runner-up score"}
    GAP -- "0 (tie)" --> C60["0.60<br/>picked by agent ID"]
    GAP -- "1, 2, 3, 4+" --> CG["0.80, 0.85, 0.90, 0.95<br/>(0.75 + 0.05 x gap, max 0.95)"]
```

Lower-ranked agents get `0.50 / (1 + how much worse their score is than the best)`, so a runner-up at +1 scores 0.25. `RoutingService` then applies the confidence caps (thin roster, single candidate, everyone full).

### Worked example (the sample data)

Priya (AGT-001) goes offline holding ORD-001 (pickup Koramangala), ORD-002 (HSR Layout) and ORD-008 (Peenya). Rahul (AGT-002, in HSR Layout) and Kiran (AGT-004, in Malleshwaram) are Available with 0 orders each.

| Order | Rahul: load + distance | Kiran: load + distance | Pick | Why |
|---|---|---|---|---|
| ORD-001 (Koramangala) | 0 + 1 neighbour = **1** | 0 + 3 far = 3 | **Rahul** | next door |
| ORD-002 (HSR Layout) | **1 pending** + 0 same zone = **1** | 0 + 3 far = 3 | **Rahul** | already there, even with one queued |
| ORD-008 (Peenya) | 2 pending + 3 far = 5 | 0 + 3 far = **3** | **Kiran** | both far, Kiran is free |

Result: Rahul 2, Kiran 1. Each pick wins by 2 points, which the strategy rates 0.85, but three stranded orders with only two available agents is a thin roster, so every suggestion is capped at 0.60 and says so. The reasoning ops sees, for ORD-008:

> Recovery: Priya Sharma went offline leaving 3 stranded order(s). Kiran Nair has 0 active orders (0/6 of capacity), in Malleshwaram, away from Peenya. Best on load and distance of 2 available agents; next best is Rahul Verma with 0 active orders + 2 pending suggestions (2/6 of capacity), in HSR Layout, away from Peenya. Thin roster: 3 stranded order(s) but only 2 available agent(s); consider making more agents available.

### When to use it

- The AI is down, slow or out of quota.
- You want instant, predictable, explainable results.
- It's also the fallback for every AI failure, so it must always work.

---

## 6. AI strategy in detail

`routing/strategy/AIRoutingStrategy.java` → `service/ai/AIAdvisorService.java` → `service/ai/PromptBuilder.java` → `routing/gateway/LLMGateway.java`.

```mermaid
flowchart TD
    A(["AI strategy: recommend(order, candidates, context)"]) --> P{"Context?"}
    P -- "initial<br/>(Get suggestion)" --> P1["Initial prompt:<br/>routine request, balance the fleet"]
    P -- "recovery<br/>(agentic loop)" --> P2["Re-plan prompt:<br/>incident report"]
    P1 --> GW
    P2 --> GW
    GW["LLMGateway: try providers in order"] --> GEM{"Gemini"}
    GEM -- "answer" --> PARSE
    GEM -- "no key / timeout / 429 / 503 / empty" --> GRQ{"Groq"}
    GRQ -- "answer" --> PARSE
    GRQ -- "fails too" --> FB
    PARSE["Parse JSON<br/>(tolerates fences, prose, agentId or agent_id)"] -- "not JSON" --> FB
    PARSE --> VAL["Validate each recommendation"]
    VAL --> V1{"agent_id is in the<br/>roster we sent?"}
    V1 -- "no: made up" --> DROP["drop it"]
    V1 -- "yes" --> V2{"0 ≤ confidence ≤ 1<br/>and reasoning present?"}
    V2 -- "no" --> DROP
    V2 -- "yes" --> KEEP["keep it<br/>source = ai:gemini / ai:groq"]
    DROP --> ANY{"Anything left?"}
    KEEP --> ANY
    ANY -- "yes" --> OK(["Ranked list"])
    ANY -- "no" --> FB["Rule-based fallback<br/>source = rule-based (AI fallback: KIND)"]
    FB --> OK
```

### Two different prompts

The AI gets a different brief depending on the situation (ADR-7). Both share the same agent table and the same JSON answer format.

| | Initial prompt | Re-plan prompt |
|---|---|---|
| Used by | "Get suggestion" / `POST /orders/{id}/suggest` | The agentic loop |
| Framing | "routine assignment request, nothing has failed" | "This is a RECOVERY situation" + incident report |
| Extra facts | the order | who went offline, that their orders are void, **every stranded order in the batch** with the current one marked |
| Priorities | lowest effective load; use the description (fragile, perishable…) | speed; **spread the batch**; **warn about a thin roster** and lower confidence |

The agent table the AI receives looks like this (the same numbers rule-based uses):

```
| id      | name        | active | pending | effective |
|---------|-------------|--------|---------|-----------|
| AGT-002 | Rahul Verma | 0      | 1       | 1         |
| AGT-004 | Kiran Nair  | 0      | 0       | 0         |
```

And it must answer with JSON only:

```json
{"recommendations":[
  {"agent_id":"AGT-004","confidence":0.85,
   "reasoning":"Recovery from Priya Sharma going offline. Kiran Nair has the lowest effective load (0)..."}
]}
```

### The provider chain

`llm.providers=gemini,groq` (env `LLM_PROVIDERS`). Each provider has a 20-second timeout (`LLM_TIMEOUT_MS`).

| What went wrong | Kind | What happens |
|---|---|---|
| Provider has no API key | `NOT_CONFIGURED` | skip to the next provider |
| Provider paused by its circuit breaker (see below) | (skipped) | next provider; `CIRCUIT_OPEN` if every provider is paused |
| No answer in time | `TIMEOUT` | next provider |
| Quota exhausted (HTTP 429) | `RATE_LIMITED` | next provider |
| Other HTTP error (Gemini 503 "overloaded" is common) | `HTTP_ERROR` | next provider |
| Empty reply | `EMPTY_RESPONSE` | next provider |
| Reply isn't the JSON we asked for | `UNPARSEABLE` | rule-based fallback |
| Every recommended agent is made up | `HALLUCINATED_AGENT` | rule-based fallback |
| Values unusable (confidence 1.7, blank reasoning) | `INVALID_RESPONSE` | rule-based fallback |

Transport problems try the next provider. Content problems go straight to rule-based, because a second model's opinion doesn't fix something we can check ourselves. Whatever happens, ops gets a suggestion, and its `source` says honestly where it came from.

Typical speed: Gemini 5–9 s, Groq under 1 s, rule-based instant.

### Circuit breaker: don't wait on a provider that keeps failing

Without it, a provider that hangs would cost **every** AI call its full 20-second timeout before the next provider is tried. Each provider has its own breaker:

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> CLOSED: success (failure count reset)
    CLOSED --> OPEN: 2 failures in a row
    OPEN --> HALF_OPEN: 5 minutes pass
    HALF_OPEN --> CLOSED: trial call succeeds
    HALF_OPEN --> OPEN: trial call fails
```

- **CLOSED:** used normally. **OPEN:** skipped; the call goes straight to the next provider. **HALF_OPEN:** the next call is a trial.
- Only transport failures (timeout, 429, HTTP error, empty reply) count. A bad answer isn't the provider being down, so it doesn't pause it.
- If every provider is paused, the AI strategy fails fast with `CIRCUIT_OPEN` and rule-based answers, labelled `rule-based (AI fallback: CIRCUIT_OPEN)`.
- `GET /routing/providers` shows each provider's state, failure count, last error and when a pause ends. Tune with `LLM_CIRCUIT_FAILURE_THRESHOLD` and `LLM_CIRCUIT_COOLDOWN_SECONDS`.

---

## 7. Saving a suggestion safely

The AI can take seconds, and the world can change meanwhile. Two checks run at save time, inside a short transaction (`service/suggestion/SuggestionServiceImpl`):

```mermaid
flowchart TD
    R(["Routing returned: Kiran"]) --> L["Lock the order row<br/>(SELECT ... FOR UPDATE)"]
    L --> S1{"Order still<br/>REASSIGNMENT_PENDING?"}
    S1 -- "no: ops already kept,<br/>reassigned or accepted" --> D1["Drop it quietly"]
    S1 -- "yes" --> S2{"Already has a pending<br/>re-plan suggestion?"}
    S2 -- "yes" --> D2["Skip (idempotent)"]
    S2 -- "no" --> S3{"Is Kiran still<br/>AVAILABLE?"}
    S3 -- "no: went Busy/Offline<br/>while the AI was thinking" --> RR["Route once more"]
    S3 -- "yes" --> OK(["Save PENDING suggestion"])
```

The row lock means two background runs touching the same order can't both insert a suggestion. One waits, then sees the other's.

---

## 8. The ops decisions

| Action | Where in the UI | What happens | Refused when |
|---|---|---|---|
| **Accept** | suggestion card | Order → `REASSIGNED` to the recommended agent; old agent −1 order, new agent +1; other open suggestions for that order → `REJECTED`. All in one transaction. | recommended agent is no longer `AVAILABLE` |
| **Reject** | suggestion card | Suggestion → `REJECTED`; the order keeps waiting. Ask again with **Get suggestion**, or reassign manually. | already decided |
| **Get suggestion** | waiting order with no open suggestion | Routes with the *initial* prompt, streams the reasoning live, saves a "Requested" suggestion. | no `AVAILABLE` candidate |
| **Reassign manually** | any waiting order | Moves the order to the agent ops picked; its open suggestions → `EXPIRED`. | agent not `AVAILABLE`, or it's the same agent |
| **Keep with <agent>** | waiting order whose own agent is `AVAILABLE` again | Order → back to `ASSIGNED` with them; open suggestions → `EXPIRED`. | that agent isn't `AVAILABLE` |
| **Mark delivered** | Orders page, on Assigned/Reassigned orders | Order → `DELIVERED`; its agent gets one fewer active order. | order is still waiting for an agent |

The dropdowns only list agents the server would accept, so a refusal usually means something changed in the last few seconds.

---

## 9. Lifecycles

### Order

```mermaid
stateDiagram-v2
    [*] --> ASSIGNED : created for an AVAILABLE agent
    ASSIGNED --> REASSIGNMENT_PENDING : agent goes OFFLINE
    ASSIGNED --> REASSIGNED : accept a manual suggestion / reassign
    ASSIGNED --> DELIVERED
    REASSIGNMENT_PENDING --> REASSIGNED : accept / reassign manually
    REASSIGNMENT_PENDING --> ASSIGNED : Keep with original agent
    REASSIGNED --> REASSIGNMENT_PENDING : new agent goes OFFLINE too
    REASSIGNED --> REASSIGNED : reassigned again
    REASSIGNED --> DELIVERED
    DELIVERED --> [*]
```

The allowed transitions live on the enum (`domain/OrderStatus.canTransitionTo`), so an illegal move is refused (HTTP 409) wherever it comes from.

### Suggestion

```mermaid
stateDiagram-v2
    [*] --> PENDING : created by the loop or Get suggestion
    PENDING --> ACCEPTED : ops accepts (order moves)
    PENDING --> REJECTED : ops rejects, or accepted a sibling
    PENDING --> EXPIRED : system withdrew it
    ACCEPTED --> [*]
    REJECTED --> [*]
    EXPIRED --> [*]
```

`EXPIRED` means **no human said no**: the recommended agent went Busy/Offline, a new agent became Available (re-balance), or ops kept/reassigned the order another way.

### Agent

```mermaid
stateDiagram-v2
    AVAILABLE --> BUSY : AgentBusyEvent
    AVAILABLE --> OFFLINE : AgentOfflineEvent
    BUSY --> OFFLINE : AgentOfflineEvent
    BUSY --> AVAILABLE : AgentAvailableEvent (re-balance)
    OFFLINE --> AVAILABLE : AgentAvailableEvent (re-balance)
    OFFLINE --> BUSY : no event
```

An agent's **active order count** changes only through `Agent.assignOrder()` / `releaseOrder()`: +1 when an order is created for them or moved to them, −1 when an order is moved away or delivered.

---

## 10. Live reasoning (SSE streaming)

"Get suggestion" calls `POST /orders/{id}/suggest/stream`. The answer arrives as Server-Sent Events while the AI is still writing.

```mermaid
sequenceDiagram
    participant UI as Angular UI
    participant API as OrderController
    participant AI as AIAdvisorService
    participant LLM as Gemini / Groq

    UI->>API: POST /orders/ORD-005/suggest/stream
    API-->>UI: event: start {strategy}
    API->>AI: route with a reasoning listener
    AI->>LLM: stream request
    loop as text arrives
        LLM-->>AI: next chunk of JSON
        AI->>AI: extract just the "reasoning" text
        AI-->>UI: event: token {text}
    end
    alt provider fails or answer is invalid
        AI-->>UI: event: restart {reason} (UI clears the text)
    end
    API->>API: validate + save (same path as /suggest)
    API-->>UI: event: suggestion {the saved suggestion}
```

The model replies in JSON. `ReasoningExtractor` decodes only the `reasoning` field as it arrives, so ops reads a sentence forming instead of raw JSON. Gemini sends text in a few large bursts; Groq sends smaller pieces.

---

## 11. Fleet rules and guardrails

| Rule | Why | Enforced in |
|---|---|---|
| At least one agent stays `AVAILABLE` | otherwise routing has no candidates and every stranded order is stuck | `AgentServiceImpl` (rows locked, safe for simultaneous clicks) + disabled buttons in the UI |
| Only `AVAILABLE` agents get new orders (create, reassign, accept, keep) | `BUSY` means "not taking more" | `OrderServiceImpl`, `SuggestionServiceImpl` + filtered dropdowns |
| AI answers are checked against the real roster | models sometimes invent IDs | `AIRoutingStrategy.validate` |
| Confidence is capped on a thin roster (0.60) or a single candidate (0.75) | no strategy should sound certain when the fleet can't back it up | `RoutingService.applyRosterLimits` |
| Agents at capacity aren't suggested; if everyone is, confidence ≤ 0.40 with a warning | nobody gets overloaded without ops seeing it | `RoutingService.applyCapacityLimits` |
| An at-risk order is moved only if someone could start it sooner, and is flagged once | no churn: a deadline doesn't trigger a suggestion every 30 s | `SlaMonitor` + `OrderServiceImpl.claimSlaAlert` (row lock) |
| Delivering an order withdraws its open suggestions | nothing left to accept on a finished order | `OrderServiceImpl.updateStatus` |
| Every API call needs a signed-in user; writes need the CSRF token | a stranger, or another website, can't move orders | `config/SecurityConfig` |
| Silent agents are taken off duty automatically | a dead phone or a crash shouldn't wait for someone to notice | `HeartbeatMonitor` + `AgentServiceImpl.markOfflineIfSilentSince` |
| A suggestion is re-checked right before saving | the roster can change while the AI thinks | `SuggestionServiceImpl.createSuggestion` |
| Order status changes follow the state machine | no impossible jumps (e.g. DELIVERED → ASSIGNED) | `OrderStatus.canTransitionTo` |
| Errors always have one JSON shape | the UI can show the server's message | `exception/GlobalExceptionHandler` |

---

## 12. Things you might not know

- **The strategy toggle is remembered.** It's saved in the database, so a restart keeps your choice. `ROUTING_STRATEGY` only applies until someone switches for the first time.
- **Every change is in the activity log.** Status changes, suggestions, decisions, reassignments and strategy switches are all recorded with `ops` or `system` as the actor (Insights page, or `GET /activity`).
- **"Get suggestion" uses the *initial* prompt even for a stranded order**, and its card is tagged "Requested". Only the background loop uses the recovery prompt.
- **Rejecting doesn't automatically ask again.** The order waits until ops clicks Get suggestion, reassigns, or an agent status change triggers a re-plan.
- **Becoming Available replaces suggestions on screen.** A re-balance withdraws and recreates every waiting order's suggestion. If you click Accept on one being replaced, you'll get a message; the next refresh shows the new one.
- **An offline agent's order count doesn't drop immediately.** It falls as their orders are accepted, reassigned or delivered, so the roster may show an Offline agent still "holding" orders.
- **Pending load counts every open suggestion**, including manual ones on unrelated orders. An agent with many unanswered suggestions looks busier to routing.
- **Each AI call costs time and quota.** A re-balance of 8 waiting orders with Gemini takes around a minute; rule-based is instant.
- **The test suite never calls a real AI.** It uses a fake provider that lives only in `src/test` and can simulate each failure (timeout, quota, garbage, made-up agent). The running app uses only Gemini and Groq.
- **You can inspect the local database** by starting the backend with `H2_CONSOLE_ENABLED=true`, signing in, and opening http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/ziprun-db`, user `sa`, empty password). The path is relative to the folder the backend was started from, normally `backend/reassignment-engine`. With Docker, use `docker compose exec db psql -U ziprun`.
- **Capacity counts queued suggestions.** An agent with 4 orders and 2 unanswered suggestions is at 6/6 and won't be suggested again until something is accepted, rejected or delivered.
- **Zones are deliberately coarse:** same, neighbouring, or further. They're a stand-in for travel time; the list lives in `routing/Zones.java`.
- **Orders without a deadline are never flagged**, and existing orders from before deadlines existed don't get one.
- **The backend log tells the story.** Search for `AGENTIC LOOP` to see each trigger and its outcome (`queued / skipped / no available agent / failed`), and `LLM provider` to see which AI answered and how long it took.
- **Background work runs on 2 threads** (`spring.task.execution.pool.*`), so simultaneous status changes queue up rather than run all at once.

---

## 13. Known limitations

Honest list of what isn't solved yet (most are on the Roadmap in the README):

| Limitation | Impact | Possible fix |
|---|---|---|
| Heartbeats come from a browser simulator, not a real agent app | Automatic offline detection works, but needs an app to send heartbeats | Build the agent app (on the Roadmap) |
| Background events aren't durable | If the backend dies mid-loop, unfinished orders wait for the next trigger | Outbox table or a message broker |
| Single backend instance assumed | In-process events, live-update connections, the sign-in lockout and the AI circuit breakers are per instance | A message broker and a shared store (e.g. Redis) |
| One shared ops login | The activity log can't say *which* person did something | Named accounts and roles |
| Zones, not travel time | "Neighbouring" is a rough proxy for "close"; traffic is ignored | Coordinates and a routing API |
| "Could start it sooner" means fewer orders ahead | The deadline check doesn't know how long each order takes | Per-order time estimates |

---

## 14. Troubleshooting

| You see | Likely cause | What to do |
|---|---|---|
| Every suggestion says `rule-based (AI fallback: NOT_CONFIGURED)` | No API key reached the backend | Set `GEMINI_API_KEY` / `GROQ_API_KEY` *before* starting it |
| Many `ai:groq` tags | Gemini returned 503 "overloaded" or timed out; Groq stepped in | Normal; check `GET /routing/providers`: Gemini is probably paused and will be retried automatically |
| `rule-based (AI fallback: CIRCUIT_OPEN)` | Every AI provider failed repeatedly and is paused | Check `GET /routing/providers` for the last error (bad key, quota); they're retried after the cooldown |
| UI says "Can't reach the backend" | Backend not running, or the dev server was started without the `/api` proxy | Start the backend on 8080; run the UI with `npm start` / `ng serve` (it reads `proxy.conf.json`) |
| A waiting order has no suggestion | No other agent is Available (its own agent is excluded) | Make an agent Available, or use **Keep with …** if its agent is back |
| "X is the only AVAILABLE agent…" (409) | The last-available guardrail | Make another agent Available first |
| Accept fails with "…isn't taking new orders" | The recommended agent went Busy/Offline since the suggestion was made | Wait for the refresh; a new suggestion replaces it |
| Backend won't start: `UnsupportedClassVersionError` | Running on Java 8/11 | Use Java 17+ (`JAVA_HOME`), or run with `docker compose up --build` |
| An agent went Offline on their own | Their app stopped sending heartbeats (see the note on their Fleet card) | Set them Available again when they're back on shift |
| An Available agent is never suggested | They're at capacity (red load bar) | Raise their capacity on the Fleet page, or wait for orders to be delivered |
| Every suggestion says "Over capacity" | Everyone available is full | Make more agents Available or raise capacities (`AGENTS_DEFAULT_MAX_CAPACITY`) |
| An order is late but nothing appeared under "Deadline at risk" | Nobody could start it sooner, or it has no deadline | The activity log says which ("no agent could start it sooner") |
| The console keeps returning to Sign in | The session expired (12 h) or the backend restarted | Sign in again; sessions live in the backend's memory |
| Sign-in says "Too many failed sign-ins" | 5 wrong passwords from your address | Wait 10 minutes |
| curl gets 401 | The API needs a user now | Add `-u ops:ziprun` (or your `OPS_USERNAME:OPS_PASSWORD`) |
| The sidebar says "Updating every 3s" instead of "Live" | The live stream (`/events`) dropped; the console fell back to polling | It reconnects by itself; check a proxy isn't buffering `text/event-stream` |

---

## 15. History and metrics (Insights)

The **Insights** page answers "is the AI actually better than rule-based?" from real decisions.

| Metric | How it's calculated |
|---|---|
| Acceptance rate (per source and overall) | accepted ÷ (accepted + rejected). `EXPIRED` suggestions were withdrawn by the system, not judged by a person, so they don't count either way. |
| AI fallback rate | rule-based fallbacks ÷ (AI answers + fallbacks): how often the AI was tried but couldn't answer |
| Average confidence | mean confidence of suggestions from that source |
| Response time | average and 95th-percentile `routingMillis` (time to produce a suggestion, AI calls included). Suggestions created before timing existed show "-". |
| AI providers | how many AI suggestions Gemini and Groq each produced |

Sources are read from each suggestion's `source`: `ai:*` → AI, `…fallback…` → AI fallback, `rule-based` → Rule-based, empty → "Before tracking".

The **activity log** (`activity_log` table, `GET /activity`) records every meaningful change: agent status (by ops, or automatic), agent zone and capacity edits, orders created/delivered/reassigned/kept, suggestions created/accepted/rejected/withdrawn, deadline alerts, and strategy switches. Each entry is written in the same transaction as the change it describes, so the log never shows something that didn't actually happen.

---

## 16. Capacity, zones and delivery deadlines

### Capacity

Every agent can carry at most a certain number of orders: their own `maxCapacity` (set on the Fleet page), or the fleet default `AGENTS_DEFAULT_MAX_CAPACITY` (6; `0` means no limit). Routing compares it with **effective load** (active orders + suggestions already queued for them), so a batch of suggestions can't overfill someone either. See [section 4](#4-how-an-agent-is-chosen-the-routing-pipeline) for what happens when agents are full. The console shows load as `4/6`, red when full.

### Zones

`routing/Zones.java` lists 16 Bengaluru areas and which ones border each other. Agents have a `currentZone` (Fleet page) and orders a pickup and drop-off zone (New order dialog). Routing compares the **pickup** zone with each agent's zone:

| Distance | Penalty (rule-based score) | AI sees |
|---|---|---|
| same zone | +0 | `same` |
| neighbouring zone | +1 | `neighbour` |
| unknown (agent has no zone) | +2 | `unknown` |
| further away | +3 | `far` |

The AI gets the same information as extra columns in its agent table (capacity, zone, distance to pickup) and is told to prefer nearby agents. An order without a pickup zone is routed on load alone.

### Delivery deadlines (the SLA monitor)

New orders get `slaDeadline = created + ORDERS_DEFAULT_SLA_MINUTES` (120) unless the dialog picks another time or "No deadline". `service/order/SlaMonitor.java` runs every 30 seconds:

```mermaid
flowchart TD
    T(["Every 30 s"]) --> F["Orders ASSIGNED / REASSIGNED,<br/>due within 30 min (or late),<br/>not flagged yet"]
    F --> C["Claim it: set slaAlertedAt<br/>(row lock, so only once)"]
    C --> R["Route with SLA_RISK context<br/>(same capacity, zone, AI rules)"]
    R --> Q{"Best agent has fewer<br/>orders ahead than the<br/>current one?"}
    Q -- "yes" --> S["Queue an SLA_RISK suggestion<br/>shown under 'Deadline at risk'"]
    Q -- "no" --> K["Leave it with its agent"]
    S --> L["Activity log:<br/>'at risk … suggested moving it to X'"]
    K --> L2["Activity log:<br/>'at risk … no agent could start it sooner'"]
```

- **"Fewer orders ahead":** the current agent has this order plus `n − 1` others; another agent is faster if their effective load is below `n − 1`. It's a count, not a time estimate (see [limitations](#13-known-limitations)).
- **Nothing moves by itself.** Accept moves the order to the faster agent (`ASSIGNED → REASSIGNED`); Reject keeps it where it is.
- **Each order is flagged once.** If ops rejects, the monitor doesn't nag every 30 seconds.
- **Waiting orders** (already `REASSIGNMENT_PENDING`) aren't touched by the monitor; the queue lists them soonest-deadline first instead.
- **Delivering an order** withdraws any open suggestion for it.
- The AI gets a different situation line for these: *"this order is at risk of missing its delivery deadline with its current agent … say why they would be faster"*, and the deadline with minutes left.

---

## 17. Live updates

The console doesn't poll. It keeps one Server-Sent Events connection open to `GET /events`:

```mermaid
sequenceDiagram
    participant A as Any write (ops, re-plan loop, monitors)
    participant JPA as ChangeTracker (JPA listener)
    participant L as LiveUpdates
    participant UI as Every open console
    A->>JPA: row inserted / updated
    JPA->>L: after the transaction commits: "orders changed"
    Note over L: changes within 150 ms are merged into one event
    L-->>UI: event: change {topics: [orders, suggestions, activity]}
    UI->>UI: re-read through the normal API
```

- **Every write path is covered** because the hook is on the entities (`@EntityListeners(ChangeTracker.class)`), not in each service.
- **Only after commit**, so a console never re-reads data that is later rolled back.
- **Events say *what kind* of data changed, not the data.** The console re-reads through the normal endpoints, so there's one source of truth.
- **If the stream drops** (backend restart, network), the browser reconnects by itself and the console polls every 3 seconds meanwhile; the sidebar says "Updating every 3s" instead of "Live · instant updates". A comment line every 25 s keeps idle connections open through proxies (nginx is configured not to buffer `/api`).

---

## 18. Sign-in and security

```mermaid
sequenceDiagram
    participant B as Browser (console)
    participant API as Backend
    B->>API: GET /api/auth/me
    API-->>B: 401 (not signed in) + XSRF-TOKEN cookie
    B->>API: POST /api/auth/login {username, password}
    API-->>B: 200 + session cookie (new id)
    B->>API: PATCH /api/agents/... with X-XSRF-TOKEN = cookie value
    API-->>B: 200
    Note over B,API: a forged request from another site can't read the cookie,<br/>so it can't send the header: 403
```

| Who | How they authenticate | Can do |
|---|---|---|
| Ops in the console | `POST /auth/login` → session cookie (HttpOnly, SameSite=Lax, 12 h) | everything |
| Scripts / curl | HTTP Basic (`-u ops:…`) | everything |
| An agent's phone app | header `X-Agent-Token: $AGENT_APP_TOKEN` | heartbeats only |

- **One account** from `OPS_USERNAME` / `OPS_PASSWORD` (default `ops` / `ziprun`, with a warning in the log until it's changed).
- **CSRF:** writes from a session must carry the `XSRF-TOKEN` cookie value in `X-XSRF-TOKEN`. Angular's HttpClient does this automatically; the streamed "Get suggestion" (which uses `fetch`) adds it by hand. Basic and agent-token calls are exempt because a browser never adds those headers by itself.
- **Brute force:** 5 wrong passwords from one address lock it out for 10 minutes (429).
- **No browser prompt:** 401s carry no `WWW-Authenticate`, so browsers never show their own login box or remember Basic credentials.
- **CORS** is an exact allow-list (`CORS_ALLOWED_ORIGINS`). The console doesn't need it: it calls `/api` on its own origin. (An earlier filter trusted any origin *containing* `localhost:4200`, which a page at `localhost:4200.evil.com` would have matched; it was removed when sign-in arrived.)
- **Tests** run with `security.enabled=false` except `SecurityIntegrationTest`, which checks all of the above with security on.

---

## 19. The database and migrations

- **Locally** the backend uses an H2 file (`backend/reassignment-engine/data/`). **Docker Compose** runs PostgreSQL 16 and points `DATABASE_URL` at it. CI runs the main flow against a real PostgreSQL too.
- **Flyway owns the schema** (`src/main/resources/db/migration`); Hibernate doesn't create or change tables (`ddl-auto=none`).

| Migration | What it does |
|---|---|
| `V1__baseline_schema.sql` | all tables. Idempotent (`IF NOT EXISTS`) so it also upgrades databases created before migrations existed, which are baselined at version 0; converts H2's native ENUM columns to VARCHAR so new enum values (like `SLA_RISK`) need no manual `ALTER` |
| `V2__seed_sample_data.sql` | the 5 sample agents and 8 orders, only into an empty database |
| `V3__zones_capacity_deadlines.sql` | `orders.sla_alerted_at`, and zones for the sample data (only rows still exactly as seeded) |

To change the schema, add `V4__what_it_does.sql`. Keep it valid on both H2 and PostgreSQL: `ADD COLUMN IF NOT EXISTS`, `ALTER COLUMN … SET DATA TYPE`, and standard types work on both.
