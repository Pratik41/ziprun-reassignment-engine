@echo off
REM ============================================================
REM  ZipRun AI Reassignment Engine - Quick Start
REM ============================================================

echo.
echo ╔════════════════════════════════════════════════════════╗
echo ║   ZipRun AI Reassignment Engine - Quick Start         ║
echo ║   Deadline: 2:30 PM IST (submit before this!)         ║
echo ╚════════════════════════════════════════════════════════╝
echo.

REM Check if LLM_API_KEY is set
if "%LLM_API_KEY%"=="" (
    echo ⚠️  WARNING: LLM_API_KEY environment variable NOT set!
    echo.
    echo To use AI routing, set your Gemini API key:
    echo.
    echo   $env:LLM_API_KEY = "your-key-here"
    echo.
    echo Get free key: https://aistudio.google.com/app/apikeys
    echo.
    echo Without it, system will use rule-based routing (fallback).
    echo Press ENTER to continue with rule-based routing...
    pause
) else (
    echo ✅ LLM_API_KEY is set. AI routing enabled!
    echo.
)

echo.
echo Step 1: Starting Backend (Spring Boot)...
echo ────────────────────────────────────────────────────────
cd /d "C:\ziprun-reassignment-engine\backend\reassignment-engine"

if errorlevel 1 (
    echo ❌ Failed to change directory!
    pause
    exit /b 1
)

echo Running: mvn spring-boot:run
echo This will take 2-3 minutes on first run (downloading dependencies)
echo.
echo When you see: "Started App in X seconds"
echo The backend is ready! Access it at: http://localhost:8080
echo.
echo Then run RUN_FRONTEND.bat in another terminal.
echo.

mvn spring-boot:run

if errorlevel 1 (
    echo.
    echo ❌ Backend failed to start!
    echo Check error messages above.
    pause
    exit /b 1
)

pause
