# ZipRun AI Reassignment Engine

A 5-hour hackathon project: An agentic, AI-powered order reassignment system for delivery fleets. When a delivery agent goes offline mid-shift, the system automatically detects it, identifies affected orders, uses AI to recommend reassignments, and presents suggestions to ops for approval.

**Problem:** Manual spreadsheet reassignment is slow, error-prone, depends on one person, fails silently.  
**Solution:** Event-driven system that observes, reasons, acts, and checkpoints with human approval.

---

## Project Structure

```
C:\Pratik Workspace/
├── README.md                          (this file)
├── ADR.md                             (Architecture Decision Records)
├── PROBLEM_ANALYSIS.md                (Problem statement deep dive)
│
├── backend/
│   └── reassignment-engine/           (Spring Boot 3.x application)
│       ├── pom.xml                    (Maven configuration)
│       ├── src/main/java/com/ziprun/
│       │   └── App.java               (Spring Boot entry point)
│       └── src/main/resources/
│           ├── application.properties (Configuration)
│           └── data.sql               (Seed data)
│
└── frontend/
    └── reassignment-ui/               (React 18 + Vite application)
        ├── package.json
        ├── vite.config.js
        └── src/
```

---

## Quick Start (5 Minutes)

### Prerequisites
- Java 17+ ✅ (you have Java 25)
- Maven 3.9+ ✅ (you have 3.9.16)
- Node.js 18+ ✅ (you have 24.19.0)
- npm 9+ ✅ (you have 11.17.0)

### Backend Setup

```bash
cd C:\Pratik Workspace\backend\reassignment-engine
mvn clean install
mvn spring-boot:run
```

**Expected output:**
```
Started App in X seconds (JVM running for Y)
Tomcat started on port(s): 8080
```

**Access:**
- API: `http://localhost:8080`
- H2 Console: `http://localhost:8080/h2-console` (user: `sa`, password: empty)

### Frontend Setup

```bash
cd C:\Pratik Workspace\frontend\reassignment-ui
npm install
npm run dev
```

**Expected output:**
```
VITE v5.x.x  ready in XXX ms

➜  Local:   http://localhost:5173/
```

**Access:** `http://localhost:5173`

---

## API Endpoints

### Order Management

**Create an order (pre-assigned to an agent)**
```http
POST /orders
Content-Type: application/json

{
  "description": "Electronics delivery, Koramangala → Indiranagar",
  "assignedAgentId": "AGT-001"
}
```

**Response:** 
```json
{
  "id": "ORD-009",
  "description": "Electronics delivery...",
  "assignedAgentId": "AGT-001",
  "status": "ASSIGNED",
  "createdAt": "2026-09-23T12:00:00"
}
```

**List orders (filterable by status)**
```http
GET /orders?status=ASSIGNED
GET /orders?status=REASSIGNMENT_PENDING
GET /orders
```

### Agent Management

**Update agent status (triggers agentic loop if OFFLINE)**
```http
PATCH /agents/AGT-001/status
Content-Type: application/json

{
  "status": "OFFLINE"
}
```

**When you PATCH an agent to OFFLINE:**
1. Endpoint returns immediately (200 OK)
2. Async event handler fires in background
3. Handler identifies orders assigned to that agent
4. Handler calls routing strategy on each stranded order
5. Handler creates ReassignmentSuggestion records with `triggerReason=AGENT_OFFLINE`
6. UI polls and shows new suggestions with re-plan badge

### Reassignment Suggestions

**Get pending suggestions**
```http
GET /suggestions?status=PENDING
```

**Accept a suggestion**
```http
PATCH /suggestions/SUGG-001
Content-Type: application/json

{
  "status": "ACCEPTED"
}
```

When accepted:
- Suggestion status → ACCEPTED
- Order status → REASSIGNED
- Order assigned agent → new recommended agent

**Reject a suggestion**
```http
PATCH /suggestions/SUGG-001
Content-Type: application/json

{
  "status": "REJECTED"
}
```

### Get a suggestion for an order (on-demand)**
```http
POST /orders/ORD-001/suggest
```

**Response:**
```json
{
  "id": "SUGG-XXX",
  "orderId": "ORD-001",
  "recommendedAgentId": "AGT-002",
  "confidence": 0.92,
  "reasoning": "Rahul (AGT-002) is available and nearby. Currently carrying 0 orders.",
  "status": "PENDING",
  "triggerReason": "INITIAL"
}
```

