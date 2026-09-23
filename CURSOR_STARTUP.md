# 🎯 Cursor IDE Quick Start Guide

## ✅ Automatic Setup (5 minutes)

When you open this project in Cursor and run it, **agents will be auto-seeded**. Here's what happens:

### Backend Auto-Configuration:
```
1. Hibernate creates database schema
2. data.sql runs automatically (5 agents seeded)
3. H2 file-based database persists at ./data/ziprun-db
4. API ready at http://localhost:8080
```

**5 Pre-Seeded Agents:**
- AGT-001: Raj Kumar (AVAILABLE)
- AGT-002: Amit Patel (AVAILABLE)
- AGT-003: Vikram Singh (AVAILABLE)
- AGT-004: Suresh Gupta (AVAILABLE)
- AGT-005: Ravi Nair (AVAILABLE)

### Frontend Auto-Setup:
```
1. npm install runs (if needed)
2. Angular dev server starts
3. UI ready at http://localhost:4200
```

---

## 🚀 Two Ways to Run

### Option A: Via Cursor Terminal (Recommended)

**Terminal 1 - Backend:**
```bash
cd backend/reassignment-engine
export LLM_API_KEY="your-gemini-key"  # Get from https://ai.google.dev
export ROUTING_STRATEGY="ai"
./mvnw spring-boot:run
```

**Terminal 2 - Frontend:**
```bash
cd frontend/reassignment-ui
npm install
npm start
```

Then open: http://localhost:4200

### Option B: Run JAR Directly

**Backend:**
```bash
cd backend/reassignment-engine
./mvnw clean package
export LLM_API_KEY="your-gemini-key"
java -jar target/reassignment-engine-1.0-SNAPSHOT.jar
```

**Frontend:**
```bash
cd frontend/reassignment-ui
npm install
npm start
```

---

## ⚙️ What's Already Configured

✅ **application.properties:**
- H2 database: file-based (`./data/ziprun-db`)
- Auto-schema creation: `ddl-auto=update`
- Seed data auto-load: `spring.sql.init.mode=always`
- Gemini API: `gemini-3.6-flash` (latest)
- Async thread pools: Pre-configured
- CORS: Enabled for localhost:4200

✅ **data.sql:**
- 5 agents pre-defined
- Idempotent inserts (won't duplicate)
- Auto-runs on startup

✅ **pom.xml & package.json:**
- All dependencies included
- Versions tested
- Build ready to go

---

## 🔑 Environment Variables Needed

### Required for LLM:
```bash
export LLM_API_KEY="your-gemini-api-key"
```

**Get free API key:** https://ai.google.dev/gemini-api

### Optional (defaults provided):
```bash
export ROUTING_STRATEGY="ai"  # default: ai
```

---

## 🧪 Verify It's Working

### Check Backend:
```bash
# Should return JSON array with 5 agents
curl http://localhost:8080/agents
```

### Check Frontend:
```
Open http://localhost:4200 in browser
Hard refresh: Ctrl+Shift+R
Should see 5 agents in demo panel
```

---

## 📊 First Test (2 minutes)

1. **Create Order:**
   - Demo Panel → Section 1
   - Description: "Test delivery"
   - Agent: AGT-002 (Amit Patel)
   - Click "Create Order"

2. **Trigger Agentic Loop:**
   - Demo Panel → Section 2
   - Find "Amit Patel"
   - Click "🔴 Offline"

3. **See Suggestion:**
   - "Orders Pending Reassignment" panel
   - Should show order with 🔄 AUTO RE-PLAN badge
   - Click "✓ Accept" to reassign

---

## 🐛 Troubleshooting

### Agents not visible:
- Backend not started? Check port 8080
- Database issue? Delete `./data/ziprun-db*` files, restart
- Browser cache? Ctrl+Shift+R to hard refresh

### LLM errors in logs:
- Missing LLM_API_KEY? Set env var + restart
- API timeout? Check internet connection
- Model not found? Verify `gemini-3.6-flash` in application.properties

### Frontend not loading:
- Port 4200 taken? Kill process or change port
- npm issues? Delete node_modules, run `npm install` again
- Build errors? Check Node.js version (18+)

---

## 📁 Key Files to Know

```
backend/reassignment-engine/
├── src/main/resources/
│   ├── application.properties     ← All configs
│   └── data.sql                   ← Agent seed data
├── pom.xml                        ← Maven config
└── src/main/java/com/ziprun/
    ├── controller/                ← REST endpoints
    ├── service/                   ← Business logic
    └── domain/                    ← Models

frontend/reassignment-ui/
├── src/app/
│   ├── components/                ← UI panels
│   ├── services/                  ← API + refresh
│   └── app.component.*            ← Main layout
├── package.json                   ← npm config
└── angular.json                   ← Angular config
```

---

## ✨ What You'll See

**Backend Console:**
```
2026-09-23 14:36:38 INFO App: Started App in 8.056 seconds
HikariPool-1 - Added connection conn0: url=jdbc:h2:file:./data/ziprun-db
Processing PersistenceUnitInfo
INSERT INTO agents... (5 agents seeded)
Tomcat started on port 8080
```

**Frontend UI:**
```
🎮 Demo Controls
  1️⃣ Create Order
  2️⃣ Agent Status Control
  
📊 Current State
  Total Agents: 5
  Available: 5
  Offline: 0
  
📋 Orders Pending Reassignment
  (empty until you create orders)
```

---

## 🎯 Key Points

- ✅ No database migration needed (auto-created)
- ✅ No manual agent creation (auto-seeded)
- ✅ No special setup (just env vars)
- ✅ Data persists across restarts
- ✅ Both backend & frontend hot-reload during dev
- ✅ Full agentic loop working end-to-end

**Everything is ready to go!** 🚀
