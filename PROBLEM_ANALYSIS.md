# AI Reassignment Engine - Problem Statement Deep Dive

## TLDR: What Are You Building?

You're building a **reactive, AI-powered order reassignment system** for ZipRun (a delivery fleet platform). When a delivery agent becomes unavailable mid-shift, the system automatically detects it, figures out which orders are stranded, uses AI to recommend the best reassignments, and presents suggestions to ops for approval.

**Key insight:** This is NOT a full dispatch system. It's specifically the "something broke, let's fix it" loop.

---

## The Real-World Problem (Context)

### Current State (Broken)
- Morning: Ops manager manually assigns 50+ orders to 15 agents
- Mid-shift catastrophe: Agent calls in sick / bike breaks down / becomes unavailable
- Current solution: Ops manager notices (maybe), opens spreadsheet, manually reassigns
- Problems: **Slow, error-prone, depends on one person, fails silently**

### Your Solution
- Agent goes offline → **System automatically detects**
- System identifies affected orders → **No manual lookup needed**
- AI recommends best new agent → **Reasoning shown to ops**
- Ops clicks approve/reject → **Human keeps final control**

**This is agentic because:**
- **Observe** (event-driven, not polling)
- **Reason** (structured thinking about which orders are affected)
- **Act** (queuing suggestions, not auto-assigning)
- **Checkpoint** (human approval before anything changes)

---

## What You're Building (6 Tasks, 117 Points)

### **T-1: Domain Model & API (20 pts, ~50 min)**

Three core entities with careful state machines:

```
Order:
  ASSIGNED (just got an order)
    ↓
  REASSIGNMENT_PENDING (assigned agent went offline)
    ↓
  REASSIGNED (ops approved new agent)
    ↓
  DELIVERED (order completed)

Agent:
  AVAILABLE (ready to take orders)
  BUSY (carrying orders)
  OFFLINE (unavailable - THIS TRIGGERS EVERYTHING)

ReassignmentSuggestion:
  Stores: which order, recommended agent, AI confidence, AI reasoning
  Status: PENDING → ACCEPTED | REJECTED
  Trigger: INITIAL (manual request) | AGENT_OFFLINE (automatic)
```

Four REST endpoints:
- `POST /orders` - create order pre-assigned to agent
- `GET /orders?status=` - list orders by status
- `PATCH /agents/{id}/status` - mark agent online/offline (fires agentic loop)
- `PATCH /suggestions/{id}` - ops approves/rejects

**Design for later:** Sprint 2 adds zones, capacity, weight classes. Don't hardcode - leave extension seams.

---

### **T-2: Pluggable Routing Engine (25 pts, ~55 min)**

The core architectural challenge: **How do you make strategies swappable at runtime without restarting?**

**The Contract** (interface all strategies implement):
```java
RoutingStrategy {
  suggest(Order order, List<AvailableAgent> roster) 
    → RoutingRecommendation
}
```

**Implementation 1: Rule-Based Strategy**
- Simple: Pick the agent with fewest active orders
- Always works, no external dependencies
- Deterministic and fast

**Implementation 2: AI Strategy** (T-3)
- Smart: Ask LLM which agent makes most sense
- Slower, requires API key, can fail
- Returns confidence score + reasoning

**The Switchability Pattern** (key ADR topic):
You'll decide between these approaches:
- Spring `@Qualifier` (simple but requires restart)
- Auto-wired `Map<String, RoutingStrategy>` (clean, no restart needed)
- Manual factory (explicit but less scalable)

**Why this matters for sprint 2:**
Sprint 2 adds `ZoneAffinityStrategy` (prefer agents already in the pickup zone). With good design, adding it means: implement interface, register bean. Done. With bad design: modify factory, update controllers, adjust persistence.

---

### **T-3: AI Routing Strategy (25 pts, ~55 min)**

This is where the "AI" in "AI Reassignment Engine" lives.

**What you're doing:**
1. Take an order (pickup/dropoff, contents, deadline)
2. Take the available agent roster (current load, status)
3. Take the situation context (first assignment vs recovery from failure)
4. Send to LLM: "Given this order and these agents, who should take it?"
5. Parse response: agent ID, confidence (0.0-1.0), reasoning

**Critical design: Two Different Prompts**

