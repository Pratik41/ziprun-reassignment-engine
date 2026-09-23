# 🧪 Manual Dry Run Checklist

## Quick Steps (10-15 minutes)

### STEP 1: Clean Start
```bash
cd C:\ziprun-reassignment-engine
rm -rf backend/reassignment-engine/data/*.db
pkill -f "java" && pkill -f "npm start"
sleep 3
```

### STEP 2: Terminal 1 - Backend
```bash
cd C:\ziprun-reassignment-engine\backend\reassignment-engine
export LLM_API_KEY="your-gemini-api-key-here"
export ROUTING_STRATEGY="ai"
./mvnw spring-boot:run
```
(Get Gemini API key from https://ai.google.dev/gemini-api)
⏳ Wait for: "Started App in X seconds"

### STEP 3: Terminal 2 - Frontend
```bash
cd C:\ziprun-reassignment-engine\frontend\reassignment-ui
npm start
```
⏳ Wait for: "Application bundle generation complete"

Then: http://localhost:4200 → Ctrl+Shift+R

---

## Test Sequence

### TEST 1: Create Orders (2 min)
- Demo Panel Section 1️⃣
- Description: "Electronics delivery"
- Agent: "Amit Patel (AGT-002)"
- Click "Create Order"
- **Verify:** ✅ Amit's count: 0→1

Repeat with:
- Description: "Groceries"
- Agent: "Amit Patel"
- **Verify:** ✅ Amit's count: 1→2

### TEST 2: Trigger Agentic Loop (3 min)
- Demo Panel Section 2️⃣
- Find "Amit Patel"
- Click "🔴 Offline"
- **Wait:** 2-3 seconds

**Verify:**
- ✅ Status changes to OFFLINE
- ✅ 2 suggestion cards appear
- ✅ Both show 🔄 AUTO RE-PLAN badge
- ✅ Different agents recommended

### TEST 3: Verify Unique Reasoning (CRITICAL!)
Read the "AI Reasoning" text on each card.

**✅ CORRECT:**
```
"For ORD-001 (Electronics): Vikram has 2 active orders 
 (lowest). Chosen over Raj (4, offline) and Amit (2, busy) 
 because best capacity."
```

**❌ WRONG:**
```
"Decision: least loaded agent for workload distribution."
```

Each reason must be **DIFFERENT** and mention:
- Order ID
- Order description
- Agent name
- Load numbers

### TEST 4: Accept Suggestion (2 min)
- Click "✓ Accept" on first card
- **Verify:** ✅ Card shows "ACCEPTED"
- **Verify:** ✅ Order status → REASSIGNED

### TEST 5: Reject Suggestion
- Click "✗ Reject" on second card
- **Verify:** ✅ Card shows "REJECTED"
- **Verify:** ✅ Order stays REASSIGNMENT_PENDING

### TEST 6: Manual Reassignment (2 min)
- Click "🔄 Reassign" on rejected order
- Select agent: "Suresh Gupta (AGT-004)"
- Click "✓ Confirm"
- **Verify:** ✅ Order reassigned
- **Verify:** ✅ AGT-004's count increases

---

## Success Checklist

```
□ Backend starts without errors
□ Frontend loads at http://localhost:4200
□ 5 agents visible in demo
□ Can create orders
□ Order counts increment correctly
□ Can set agent OFFLINE
□ Suggestions appear in <2 seconds
□ 🔄 AUTO RE-PLAN badge shows (proves agentic!)
□ Each suggestion has UNIQUE reasoning (not generic)
□ Confidence varies (0.75-0.95, not always 0.95)
□ Accept button works
□ Reject button works  
□ Manual reassignment works
□ Data persists after restart
□ Orders by Agent panel works
□ No console errors
```

**If ALL ✅ = Ready to submit!**

---

## If Something Fails

1. **No agents visible:**
   - Check port 8080 in use: `lsof -i :8080`
   - Delete DB: `rm -rf backend/reassignment-engine/data/*`
   - Restart backend

2. **Generic reasoning (old behavior):**
   - Hard refresh: Ctrl+Shift+R
   - Check application.properties has `gemini-3.6-flash`

3. **Frontend not loading:**
   - Port 4200 in use? Kill process
   - Delete node_modules: `rm -rf node_modules`
   - npm install && npm start

4. **LLM errors:**
   - Check LLM_API_KEY is set: `echo $LLM_API_KEY`
   - Verify it's the correct Gemini key

---

## Quick Verification Commands

```bash
# Check backend is running
curl http://localhost:8080/agents

# Should return 5 agents JSON
# [{"id":"AGT-001","name":"Raj Kumar",...}, ...]

# Check frontend is running
curl http://localhost:4200

# Should return HTML
```

---

**Everything ready? You're good to submit!** 🚀
