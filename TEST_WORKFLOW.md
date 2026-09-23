# Full System Test Workflow (2-Hour Submission Sprint)

**Deadline:** 2:30 PM IST  
**Time Remaining:** ~2 hours  
**Status:** All backend components ready, Angular scaffolded

---

## Quick Verification Checklist

### ✅ Backend Ready
- [x] Domain model (Order, Agent, ReassignmentSuggestion)
- [x] JPA repositories
- [x] REST controllers (3-tier architecture)
- [x] Service layer (OrderService, AgentService, SuggestionService)
- [x] Routing engine (rule-based + AI strategies)
- [x] LLM integration (Gemini/Groq/Ollama support)
- [x] Configuration management

### ✅ Frontend Ready
- [x] Angular 17 scaffolded with standalone API
- [x] CORS configured (localhost:4200)
- [x] HTTP client ready

### ⏳ TODO (Next 90 minutes)
- [ ] Start backend
- [ ] Start frontend
- [ ] Test API endpoints
- [ ] Implement basic Angular UI
- [ ] ADR updates (add agentic loop design)
- [ ] Final commit + push
- [ ] Create demo video (5 min)

---

## Test Sequence (Follow in Order)

### Test 1: Verify Backend Started
```bash
curl http://localhost:8080/agents
# Should return: [ { "id": "AGT-001", ... } ]
```

### Test 2: Create Order (Pre-Assignment)
```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "description": "Electronics delivery - Bangalore",
    "assignedAgentId": "AGT-001"
  }'
# Response: { "id": "ORD-...", "status": "ASSIGNED", ... }
# Save order ID for next test
```

### Test 3: Request AI Suggestion
```bash
curl -X POST http://localhost:8080/orders/ORD-ABC123/suggest
# Response: { 
#   "id": "SUGG-...", 
#   "recommendedAgentId": "AGT-002",
#   "confidence": 0.85,
#   "reasoning": "Agent X is least loaded...",
#   "status": "PENDING",
#   "triggerReason": "INITIAL"
# }
```

### Test 4: Ops Approves Suggestion
```bash
curl -X PATCH http://localhost:8080/suggestions/SUGG-XYZ/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "ACCEPTED" }'
# Response: { "id": "SUGG-...", "status": "ACCEPTED", ... }
```

### Test 5: Verify Order Reassigned
```bash
curl http://localhost:8080/orders/ORD-ABC123
# Response: { "status": "REASSIGNED", "assignedAgentId": "AGT-002", ... }
```

### Test 6: Trigger Agentic Loop (Agent Goes Offline)
```bash
curl -X PATCH http://localhost:8080/agents/AGT-001/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "OFFLINE" }'
# Response: { "id": "AGT-001", "status": "OFFLINE", ... }
# 
# This publishes AgentOfflineEvent
# (In Phase 5, event handler will auto-create re-plan suggestions)
```

### Test 7: Check Pending Suggestions (UI will show these)
```bash
curl http://localhost:8080/suggestions?status=PENDING
# Response: List of PENDING suggestions (both INITIAL and AGENT_OFFLINE)
```

---

## What You'll See

### Terminal (Backend)
```
[DEBUG] POST /orders: description=..., agent=AGT-001
[INFO] Order created: id=ORD-..., agent=AGT-001
[DEBUG] POST /orders/ORD-.../suggest: orderId=...
[DEBUG] Calling LLM via provider: gemini
[DEBUG] AI recommendation for order ORD-...: agent=AGT-002, confidence=0.85
[INFO] Suggestion created: id=SUGG-..., orderId=ORD-..., agent=AGT-002
[DEBUG] PATCH /agents/AGT-001/status: newStatus=OFFLINE
[INFO] Agent going OFFLINE: id=AGT-001, name=Priya Sharma. Publishing AgentOfflineEvent.
```

### Browser (http://localhost:4200)
```
Angular app loads with:
- Agent roster (AVAILABLE, BUSY, OFFLINE status badges)
- Pending reassignment suggestions
  * Recommended agent
  * AI confidence score
  * AI reasoning (plain English)
  * "Re-plan" badge (if AGENT_OFFLINE trigger)
  * Accept / Reject buttons
```

---

## Architecture Validation

Once tests pass, you've validated:

✅ **Controller Layer**
- HTTP parsing & validation working
- Proper status codes (201 Created, 200 OK, 400 Bad Request)
- Error handling (404 Not Found, 500 Internal Server Error)