---

## Testing the Agentic Loop (End-to-End)

This is the core feature. Here's how to test it:

### Step 1: Check Current State
```bash
curl http://localhost:8080/orders
# See all orders assigned to various agents
```

### Step 2: Trigger Agent Offline (Fire the Agentic Loop)
```bash
curl -X PATCH http://localhost:8080/agents/AGT-001/status \
  -H "Content-Type: application/json" \
  -d '{"status":"OFFLINE"}'
# Returns immediately (200 OK)
```

### Step 3: Check Suggestions (Wait 1-2 seconds, then poll)
```bash
curl http://localhost:8080/suggestions?status=PENDING
```

**Expected:** See 2-3 new suggestions with:
- `triggerReason: "AGENT_OFFLINE"` (the re-plan badge)
- `orderId: "ORD-001", "ORD-002", "ORD-008"` (orders that were assigned to AGT-001)
- `recommendedAgentId: "AGT-002"` or similar (available agent)
- `confidence: 0.85` (AI confidence, or rule-based default)
- `reasoning: "..."` (plain English explanation)

### Step 4: Accept a Suggestion
```bash
curl -X PATCH http://localhost:8080/suggestions/SUGG-001 \
  -H "Content-Type: application/json" \
  -d '{"status":"ACCEPTED"}'
```

### Step 5: Verify Order Was Reassigned
```bash
curl http://localhost:8080/orders/ORD-001
```

**Expected:**
- `status: "REASSIGNED"`
- `assignedAgentId: "AGT-002"` (now pointing to new agent)

---

## Configuration

### Backend (`application.properties`)

**LLM Provider** (choose one)

```properties
# Gemini (free quota at aistudio.google.com)
llm.provider=gemini
llm.api-key=your-api-key
llm.model=gemini-1.5-flash
llm.base-url=https://generativelanguage.googleapis.com

# OR Groq (fast, free at console.groq.com)
llm.provider=groq
llm.api-key=your-api-key
llm.model=llama-3.1-8b-instant
llm.base-url=https://api.groq.com

# OR Ollama (local, no API key)
llm.provider=ollama
llm.api-key=
llm.model=llama2
llm.base-url=http://localhost:11434
```

**Routing Strategy** (choose one)

```properties
routing.strategy=rule-based          # Always available
# OR
routing.strategy=ai-powered          # Requires LLM config above
```

**Database** (H2 in-memory by default)

```properties
spring.jpa.hibernate.ddl-auto=create-drop  # Auto-create schema
spring.h2.console.enabled=true              # Enable H2 console
```

### Frontend (`.env` or `vite.config.js`)

Set backend API URL:

```javascript
// vite.config.js
export default {
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
}
```

---

## Architecture Overview

### Domain Model

**Order**
- States: ASSIGNED → REASSIGNMENT_PENDING → REASSIGNED → DELIVERED
- Fields: id, description, assignedAgentId, status, createdAt
- Relationships: One-to-many to ReassignmentSuggestion

**Agent**
- States: AVAILABLE, BUSY, OFFLINE
- Fields: id, name, activeOrderCount, status
- Events: When status changes to OFFLINE, publish `AgentOfflineEvent`

**ReassignmentSuggestion**
- States: PENDING → ACCEPTED | REJECTED
- Fields: id, orderId, recommendedAgentId, confidence, reasoning, status, triggerReason
- TriggerReason: INITIAL (manual request) | AGENT_OFFLINE (agentic re-plan)

### Routing Engine

**RoutingStrategy Interface**
```java
public interface RoutingStrategy {
    RoutingRecommendation recommend(Order order, List<Agent> availableAgents);
}
```

**Implementations:**
1. **RuleBasedStrategy** - Pick agent with fewest active orders
2. **AIRoutingStrategy** - Call LLM for intelligent recommendation

**Selection Mechanism:**
- Auto-wired `Map<String, RoutingStrategy>` bean map
- Active strategy determined by `routing.strategy` config property
- Changed at runtime (via env var) without restart

### Agentic Loop

**Flow:**
1. `PATCH /agents/{id}/status` → agent goes OFFLINE
2. HTTP endpoint returns immediately (200 OK)
3. Spring publishes `AgentOfflineEvent`
4. `ReplanEventHandler` listener fires asynchronously (@Async)
5. Handler queries affected orders: `WHERE assignedAgentId = ? AND status = ASSIGNED`
6. For each order:
   - Check idempotency: does PENDING suggestion with trigger=AGENT_OFFLINE exist?
   - If not, call active routing strategy
   - Create ReassignmentSuggestion with `triggerReason=AGENT_OFFLINE`