*Initial Assignment Prompt:*
```
Order: electronics delivery, Koramangala → Indiranagar
Available agents:
- Priya (2 orders, BUSY)
- Rahul (0 orders, AVAILABLE)
- Ananya (1 order, BUSY)
Who should take this?
Return JSON: {"agentId": "...", "confidence": 0.85, "reasoning": "..."}
```

*Re-Plan Prompt (after agent goes offline):*
```
AGENT OFFLINE EVENT - Recovery Mode
Agent AGT-001 (Priya) has gone offline.
Orders now stranded (previously assigned to Priya):
- ORD-001: electronics
- ORD-002: groceries
Available agents for reassignment:
- Rahul (0 orders, AVAILABLE)
- Ananya (1 order, BUSY)
- Deepak (3 orders, BUSY)
For each stranded order, recommend the best agent.
Return JSON: [{"orderId": "ORD-001", "agentId": "...", ...}, ...]
```

**Why different?** The model needs to know it's in **recovery mode**. Previous assignments to that agent are void. It's not a normal routing problem - it's fixing a broken state.

**Resilience (critical):**
- LLM timeout? Fall back to rule-based
- Hallucinated agent ID? Validate before persisting
- Malformed JSON? Fallback strategy
- Async re-plan failure? Still create suggestion from rule-based instead of silent drop

---

### **T-4: Agentic Re-Planning Loop (15 pts, ~45 min)**

**THE HEART OF THE SYSTEM.** This is what makes it "agentic."

**The Flow:**
```
1. ops calls: PATCH /agents/AGT-001/status → { status: "OFFLINE" }
2. Endpoint returns immediately (200 OK)
3. Asynchronously (background thread or event listener):
   a) Query: Get all orders where assigned_agent = AGT-001 AND status = ASSIGNED
   b) For each stranded order:
      - Check: Does PENDING suggestion with trigger=AGENT_OFFLINE exist? If yes, skip
      - Call routing strategy (AI or rule-based) with re-plan context
      - Persist ReassignmentSuggestion with triggerReason = AGENT_OFFLINE
4. Ops sees suggestions on next poll/refresh
```

**Why async?**
- Endpoint must return fast (no waiting for re-plan)
- If re-plan takes 5 seconds and there are 10 orders, ops sees a 50-second lag (terrible UX)

