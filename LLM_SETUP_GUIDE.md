# LLM Setup Guide - Free Options

You have **three free options** for the LLM integration. Pick one and set it up.

---

## Option 1: Google Gemini (Recommended for Beginners) ✅

**Free tier:** 60 requests/minute (more than enough for testing)

### Steps:

1. **Get API Key:**
   - Go to: https://aistudio.google.com/app/apikeys
   - Click **"Create API Key"**
   - Click **"Create API key in new project"**
   - Copy the API key

2. **Set Environment Variable:**
   ```bash
   # Windows PowerShell
   $env:LLM_API_KEY = "your-api-key-here"
   
   # Windows Command Prompt
   set LLM_API_KEY=your-api-key-here
   
   # Linux/Mac (add to ~/.bashrc or ~/.zshrc)
   export LLM_API_KEY="your-api-key-here"
   ```

3. **Configure (already done in application.properties):**
   ```properties
   llm.provider=gemini
   llm.model=gemini-1.5-flash
   llm.base-url=https://generativelanguage.googleapis.com
   ```

4. **Start Backend:**
   ```bash
   cd backend/reassignment-engine
   mvn spring-boot:run
   ```

---

## Option 2: Groq (Fastest, Free) ⚡

**Free tier:** Unlimited requests (rate-limited, but no request limit like Gemini)

### Steps:

1. **Get API Key:**
   - Go to: https://console.groq.com/keys
   - Sign up (takes 2 minutes)
   - Click **"Create API Key"**
   - Copy the API key

2. **Set Environment Variable:**
   ```bash
   $env:LLM_API_KEY = "your-groq-api-key-here"
   ```

3. **Configure in `application.properties`:**
   ```properties
   llm.provider=groq
   llm.model=llama-3.1-8b-instant
   llm.base-url=https://api.groq.com
   ```

4. **Start Backend:**
   ```bash
   mvn spring-boot:run
   ```

---

## Option 3: Ollama (Local, No API Key Needed) 🏠

**Best for:** Offline testing, complete privacy, no rate limits

### Steps:

1. **Install Ollama:**
   - Download from: https://ollama.ai
   - Install for your OS (Windows/Mac/Linux)

2. **Download Model (first time only):**
   ```bash
   ollama pull llama2
   # Or for faster performance:
   ollama pull mistral
   ```
   This downloads ~3-4GB. First run takes 5-10 minutes.

3. **Start Ollama Server (background):**
   ```bash
   ollama serve
   # You'll see: "Listening on 127.0.0.1:11434"
   ```
   Leave this running in a separate terminal.

4. **Configure in `application.properties`:**
   ```properties
   llm.provider=ollama
   llm.model=llama2
   # Or use: llm.model=mistral
   llm.base-url=http://localhost:11434
   llm.api-key=  # Leave empty for Ollama
   ```

5. **Start Backend:**
   ```bash
   mvn spring-boot:run
   ```

---

## Comparison Table

| Feature | Gemini | Groq | Ollama |
|---------|--------|------|--------|
| **Free Tier** | ✅ 60/min | ✅ Unlimited | ✅ Unlimited |
| **API Key** | ✅ Required | ✅ Required | ❌ Not needed |
| **Setup Time** | 2 min | 2 min | 10 min (download) |
| **Speed** | Good | ⚡ Fastest | Depends on HW |
| **Quality** | Excellent | Good | Good |
| **Best For** | Quick start | Production-like | Offline/testing |
| **Needs Internet** | ✅ Yes | ✅ Yes | ❌ No |

---

## Testing Your Setup

Once you've set environment variables and started the backend:

### 1. Create an order:
```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "description": "Electronics - Bangalore to Indiranagar",
    "assignedAgentId": "AGT-001"
  }'
```

### 2. Request an AI suggestion:
```bash
# Replace {order-id} with the ID from step 1
curl -X POST http://localhost:8080/orders/{order-id}/suggest
```

### 3. Check the response:
Should return JSON with:
```json
{
  "id": "SUGG-...",
  "orderId": "ORD-...",
  "recommendedAgentId": "AGT-...",
  "confidence": 0.85,
  "reasoning": "Agent X selected...",
  "status": "PENDING",
  "triggerReason": "INITIAL"
}
```

### 4. Verify routing strategy can switch:
```bash
# Change routing strategy at runtime
$env:ROUTING_STRATEGY = "ai"
# Restart backend - it will use AI strategy
```

---

## Troubleshooting

### "LLM_API_KEY not found"
```bash
# Verify environment variable is set
echo $env:LLM_API_KEY  # PowerShell
echo $LLM_API_KEY      # Bash
```

### "Could not reach LLM provider"
- Check internet connection
- Verify API key is correct
- Check base URL matches provider
- Try a different provider

### "Ollama connection refused"
```bash
# Make sure Ollama server is running
ollama serve
# Should show: "Listening on 127.0.0.1:11434"
```

### Backend shows "Fallback to rule-based strategy"
This means AI call failed, but the system gracefully degraded. Check logs:
```bash
tail -f logs/application.log | grep "AI\|fallback"
```

---

## Recommendation for This Project

**Start with Gemini** because:
1. ✅ Easiest setup (no downloads)
2. ✅ Fast (good for testing)
3. ✅ Free tier is sufficient for hackathon
4. ✅ No special configuration needed
5. ✅ You can switch to Groq later if needed

Once working with Gemini, you can test failover by:
- Stopping the backend
- Changing provider to Groq
- Restarting
- Verify AI routing still works
- This tests configuration flexibility

---

## Environment Variable Persistence

To make the API key permanent (survives terminal restarts):

### Windows (Permanent):
1. Right-click **"This PC"** or **"My Computer"** → **Properties**
2. Click **"Advanced system settings"**
3. Click **"Environment Variables"** button
4. Click **"New"** (User variables)
5. Variable name: `LLM_API_KEY`
6. Variable value: `your-api-key-here`
7. Click **OK**, restart terminal

### Linux/Mac (Permanent):
```bash
# Add to ~/.bashrc or ~/.zshrc
echo 'export LLM_API_KEY="your-api-key-here"' >> ~/.bashrc

# Apply changes
source ~/.bashrc
```

---

**Choose an option above and let me know which one you picked!**