7. Ops sees suggestions on next UI poll/refresh

**Key Design Principles:**
- Event-driven (not polling)
- Async (doesn't block HTTP)
- Idempotent (no duplicate suggestions)
- Resilient (LLM failure → fallback to rule-based)

---

## AI Integration (LLM Prompts)

### Initial Assignment Prompt

```
Order Details:
ID: ORD-001
Description: Electronics delivery, Koramangala → Indiranagar
Created: 2026-09-23T12:00:00

Available Agents:
1. AGT-002 (Rahul Verma) - Status: AVAILABLE, Active Orders: 0
2. AGT-004 (Kiran Nair) - Status: AVAILABLE, Active Orders: 0
3. AGT-001 (Priya Sharma) - Status: BUSY, Active Orders: 2

Task: Recommend the best agent to take this order. Consider availability and current load.

Return JSON:
{
  "agentId": "...",
  "confidence": 0.0-1.0,
  "reasoning": "Plain English explanation for ops team"
}
```

### Re-Plan Prompt (Agent Offline)

```
CRITICAL: Agent Recovery Mode

Agent Offline Event:
- Agent ID: AGT-001
- Agent Name: Priya Sharma
- Status Change: AVAILABLE/BUSY → OFFLINE at 2026-09-23T12:05:00

Stranded Orders (previously assigned to AGT-001):
1. ORD-001 - Electronics delivery, Koramangala → Indiranagar
2. ORD-002 - Groceries, HSR Layout → BTM
3. ORD-008 - Hardware, Peenya → Yeshwanthpur

Available Agents for Reassignment:
1. AGT-002 (Rahul Verma) - Status: AVAILABLE, Active Orders: 0
2. AGT-003 (Ananya Iyer) - Status: BUSY, Active Orders: 1
3. AGT-004 (Kiran Nair) - Status: AVAILABLE, Active Orders: 0
4. AGT-005 (Deepak Mehta) - Status: BUSY, Active Orders: 3

Task: For EACH stranded order, recommend a reassignment. This is urgent - orders need immediate coverage.
Consider order complexity, agent capacity, and urgency. DO NOT recommend AGT-001 (offline).

Return JSON Array:
[
  {
    "orderId": "ORD-001",
    "agentId": "...",
    "confidence": 0.0-1.0,
    "reasoning": "Why this agent for this order"
  },
  ...
]
```

**Why different?**
- Initial: "Here's a new order, assign normally"
- Re-plan: "Here's an emergency, fix it — agent failed, these orders are stranded"
- Model needs to understand context to reason correctly

---

## Development Workflow

### Phase 1: Architecture (✓ Done)
- [x] Sketch domain model
- [x] Plan routing strategy pattern
- [x] Design agentic loop trigger mechanism
- [x] Document ADRs as decisions made

### Phase 2: Domain Model & API (In Progress)
- [ ] Create Order, Agent, ReassignmentSuggestion entities
- [ ] Set up JPA repositories
- [ ] Implement 4 REST endpoints

### Phase 3: Routing Engine
- [ ] Define RoutingStrategy interface
- [ ] Implement RuleBasedStrategy
- [ ] Wire strategy selection via bean map
- [ ] Add runtime switchability

### Phase 4: AI Integration
- [ ] Implement AIRoutingStrategy
- [ ] Add LLM gateway (Gemini/Groq/Ollama support)
- [ ] Write initial & re-plan prompts
- [ ] Add fallback paths for LLM failures

### Phase 5: Agentic Loop
- [ ] Create AgentOfflineEvent
- [ ] Implement ReplanEventHandler
- [ ] Add @Async re-planning
- [ ] Test end-to-end: agent offline → suggestions appear

### Phase 6: Ops UI
- [ ] Create reassignment suggestion list
- [ ] Add accept/reject buttons
- [ ] Display re-plan badge
- [ ] Show agent roster with status
- [ ] Add polling/refresh

### Phase 7: Completion
- [ ] Finalize ADR.md
- [ ] Record 5-minute demo video
- [ ] Push to GitHub (public)
- [ ] Submit

---

## Testing

### Unit Tests
```bash
cd backend/reassignment-engine
mvn test
```

### Manual API Testing (REST Client extension in VS Code)

Create a file: `test.http`

```http
### Create an order
POST http://localhost:8080/orders
Content-Type: application/json

{
  "description": "Test order",
  "assignedAgentId": "AGT-001"
}

### Get all orders
GET http://localhost:8080/orders

### Trigger agentic loop (agent offline)
PATCH http://localhost:8080/agents/AGT-001/status
Content-Type: application/json

{
  "status": "OFFLINE"
}

### Get pending suggestions
GET http://localhost:8080/suggestions?status=PENDING

### Accept a suggestion
PATCH http://localhost:8080/suggestions/SUGG-001
Content-Type: application/json

{
  "status": "ACCEPTED"
}
```

Then click "Send Request" above each block in VS Code (thanks to REST Client extension).

---

## Environment Variables

### LLM API Key (Keep Secret!)

```bash
# Windows (PowerShell)
$env:LLM_API_KEY = "your-secret-key"

# Windows (CMD)
set LLM_API_KEY=your-secret-key

# Linux/Mac
export LLM_API_KEY=your-secret-key
```

Never commit API keys. Use `.env` file (add to `.gitignore`):

```
# .env (not committed)
LLM_API_KEY=your-secret-key
```

---

## Troubleshooting

### Backend won't start: "Port 8080 already in use"
```bash
# Find process using port 8080
netstat -ano | findstr :8080

# Kill it (replace PID)
taskkill /PID <PID> /F

# Or change port in application.properties
server.port=8081
```

### Frontend dev server won't start: "Port 5173 already in use"
```bash
npm run dev -- --port 5174
```

### H2 database seems empty
```bash
# Seed data loads automatically on startup (data.sql)
# If missing, check application.properties:
spring.jpa.hibernate.ddl-auto=create-drop  # Recreates schema
spring.datasource.initialization-mode=always  # Runs data.sql

# Manually visit: http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:testdb
# User: sa
# Password: (leave empty)
```

### LLM calls timing out
```bash
# Check LLM_API_KEY is set
echo $LLM_API_KEY  # or use $env:LLM_API_KEY on Windows

# Check API key has quota (visit provider website)
# Check network connectivity (curl the provider)

# Meanwhile, system falls back to rule-based routing
# Check logs for fallback message
```

---

## Submission Checklist

- [ ] Backend runs: `mvn spring-boot:run` from backend folder
- [ ] Frontend runs: `npm run dev` from frontend folder
- [ ] API endpoints respond (test at least /orders, /agents/{id}/status)
- [ ] Agentic loop works: agent offline → suggestions appear
- [ ] UI shows re-plan badge for AGENT_OFFLINE suggestions
- [ ] ADR.md complete (4+ entries covering decisions)
- [ ] README.md includes setup (5-min run time verified)
- [ ] GitHub repo created and public
- [ ] 5-minute demo video recorded (showing re-plan path)
- [ ] Demo uploaded to Loom or YouTube
- [ ] Submission form filled with links

---

## Key Learning Outcomes

After this session, you should understand:

1. **Agentic systems** - observe → reason → act → checkpoint pattern
2. **Event-driven architecture** - how Spring events decouple components
3. **Strategy pattern + runtime switchability** - using Spring beans for polymorphism
4. **LLM resilience** - graceful degradation when external services fail
5. **Domain modeling** - state machines and clear entity boundaries
6. **Async processing** - keeping HTTP response fast, moving work to background
7. **Architectural thinking** - making decisions now that support future extensions

---

## Resources

- **Problem Statement:** [HTML Brief](Problem%20Statement%20(1).html)
- **Problem Analysis:** [PROBLEM_ANALYSIS.md](PROBLEM_ANALYSIS.md)
- **Architecture Decisions:** [ADR.md](ADR.md)

### External Links
- [Spring Boot 3.3 Docs](https://spring.io/projects/spring-boot)
- [React 18 Docs](https://react.dev)
- [Vite Docs](https://vitejs.dev)
- [Gemini API](https://ai.google.dev)
- [Groq API](https://groq.com)
- [Ollama](https://ollama.ai)

---

## Questions?

This README and the ADR.md are living documents. Update them as you build. The README is what gets submitted, so keep it accurate.

---

**Created:** 2026-09-23  
**Status:** Phase 1 Complete (Architecture & Setup)  
**Next:** Phase 2 (Domain Model & API)

Generated with Claude Code