**What makes this "agentic"?**
- **Not a timer** (polling every minute is automated, not agentic)
- **Event-driven** (responds to state change)
- **Structured reasoning** (identifies stranded orders, not all orders)
- **Bounded action** (queues suggestions, doesn't assign)
- **Human checkpoint** (ops approves before anything changes)

**Idempotency (critical):**
If same agent flips offline twice, or two agents go offline affecting overlapping orders, don't create duplicate suggestions. Check: `SELECT * FROM suggestions WHERE order_id = ? AND status = PENDING AND trigger_reason = AGENT_OFFLINE`

---

### **T-5: Ops Interface (12 base + 8 optional, ~40 min)**

**The Floor (what everyone ships):**
- List of orders in REASSIGNMENT_PENDING status
- For each: current suggestion shown inline
  - Recommended agent name
  - Confidence score (0.85 = 85%)
  - AI's plain-English reasoning (ops reads this)
- Accept button → `PATCH /suggestions/{id} { status: "ACCEPTED" }`
- Reject button → `PATCH /suggestions/{id} { status: "REJECTED" }`
- **Visual badge** showing if this is agentic re-plan (AGENT_OFFLINE) vs manual request (INITIAL)
- Agent roster showing status: AVAILABLE, BUSY, OFFLINE (clearly differentiated)
- Polling or manual refresh so new suggestions appear
- Loading states and error handling

**The Ceiling (if time permits, +8 pts):**
- Full dispatch board: all orders across all statuses
- SLA countdown timer (green/amber/red)
- Agent load visualization (orders vs capacity)
- Zone awareness showing agent locations

**Design philosophy:** The interface's job is to show the system working - especially the re-plan badge appearing after an agent goes offline. A clean, working floor beats a fancy ceiling that cost you the agentic loop.

---

### **T-6: ADR + Live Walkthrough (20 pts)**

The ADR (Architecture Decision Record) is **not documentation written after**. It's **decisions documented as you make them**.

**Format (4 fields per entry):**
```markdown
## ADR-1: Where does routing logic live?

**Context**
Need to separate routing strategy selection from HTTP handling and event handling.
Both HTTP endpoint and async event listener call routing.

**Options considered**
a) Service layer with routing as a method
b) Domain model method on Order
c) Dedicated RoutingService bean

**Decision**
Chose dedicated RoutingService bean because it encapsulates the strategy selection
logic and can be injected into both HTTP controller and event listener without
coupling either to routing concerns.

**Tradeoffs accepted**
Less explicit than domain model (readers need to know Spring dependency injection).
Adds a new class - slightly more structure overhead. Worth it for clean separation
of concerns.
```

**The 4 Required ADR Topics:**

1. **Routing Architecture** - Where does logic live? Why not elsewhere?
2. **Strategy Switchability** - How do you swap at runtime? Defend choice vs alternatives
3. **LLM Resilience** - How does system stay healthy when LLM fails? All failure modes?
4. **Agentic Loop Trigger** - How decoupled from request path? Why this approach over others?
5. **(BONUS) Extensibility** - Point to where sprint 2 feature plugs in with minimal code change

**The Walkthrough:**
In submission review, you'll trace:
- Create order → assign to agent → manually get suggestion → accept
- Agent goes offline → system detects → suggestions appear → ops accepts
- Show the code paths, explain the reasoning

---

## Tech Stack Mapping

### Backend (Your Stack ✓)
- **Language:** Java 17+
- **Framework:** Spring Boot 3.x ✓ (you have this)
- **Build:** Maven or Gradle
- **Persistence:** Spring Data JPA ✓
- **Database:** H2 (dev, zero setup) or Postgres
- **Async:** Spring @Async, ApplicationEventPublisher, or thread pool

### LLM (Choose One)
| Provider | Setup | Cost | Speed | Notes |
|----------|-------|------|-------|-------|
| **Gemini 1.5 Flash** | API key from aistudio.google.com | Free quota | Medium | Good balance |
| **Groq + Llama 3.1** | API key from console.groq.com | Free quota | ⚡ Fast | Best speed |
| **Ollama** | Local install, zero cost | N/A | Depends on HW | No API key needed |

Addendum B provides the HTTP wrapper covering all three.

### Frontend (Choose One)
- **React 18** + Vite (your choice - no strong preference either way)
  - Dev: `npm create vite@latest` 
  - Dev port: localhost:5173
  - Less opinionated, good for rapid UI
  
- **Angular 17** + standalone API
  - Dev: `ng serve`
  - Dev port: localhost:4200
  - More structured, good for larger apps

Document your choice in ADR.

---

## The Evaluation Rubric (What Actually Counts)

| Area | Points | What They're Assessing |
|------|--------|------------------------|
| Entity state machines | 8 | Does your Order/Agent/Suggestion model feel like it was designed vs stumbled into? |
| API correctness | 7 | Right HTTP verbs, status codes, validation placement |
| Routing contract | 10 | Interface before implementation? Works from both call paths? |
| Runtime switchability | 8 | Can you change strategy via config without restart? |
| Initial prompt quality | 8 | Does LLM have what it needs to reason well? |
| Re-plan prompt quality | 8 | Genuinely different from initial? Includes failure context? |
| AI resilience | 9 | All failure modes handled? Fallback works? No silent drops? |
| Agentic loop trigger | 7 | Event-driven, non-blocking, idempotent? |
| Human checkpoint | 8 | Clear that system suggests, doesn't assign? |
| Ops UI floor | 12 | Shows re-plan badge? AI reasoning visible? Accept/reject works? |
| ADR quality | 10 | 4+ entries with real thinking, not retrospective assembly? |
| Live walkthrough | 10 | Can you trace code paths and explain reasoning naturally? |
| **Total** | **117** | |

---

## Critical Success Factors (What Evaluators Actually Test)

### 1. **The Agentic Loop is Event-Driven, Not Polling**
They'll ask: "What happens if the re-plan takes 5 seconds but another agent goes offline at second 2?"
- **Bad answer:** "It'll pick it up on the next poll cycle"
- **Good answer:** "Event listener fires immediately for the second agent, both are processed in parallel"

### 2. **Two Prompts Are Genuinely Different**
They'll ask: "Why does re-plan need a different prompt?"
- **Bad answer:** "Because I thought it made sense"
- **Good answer:** "Initial is normal routing. Re-plan is recovery - the model needs to know a specific agent failed, which orders are stranded, and that previous assignments to that agent are void. Same prompt would cause hallucinations."

### 3. **System Stays Healthy When LLM Fails**
They'll ask: "What if Gemini is down?"
- **Bad answer:** "I didn't think about it"
- **Good answer:** "HTTP timeout → fallback to rule-based, log the failure. Async re-plan failure → still create suggestion from rule-based rather than silent drop. Ops sees suggestion even if it's not AI-powered."

### 4. **Human Checkpoint is Intentional**
They'll ask: "Why not auto-assign when confidence > 0.9?"
- **Bad answer:** "Seemed safer"
- **Good answer:** "System queues suggestions, ops approves. This is deliberate - by sprint 3 when we have SLA urgency, we can conditionally remove the checkpoint for low-risk situations, but starting with human-in-the-loop is the safe default."

### 5. **ADR Entries Match the Code**
They'll ask: "Show me where this strategy switchability you wrote about actually happens in the code"
- If ADR says "bean map keyed by name" and code does that → credibility ✓
- If ADR says "bean map" but code has a switch statement → credibility ✗

---

## Timeline Reality Check

You have 5 hours. Here's the path:

| Phase | Time | Focus | Outcome |
|-------|------|-------|---------|
| 1 | 15-20 min | **Read brief, sketch on paper, write ADR-1** | You understand the architecture before coding |
| 2 | 60-75 min | **Entities, API, routing engine** | Domain model works, 2 strategies implemented |
| 3 | 55-65 min | **Both prompts, AI strategy, fallbacks** | LLM integration with resilience |
| 4 | 45-55 min | **Agentic loop** | Agent offline → suggestions appear |
| 5 | 40-50 min | **Ops interface** | UI shows the re-plan badge working |
| 6 | 30-40 min | **ADR completion, demo, submission** | Finished with breathing room |

**If running behind:**
1. Drop SSE streaming bonus (+5 pts) - nice to have
2. Drop UI ceiling (+8 pts) - keep just the floor
3. **Never drop:** Agentic loop (15 pts) or ADR (20 pts) - these carry weight

---

## Key Architectural Decisions You'll Make

These are the moments where you'll learn the most. Write ADR entries for each:

### Decision 1: Where does routing live?
- In a dedicated Service? Domain object? Controller?
- Why not elsewhere?
- What does this enable for sprint 2?

### Decision 2: How do strategies swap?
- Spring @Qualifier (simple, needs restart)
- Auto-wired Map (scalable, no restart)
- Factory pattern (explicit, rigid)
- Your choice, but be ready to defend it

### Decision 3: LLM failure handling
- Timeout → what? (fallback immediately? retry?)
- Bad JSON → what?
- Hallucinated agent ID → what?
- Async re-plan failure → critical - must not silent drop

### Decision 4: Event vs Timer for agentic loop
- Spring @EventListener (clean, decoupled)
- ApplicationEventPublisher (explicit events)
- Scheduled poller (simple, but not agentic)
- Dedicated thread pool (control, but more boilerplate)

---

## Extensions on the Roadmap (Think About These)

You're not building these in sprint 1. But keep them in mind when designing:

**Sprint 2: Zone Awareness**
- Agents have currentZone
- Orders have pickupZone
- ZoneAffinityStrategy prefers nearby agents
- Can your routing interface handle this? (Yes if designed right)

**Sprint 3: Proactive Re-Planning**
- SLA deadlines on orders
- System triggers re-plan proactively if order approaches breach
- Not just event = OFFLINE, also event = SLA_SOON
- Can your event mechanism handle multiple trigger types? (Yes if designed right)

---

## What Evaluators Will Ask in the Walkthrough

1. "Walk me through what happens when an agent goes offline"
   - They're checking: do you understand the agentic loop?

2. "Why did you choose that strategy switchability approach?"
   - They're checking: did you consider alternatives, or just default?

3. "Show me how the system handles an LLM timeout"
   - They're checking: are you thinking about failure modes?

4. "What would you change to support zone-aware routing in sprint 2?"
   - They're checking: is your design forward-looking?

5. "Which ADR entry took you the longest to think through?"
   - They're checking: do you do deep thinking or surface-level coding?

---

## The Secret to High Scoring

**Not the features. The thinking.**

A minimal but well-architected floor that you can trace through and explain beats an ambitious ceiling you don't fully understand. Show that you:
- Thought about failure modes
- Made deliberate tradeoffs
- Can articulate why you chose this over that
- Designed for extensibility without over-engineering

That's what high evaluation score looks like.
