# ZipRun Reassignment Engine - Project Status

**Last Updated:** 2026-09-23 11:30 AM  
**Time Elapsed:** ~30 minutes  
**Phase:** 2 (Domain Model & API)  
**Total Available:** 5 hours

---

## ✅ Phase 1: Completed (Architecture & Setup)

- [x] **VS Code Extensions Installed**
  - REST Client (for API testing)
  - Prettier (for code formatting)
  - Java extensions (already had)

- [x] **Backend Project Created**
  - Spring Boot 3.3.0 + Maven 3.9.16
  - Java 17 target
  - pom.xml with all dependencies (JPA, H2, Lombok, Jackson, Testing)
  - Spring Boot plugin configured
  - Maven build verified ✅ (BUILD SUCCESS)

- [x] **Frontend Project Created**
  - React 18 + Vite
  - npm dependencies installed
  - Ready to `npm run dev`

- [x] **Configuration Files Created**
  - `application.properties` (Spring Boot config)
  - `data.sql` (seed data - 5 agents, 8 orders)
  - App.java (Spring Boot entry point with @EnableAsync)

- [x] **Documentation Created**
  - `README.md` - Complete setup guide, API reference, testing, troubleshooting
  - `ADR.md` - 8 architecture decision records with detailed reasoning
  - `PROBLEM_ANALYSIS.md` - Problem statement deep dive

- [x] **Verified Setup**
  - Java 25 installed ✅
  - Maven 3.9.16 installed ✅
  - Node.js 24.19.0 installed ✅
  - npm 11.17.0 installed ✅
  - Backend compiles successfully ✅

---

## 📋 Phase 2: In Progress (Domain Model & API)

**Current Task:** Create entity classes and REST endpoints

### 2.1: Entity Classes (TODO)
- [ ] **Order.java** 
  - States: ASSIGNED, REASSIGNMENT_PENDING, REASSIGNED, DELIVERED
  - Fields: id, description, assignedAgentId, status, createdAt
  - Relationships: @OneToMany with ReassignmentSuggestion
  - Future fields: pickupZone, dropoffZone (nullable)

- [ ] **Agent.java**
  - States: AVAILABLE, BUSY, OFFLINE
  - Fields: id, name, activeOrderCount, status
  - Events: Publish AgentOfflineEvent when status changes to OFFLINE
  - Future fields: currentZone (nullable)

- [ ] **ReassignmentSuggestion.java**
  - States: PENDING, ACCEPTED, REJECTED
  - Fields: id, orderId, recommendedAgentId, confidence, reasoning, status, triggerReason
  - TriggerReason enum: INITIAL, AGENT_OFFLINE
  - Relationships: @ManyToOne with Order

### 2.2: JPA Repositories (TODO)
- [ ] **OrderRepository.java** - JpaRepository<Order, String>
  - Methods: findByStatus, findByAssignedAgentId, etc.

- [ ] **AgentRepository.java** - JpaRepository<Agent, String>
  - Methods: findByStatus, etc.

- [ ] **ReassignmentSuggestionRepository.java** - JpaRepository<ReassignmentSuggestion, String>
  - Methods: findByStatus, findByOrderId, findPendingOfflineReplans, etc.

### 2.3: REST Controllers & Endpoints (TODO)
- [ ] **OrderController.java**
  - `POST /orders` - Create order
  - `GET /orders` - List orders (filterable by status)
  - `GET /orders/{id}` - Get single order
  - `POST /orders/{id}/suggest` - On-demand suggestion (calls routing engine)

- [ ] **AgentController.java**
  - `GET /agents` - List agents
  - `PATCH /agents/{id}/status` - Update status (fires agentic loop)
  - `GET /agents/{id}` - Get single agent

- [ ] **SuggestionController.java**
  - `GET /suggestions` - List suggestions (filterable by status)
  - `PATCH /suggestions/{id}` - Accept or reject suggestion
  - `GET /suggestions/{id}` - Get single suggestion

### 2.4: Validation & Error Handling (TODO)
- [ ] Input validation (agent exists, valid status, etc.)
- [ ] Proper HTTP status codes (400, 404, 500)
- [ ] Error response format (structured JSON)

### 2.5: Testing (TODO)
- [ ] Smoke test all 4 endpoints
- [ ] Verify seed data loads correctly
- [ ] Check H2 database initialization

---

## ⏰ Timeline Checkpoint

```
Phase 1: Architecture & Setup    ✅ 30 min (done)
Phase 2: Domain Model & API      🔄 60-75 min (CURRENT)
Phase 3: Routing Engine          ⏳ 55-65 min
Phase 4: AI Integration          ⏳ 55-65 min
Phase 5: Agentic Loop            ⏳ 45-55 min
Phase 6: Ops UI                  ⏳ 40-50 min
Phase 7: Finalization & Submit   ⏳ 30-40 min
─────────────────────────────────────────────
TOTAL TIME AVAILABLE            ⏳ 4.5 hours remaining
```

**Pace:** On track. Phase 2 should take 60-75 minutes; we're ~30 minutes in on Phase 1 completion.

---

## 🎯 Next Immediate Steps

### Step 1: Create Entity Classes (10-15 min)

