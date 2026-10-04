# ZipRun AI Reassignment Engine

[![CI](https://github.com/Pratik41/ziprun-reassignment-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/Pratik41/ziprun-reassignment-engine/actions/workflows/ci.yml)

When a delivery agent goes offline mid-shift, this system notices, finds the orders they were carrying, asks an AI (or a rule-based fallback) who should take each one, and queues those suggestions for an ops manager to accept or reject. Nobody has to click anything for the suggestions to appear.

- **Backend:** Spring Boot 3.3 · Java 17 · H2 · `backend/reassignment-engine`
- **Frontend:** Angular 17 · `frontend/reassignment-ui`
- **Design decisions:** [ADR.md](ADR.md)

---

## Run it (≈5 minutes)

**Prerequisites:** Java 17+, Maven 3.8+, Node 18+.  
An LLM key is optional: without one, every suggestion comes from the rule-based fallback and is labelled that way.

```bash
# 1. Backend  ->  http://localhost:8080
cd backend/reassignment-engine
export GEMINI_API_KEY=...        # optional: https://aistudio.google.com/app/apikey
export GROQ_API_KEY=...          # optional fallback provider: https://console.groq.com/keys
mvn spring-boot:run

# 2. Frontend  ->  http://localhost:4200   (new terminal)
cd frontend/reassignment-ui
npm install
npm start
```

PowerShell: use `$env:GEMINI_API_KEY="..."` instead of `export`.

On first start the database is seeded from `data.sql` (brief Addendum A): 5 agents and 8 orders. Priya Sharma (AGT-001) carries ORD-001, ORD-002 and ORD-008; Rahul (AGT-002) and Kiran (AGT-004) are the only AVAILABLE agents. The H2 file lives in `backend/reassignment-engine/data/`; delete that folder to reset.

## Demo: the re-plan path

1. Open http://localhost:4200.
2. In **Demo Controls → Agent Status**, set **Priya Sharma** to **Offline**.
3. Within a few seconds (the UI polls every 3s), ORD-001, ORD-002 and ORD-008 appear under **Orders Pending Reassignment**. Each has an **AUTO RE-PLAN** badge, a recommended agent, a confidence bar, reasoning, and a source tag (`ai:gemini`, or an amber `rule-based (AI fallback: …)` tag).  
   Suggestions are spread across Rahul and Kiran rather than all going to one agent.
4. **Accept** one. The order moves to the new agent and both agents' loads update in the roster.
5. Flip Priya Available → Offline again. No duplicate suggestions appear.
6. **Reject** a suggestion, then click **Get Suggestion** on that order. The AI's reasoning streams in live (SSE) before the new suggestion card appears.
7. Switch **Routing Strategy** between `ai` and `rule-based` at the top of the demo panel. It takes effect immediately, with no restart.

The same flow with curl:

```bash
curl -X PATCH localhost:8080/agents/AGT-001/status -H 'Content-Type: application/json' -d '{"status":"OFFLINE"}'
curl 'localhost:8080/suggestions?status=PENDING'
curl -X PATCH localhost:8080/suggestions/SUGG-XXXX -H 'Content-Type: application/json' -d '{"status":"ACCEPTED"}'
```

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/orders` | `{description, assignedAgentId}` → 201 (agent must be `AVAILABLE`) |
| `GET` | `/orders?status=` | `ASSIGNED`, `REASSIGNMENT_PENDING`, `REASSIGNED`, `DELIVERED` |
| `GET` | `/orders/{id}` | |
| `PATCH` | `/orders/{id}/status` | state machine enforced (409 on illegal transition) |
| `POST` | `/orders/{id}/suggest` | runs the active strategy, persists an `INITIAL` suggestion → 201 |
| `POST` | `/orders/{id}/suggest/stream` | same, as Server-Sent Events: `start`, `token` (reasoning text as generated), `restart` (fallback), then `suggestion` or `error` |
| `POST` | `/orders/{id}/reassign` | manual override `{newAgentId}` (`AVAILABLE` agents only, not the current one; open suggestions for the order are `EXPIRED`) |
| `POST` | `/orders/{id}/keep` | original agent is back online: order returns to `ASSIGNED`, open suggestions `EXPIRED` |
| `GET` | `/agents?status=` | |
| `PATCH` | `/agents/{id}/status` | `OFFLINE` fires the agentic loop asynchronously; returns immediately. 409 if it would leave no AVAILABLE agent |
| `GET` | `/suggestions?status=` | `PENDING`, `ACCEPTED`, `REJECTED`, `EXPIRED` (withdrawn by the system) |
| `PATCH` | `/suggestions/{id}` | `{status: ACCEPTED | REJECTED}`. Accept reassigns the order atomically |
| `GET` / `PUT` | `/routing/strategy` | view / switch active strategy at runtime `{strategy}` |

Errors always have one shape: `{status, error, message, path, timestamp, details}` with 400 (bad input), 404 (unknown id), 409 (conflicts with current state).

## Configuration

All settings are in `application.properties` and can be overridden with environment variables:

| Env var | Default | Meaning |
|---|---|---|
| `ROUTING_STRATEGY` | `ai` | strategy at startup (`ai`, `rule-based`) |
| `LLM_PROVIDERS` | `gemini,groq` | providers tried in order; unconfigured ones are skipped |
| `GEMINI_API_KEY` (or `LLM_API_KEY`) | none | Gemini key |
| `GEMINI_MODEL` | `gemini-3.6-flash` | |
| `GROQ_API_KEY` / `GROQ_MODEL` | none / `openai/gpt-oss-20b` | |
| `LLM_TIMEOUT_MS` | `20000` | per-provider connect/read timeout |

## How it works

```
PATCH /agents/AGT-001/status OFFLINE
  └─ AgentServiceImpl ── publishes AgentOfflineEvent (commit) ──► returns 200
                                    │ @Async @TransactionalEventListener(AFTER_COMMIT)
                                    ▼
                         ReplanEventHandler  (observe → reason → act)
                           1. find orders AGT-001 still owns
                           2. mark them REASSIGNMENT_PENDING
                           3. per order: RoutingService.route(order, RoutingContext.agentOffline(...))
                                ├─ "ai"         → AIAdvisorService → re-plan prompt → LLMGateway (gemini → groq)
                                │                   validate agent ids / confidence, else fall back ─┐
                                └─ "rule-based" → least effective load ◄──────────────────────────────┘
                           4. SuggestionService.createReplanSuggestionIfAbsent (idempotent, row-locked)
                                    ▼
                         PENDING suggestion ──► ops: PATCH /suggestions/{id}  (checkpoint)
```

Key files (under `backend/reassignment-engine/src/main/java/com/ziprun/`):
- `routing/RoutingStrategy.java`: the contract; `routing/strategy/*` the implementations
- `routing/RoutingService.java`: candidates, runtime switch, safety-net fallback
- `routing/RoutingContext.java`: initial vs recovery context and pending load
- `service/ai/PromptBuilder.java`: the two prompts
- `routing/gateway/*`: provider chain, timeouts, typed `LLMException`
- `service/event/ReplanEventHandler.java`: the agentic loop
- `service/suggestion/SuggestionServiceImpl.java`: idempotency and the accept/reject checkpoint

## Tests

```bash
cd backend/reassignment-engine
mvn test
```

40 tests:
- Unit: rule-based ranking and confidence, AI validation and every fallback path, response parsing, prompt differences, provider chain, incremental reasoning extraction.
- End-to-end (MockMvc + async loop + H2): offline → spread suggestions → accept → loads updated; idempotent re-trigger; sibling suggestions rejected on accept; runtime strategy switch; stale suggestions withdrawn when their agent goes busy or offline; offline-mid-routing recommendation refused; keep-with-original-agent; manual reassign retires open suggestions; last AVAILABLE agent protected; structured errors; async fallback when the AI hallucinates; SSE stream (tokens then suggestion, and fallback with `restart`).
