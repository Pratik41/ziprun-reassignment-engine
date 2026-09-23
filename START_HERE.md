# 🚀 START HERE - Your 90-Minute Sprint to Submission

**Deadline:** 2:30 PM IST TODAY  
**Current time:** ~12:45 PM (approximately)  
**Time remaining:** ~105 minutes  
**Your mission:** Get working UI + demo video + submit

---

## 📍 WHERE YOU ARE NOW

✅ **DONE:**
- Backend 100% complete (APIs, services, routing engine, AI integration)
- 3-tier architecture (Controller → Service → Repository)
- ADR documentation (8 entries)
- GitHub repo with all code
- LLM integration (Gemini/Groq/Ollama support)

⏳ **TODO:**
1. Get Gemini API key (2 min)
2. Start backend (2 min)
3. Start Angular frontend (2 min)
4. Build basic UI (30 min) ← CRITICAL PATH
5. Test workflow (20 min)
6. Record demo video (15 min)
7. Final push + submit (10 min)

---

## ⏰ DETAILED TIMELINE (105 minutes)

### **00:00-00:05 | GET GEMINI API KEY** (5 min)
```
1. Open: https://aistudio.google.com/app/apikeys
2. Click "Create API Key" → "Create API key in new project"
3. Copy the key
4. Go to your terminal
5. Run: $env:LLM_API_KEY = "paste-your-key-here"
```

**Backup:** If Gemini doesn't work, system falls back to rule-based routing (still works!)

---

### **00:05-00:10 | START BACKEND** (5 min)

**Option A: Use Quick-Start Script (Recommended)**
```powershell
# Double-click this file:
C:\ziprun-reassignment-engine\RUN_PROJECT.bat
```