1. Open VS Code at `C:\Pratik Workspace\backend\reassignment-engine`
2. Create entities in `src/main/java/com/ziprun/domain/`
   - Order.java
   - Agent.java
   - ReassignmentSuggestion.java
   - AgentOfflineEvent.java

3. Use Lombok annotations (`@Data`, `@Entity`, `@Table`) to reduce boilerplate

### Step 2: Create Repositories (5-10 min)

1. Create interfaces in `src/main/java/com/ziprun/repository/`
   - OrderRepository.java
   - AgentRepository.java
   - ReassignmentSuggestionRepository.java

2. Extend JpaRepository<Entity, ID>

### Step 3: Create Controllers (20-30 min)

1. Create endpoints in `src/main/java/com/ziprun/controller/`
   - OrderController.java
   - AgentController.java
   - SuggestionController.java

2. Implement CRUD + special endpoints

### Step 4: Wire Everything (10 min)

1. Create services (OrderService, AgentService, SuggestionService)
2. Inject repositories into services
3. Inject services into controllers

### Step 5: Test (10-15 min)

1. `mvn spring-boot:run`
2. Test endpoints with curl or REST Client
3. Verify seed data loads

---

## 🚨 Critical Success Factors (Phase 2)

✅ **DO:**
- Write state machines clearly (enum for Order status, etc.)
- Use `@Nullable` for sprint 2 fields (pickupZone, currentZone)
- Keep entities focused (don't add routing logic here)
- Test each endpoint before moving to next

❌ **DON'T:**
- Add business logic to entities (that goes in services)
- Create complex queries (keep it simple for now)
- Forget to handle null values
- Skip testing (even basic smoke tests matter)

---

## 📊 Scoring Progress

| Component | Status | Points | Notes |
|-----------|--------|--------|-------|
| Entity design | 🔄 In progress | 8 | State machines clear? |
| API correctness | ⏳ Todo | 7 | Right verbs, status codes? |
| Persistence | ⏳ Todo | 5 | N+1 queries? Indexes? |
| Routing contract | ⏳ Todo | 10 | Interface before impl |
| Strategy switchability | ⏳ Todo | 8 | Bean map pattern |
| Pattern justification | ⏳ Todo | 7 | ADR coverage |
| **Subtotal (T-1,T-2 phase)** | | **45** | Must-have before T-3 |

---

## 🔄 Decision Log (Phase 1 ADRs Captured)

- **ADR-1**: Routing logic in dedicated RoutingService bean ✅
- **ADR-2**: Runtime strategy switching via auto-wired Map bean + config ✅
- **ADR-3**: LLM resilience: validate → fallback strategy ✅
- **ADR-4**: Agentic loop via @EventListener + @Async ✅
- **ADR-5**: Extensibility: nullable fields for sprint 2 ✅
- **ADR-6**: Frontend framework: React 18 + Vite ✅
- **ADR-7**: Two different prompts for initial vs re-plan ✅
- **ADR-8**: Idempotency via check-before-insert ✅

These are captured in ADR.md. As you build Phase 2 entities, reference these decisions.

---

## 📁 File Structure Built So Far

```
C:\Pratik Workspace
├── README.md                    ✅ Complete
├── ADR.md                       ✅ Complete (8 entries)
├── PROBLEM_ANALYSIS.md          ✅ Complete
├── PROJECT_STATUS.md            ✅ This file
│
├── backend/reassignment-engine/
│   ├── pom.xml                  ✅ Spring Boot 3.3 + all deps
│   ├── src/main/java/com/ziprun/
│   │   └── App.java             ✅ Spring Boot app
│   └── src/main/resources/
│       ├── application.properties ✅ Config
│       └── data.sql             ✅ Seed data
│
└── frontend/reassignment-ui/
    ├── package.json             ✅ React 18 setup
    ├── node_modules/            ✅ Dependencies installed
    └── src/                      ⏳ UI code (TODO)
```

---

## 🎓 Learning Checkpoints

By end of Phase 2, you should understand:

- [x] JPA/Hibernate entity mapping
- [x] Spring Data Repository pattern
- [x] REST controller structure
- [x] HTTP status codes & verbs
- [ ] State machines in code (in progress)
- [ ] Database relationships (foreign keys, constraints)
- [ ] Seed data initialization

---

## ⚠️ Potential Blockers

**None identified yet.** Setup is clean:
- ✅ Java/Maven/Node all installed and verified
- ✅ Dependencies downloading without issues
- ✅ Build process working
- ✅ Port 8080 available (H2 running fine on :8080)

---

## 📝 Notes for Walkthrough

**What to prepare to explain:**
1. Why ReassignmentSuggestion has both orderId AND triggerReason
2. Why Agent publishes an event instead of controller calling service directly
3. Why seed data includes both BUSY and AVAILABLE agents (to test routing)
4. How @Async keeps HTTP fast while re-planning runs

These are the kinds of questions evaluators will ask during walkthrough.

---

## 🚀 Ready to Start Phase 2?

Everything is set up. No blocker. Just need to:

1. Create entities
2. Create repositories
3. Create controllers
4. Test

**Estimated time:** 60-75 minutes (as planned)  
**Next message:** I'll create the entity classes for you and explain each one

---

Generated with Claude Code  
GitHub status: Not yet pushed (will push after Phase 3)
