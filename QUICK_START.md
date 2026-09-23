# ⚡ QUICK START - 2 Minutes to Running System

## Terminal 1: Start Backend

```powershell
cd C:\ziprun-reassignment-engine\backend\reassignment-engine
mvn spring-boot:run
```

**Wait for:** `Started App in X.XXX seconds`

Backend ready at: **http://localhost:8080**

---

## Terminal 2: Start Frontend (NEW TERMINAL/COMMAND PROMPT)

```powershell
cd C:\ziprun-reassignment-engine\frontend\reassignment-ui
npm start
```

**Wait for:** `Application bundle generation complete`

Frontend ready at: **http://localhost:4200**

---

## Verify Both Are Running

### Test Backend:
```bash
curl http://localhost:8080/agents
```

Should return: `[]` (empty array, no seed data)

### Test Frontend:
Open browser: **http://localhost:4200**

You should see Angular welcome page

---

## Features Ready RIGHT NOW:

✅ **Mock LLM** - Returns realistic AI suggestions locally (no API key needed!)  
✅ **Rule-Based Fallback** - Always works, uses least-loaded agent logic  
✅ **3-Tier Architecture** - Controller → Service → Repository  
✅ **REST APIs** - Create orders, get suggestions, accept/reject  
✅ **Event System** - Agentic loop infrastructure ready  

---

## Create Sample Data (via curl):

```bash
# Create order
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "description": "Test delivery from Bangalore to Indiranagar",
    "assignedAgentId": "AGT-001"
  }'

# Response includes order ID - save it

# Request AI suggestion
curl -X POST http://localhost:8080/orders/[order-id]/suggest

# See AI confidence + reasoning!
```

---

**Everything is ready. Just open 2 terminals and start both!** 🚀