**Option B: Manual (if script doesn't work)**
```powershell
cd C:\ziprun-reassignment-engine\backend\reassignment-engine
mvn spring-boot:run
```

**What to look for:**
```
✅ [INFO] Started App in X.XXX seconds
✅ Server running on port 8080
```

**Leave this terminal open!** Don't close it.

---

### **00:10-00:15 | START ANGULAR FRONTEND** (5 min)

**In a SEPARATE terminal/command prompt:**

```powershell
# Double-click this file:
C:\ziprun-reassignment-engine\RUN_FRONTEND.bat
```

**Or manually:**
```powershell
cd C:\ziprun-reassignment-engine\frontend\reassignment-ui
npm start
```

**What to look for:**
```
✅ Application bundle generation complete
✅ Browser opens http://localhost:4200
✅ You see Angular welcome page
```

**Leave this terminal open too!**

---

### **00:15-00:45 | BUILD ANGULAR UI** (30 min)

**This is your critical path. Focus here.**

You need to build **3 components** to show the system working:

#### Component 1: Agent Roster (5 minutes)
```
File: src/app/components/agent-roster/agent-roster.component.ts
File: src/app/components/agent-roster/agent-roster.component.html

What it does:
- Fetch agents from GET /agents
- Display in a table/list
- Show status: 🟢 AVAILABLE | 🟡 BUSY | 🔴 OFFLINE

Minimal HTML:
<div *ngFor="let agent of agents">
  <span>{{ agent.name }}</span>
  <span [class]="'status-' + agent.status">{{ agent.status }}</span>
</div>
```

#### Component 2: Suggestion List (10 minutes)
```
File: src/app/components/suggestion-list/suggestion-list.component.ts
File: src/app/components/suggestion-list/suggestion-list.component.html

What it does:
- Poll GET /suggestions?status=PENDING every 3 seconds
- Display pending reassignments
- Show for each:
  * Order ID
  * Current agent
  * Recommended agent + confidence
  * AI reasoning (as-is from API)
  * Accept/Reject buttons

Minimal HTML:
<div *ngFor="let sugg of suggestions">
  <h4>Order {{ sugg.orderId }}</h4>
  <p>Recommended: {{ sugg.recommendedAgentId }}</p>
  <p>Confidence: {{ (sugg.confidence * 100) | number:'1.0-0' }}%</p>
  <p>Reason: {{ sugg.reasoning }}</p>
  <button (click)="accept(sugg.id)">ACCEPT</button>
  <button (click)="reject(sugg.id)">REJECT</button>
</div>
```

#### Component 3: Services (10 minutes)
```
File: src/app/services/suggestion.service.ts

Methods:
- getSuggestions(): Observable
- acceptSuggestion(id): Observable
- rejectSuggestion(id): Observable

Also create:
- agent.service.ts (getAgents)
- order.service.ts (createOrder, getOrders, suggestReassignment)
```

---

### **00:45-01:05 | TEST WORKFLOW** (20 min)

**Terminal 1: Test via curl**
```bash
# Create order
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"description":"Test delivery","assignedAgentId":"AGT-001"}'
# Note the returned order ID

# Request AI suggestion
curl -X POST http://localhost:8080/orders/[order-id]/suggest

# Should see: confidence + reasoning from AI
```

**Browser: Check UI**
```
1. Open http://localhost:4200
2. See agent roster populated (5 agents from seed data)
3. See pending suggestion appear (from step above)
4. Click ACCEPT
5. Check that order status changed to REASSIGNED
```

**Terminal 1: Trigger agentic loop**
```bash
# Set an agent to OFFLINE (this triggers the agentic loop!)
curl -X PATCH http://localhost:8080/agents/AGT-001/status \
  -H "Content-Type: application/json" \
  -d '{"status":"OFFLINE"}'

# Check backend logs - should see:
# [INFO] Agent going OFFLINE: id=AGT-001, name=Priya Sharma
# [INFO] Publishing AgentOfflineEvent
```

**Browser: Check re-plan badge**
```
1. UI should auto-refresh (polling)
2. New pending suggestion should appear
3. This one has trigger reason = AGENT_OFFLINE (show a badge!)
```

---

### **01:05-01:20 | RECORD 5-MINUTE DEMO** (15 min)

**Use Loom (easiest):**
1. Go to https://www.loom.com (free, no download)
2. Click "Start Recording"
3. Select your screen (show terminal + browser)
4. Record this flow:

```
[0:00] Show backend terminal running
[0:30] Show Angular app at localhost:4200
[1:00] curl: Create order
[1:30] Show suggestion appears in UI
[2:00] curl: Accept suggestion
[2:30] Show order status changes
[3:00] curl: Set agent offline
[3:30] Show re-plan suggestion appears with badge
[4:00] Show agentic loop logs
[4:30] curl: Reject re-plan
[5:00] Done - save video
```

**Copy the Loom link**

---

### **01:20-01:30 | FINAL PUSH** (10 min)

```bash
cd C:\ziprun-reassignment-engine

# Add demo link to README
# (Edit README.md, add: "Demo: [Loom link]")

git add -A
git commit -m "Final submission: Working UI + demo

- Angular UI showing agents + pending suggestions
- Accept/Reject buttons functional
- Agentic loop badge displaying
- Demo video: [loom-link]

Ready for submission at 2:30 PM IST"

git push origin main
```

**Verify:**
1. Open https://github.com/Pratik41/ziprun-reassignment-engine
2. See latest code
3. README shows demo link ✓

---

## 📋 SUBMISSION REQUIREMENTS

When you submit at ~2:15 PM (15 min buffer), you need:

✅ **GitHub Link:**
```
https://github.com/Pratik41/ziprun-reassignment-engine
```

✅ **Demo Video Link:**
```
https://loom.com/share/[your-video-id]
```

✅ **README.md mentions:**
- How to run (< 5 min setup)
- Architecture
- API endpoints
- Demo video link

✅ **Code quality:**
- Builds without errors ✓
- Backend running ✓
- Frontend running ✓
- UI showing data ✓
- Accept/Reject working ✓

✅ **ADR.md:**
- 8 entries ✓
- Extensibility explained ✓
- Deliberate exclusions ✓

---

## 🎯 PRIORITY ORDER (if time gets tight)

1. ✅ **MUST HAVE:**
   - Backend working
   - GitHub repo public
   - README with setup

2. ✅ **SHOULD HAVE:**
   - Basic Angular UI (agent list + suggestions)
   - Accept/Reject buttons
   - Demo video

3. ⏳ **NICE TO HAVE:**
   - Polish styling
   - Error handling
   - Loading states
   - (Skip these if time runs out)

**Strategy:** Simple, working floor beats incomplete ceiling.

---

## 🆘 TROUBLESHOOTING (Quick Fixes)

### Backend won't start
```bash
# Clear build
cd backend/reassignment-engine
mvn clean

# Try again
mvn spring-boot:run
```

### Angular npm install hangs
```bash
# Kill it
Ctrl+C

# Clear cache
npm cache clean --force

# Try again
npm install
npm start
```

### API not responding from Angular
```bash
# Check CORS is configured
# Should be in application.properties:
# server.cors.allowed-origins=http://localhost:4200

# Restart backend if changed
```

### Gemini API key not working
```
No problem! System falls back to rule-based routing.
Still fully functional - just less "intelligent" recommendations.
Acceptable for hackathon demo.
```

---

## ✅ FINAL CHECKLIST (Before hitting submit)

- [ ] Backend running: `curl http://localhost:8080/agents` returns data
- [ ] Frontend running: http://localhost:4200 shows UI
- [ ] Agents display: See 5 agents with status badges
- [ ] Create order works: curl POST works, order created
- [ ] Suggestion works: curl POST /suggest returns recommendation
- [ ] UI updates: Suggestion appears in browser
- [ ] Accept/Reject works: Click button, order status changes
- [ ] Agent offline: curl PATCH status to OFFLINE
- [ ] Agentic loop fires: Check backend logs for "Publishing AgentOfflineEvent"
- [ ] Re-plan appears: New suggestion in UI with [Re-plan] badge
- [ ] Demo video: Loom recording all above steps
- [ ] GitHub pushed: Latest code + README with demo link
- [ ] README works: Setup takes < 5 minutes

---

## 🎬 ACTION NOW!

**You have 105 minutes.** Your backend is done (the hard part). 

1. **Right now:** Get Gemini key (2 min)
2. **Next:** Run the .bat files to start backend + frontend (10 min)
3. **Then:** Build 3 simple components (30 min)
4. **Then:** Test + demo (35 min)
5. **Finally:** Push + submit (10 min)

---

**YOU'VE GOT THIS!** 🚀 

The backend is production-ready. The UI is just display logic. You'll finish with 30 minutes to spare.

**Go go go!** ⏰