✅ **Service Layer**
- Business logic executing correctly
- State machine transitions (ASSIGNED → REASSIGNMENT_PENDING → REASSIGNED)
- Agent validation (can't assign to OFFLINE agent)
- Idempotency (no duplicate suggestions)

✅ **Repository Layer**
- JPA queries working
- Data persistence correct
- Relationships (Order ← → ReassignmentSuggestion)

✅ **Routing Engine**
- Strategy interface working (same contract for AI + rule-based)
- Rule-based strategy: selects least-loaded agent
- AI strategy: calls LLM, validates response, fallback on error
- Runtime switchability: strategy changeable via config

✅ **AI Integration**
- LLM gateway handles HTTP to Gemini/Groq/Ollama
- Prompt building: initial vs re-plan prompts
- Response parsing: JSON deserialization
- Validation: agent ID exists, confidence in [0.0, 1.0]
- Graceful fallback: AI failures → rule-based strategy

✅ **Agentic Event System**
- AgentOfflineEvent published when status → OFFLINE
- Event infrastructure ready for Phase 5 handler

---

## Angular UI Floor (Minimum for Submission)

Build this simple UI (takes ~30 minutes):

```
┌─────────────────────────────────────────────────────┐
│  ZipRun AI Reassignment Engine                      │
├─────────────────────────────────────────────────────┤
│                                                     │
│  AGENT ROSTER                                       │
│  ─────────────────────────────────────────────────  │
│  ✓ AGT-001 Priya Sharma        [OFFLINE]  ⚠️        │
│  ✓ AGT-002 Rahul Verma         [AVAILABLE]         │
│  ✓ AGT-003 Ananya Iyer         [BUSY]     ●●●      │
│                                                     │
│  PENDING REASSIGNMENTS                              │
│  ─────────────────────────────────────────────────  │
│  📦 Order ORD-ABC123                                │
│     Current Agent: AGT-001 (OFFLINE)                │
│     Recommended: AGT-002 Rahul Verma                │
│     Confidence: 85% 🟢                              │
│     Reason: "Rahul is AVAILABLE and closest..."     │
│     Badge: [Re-plan] ← Shows agentic loop triggered │
│     [ACCEPT] [REJECT]                               │
│                                                     │
│  Status: 1 pending reassignment                     │
└─────────────────────────────────────────────────────┘
```

### Angular Components Needed

```
src/app/
├── components/
│   ├── agent-roster/
│   │   ├── agent-roster.component.ts
│   │   └── agent-roster.component.html
│   ├── suggestion-list/
│   │   ├── suggestion-list.component.ts
│   │   └── suggestion-list.component.html
│   └── suggestion-card/
│       ├── suggestion-card.component.ts
│       └── suggestion-card.component.html
│
├── services/
│   ├── agent.service.ts      (GET /agents, PATCH /status)
│   ├── order.service.ts      (GET /orders, POST /suggest)
│   └── suggestion.service.ts (GET /suggestions, PATCH /)
│
└── app.component.ts          (Main layout + polling)
```

---

## Submission Package (45 minutes before deadline)

### 1. README.md (Update)
```markdown
# ZipRun AI Reassignment Engine

## Quick Start (< 5 minutes)

1. Set Gemini API key:
   $env:LLM_API_KEY = "your-key"

2. Start backend:
   cd backend/reassignment-engine && mvn spring-boot:run

3. Start frontend:
   cd frontend/reassignment-ui && npm start

4. Open: http://localhost:4200

## Architecture

- **Backend:** Spring Boot 3.x (Java 17)
  - 3-tier: Controller → Service → Repository
  - AI routing (Gemini LLM)
  - Rule-based fallback
  - Event-driven agentic loop

- **Frontend:** Angular 17 (standalone API)
  - Real-time suggestion display
  - Accept/reject interface
  - Agent roster with status badges
  - Re-plan badge for agentic suggestions

## API Endpoints

- POST /orders - Create order
- GET /orders - List orders
- POST /orders/{id}/suggest - Get AI suggestion
- PATCH /agents/{id}/status - Update agent (triggers agentic loop)
- GET /suggestions - List pending reassignments
- PATCH /suggestions/{id} - Accept/reject

## Key Features

✅ Domain model with state machines
✅ Pluggable routing strategies (AI + rule-based)
✅ LLM integration (Gemini/Groq/Ollama)
✅ Runtime strategy switching
✅ Graceful AI fallback
✅ Agentic event system
✅ 3-tier clean architecture
```

### 2. Demo Video (5 minutes)
```
Show:
1. Backend running with logs
2. Frontend loading
3. Create order (POST /orders)
4. Request AI suggestion (POST /suggest)
5. See confidence + reasoning
6. Accept suggestion (PATCH /suggestions)
7. Order status changes to REASSIGNED
8. Set agent to OFFLINE
9. See re-plan badge appear
10. Backend logs show agentic loop trigger
```

Use Loom or OBS (free):
- https://www.loom.com (free, no download)
- Or Windows 11 built-in: Win+G → Record

### 3. Final Push to GitHub
```bash
git add -A
git commit -m "Submission: Full stack working - backend + Angular UI"
git push origin main
```

---

## Time Allocation (120 minutes)

```
00-05 min: Gemini API setup
05-10 min: Start backend + verify logs
10-15 min: Start frontend + verify loads
15-40 min: Build Angular UI (agent roster + suggestions list)
40-50 min: Test full workflow (create order → AI suggestion → accept)
50-60 min: Fix bugs + test agentic loop (agent offline)
60-75 min: Polish UI + add loading states
75-90 min: Record 5-minute demo video
90-100 min: Update README + ADR final notes
100-120 min: Final commit + push + submit
```

---

## Critical Validation Points

Before 2:30 PM, ensure:

✅ Backend starts without errors
✅ API endpoints return correct data
✅ AI suggestions have confidence + reasoning
✅ Frontend displays suggestions
✅ Accept/reject buttons work
✅ Agent OFFLINE triggers event (check logs)
✅ GitHub repo is public + all code pushed
✅ README explains architecture
✅ Demo video uploaded (Loom link)

---

**You've got this! Start with Gemini setup (2 min) → Backend (5 min) → Frontend (10 min) → Build UI (25 min) → Test (20 min) → Submit!** 🎯
