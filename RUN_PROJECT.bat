@echo off
REM ============================================================
REM  ZipRun AI Reassignment Engine - start the backend (Windows)
REM  Then run RUN_FRONTEND.bat in a second terminal.
REM ============================================================

if "%GEMINI_API_KEY%%LLM_API_KEY%%GROQ_API_KEY%"=="" (
    echo No LLM key set - suggestions will use the rule-based fallback.
    echo To enable AI:  set GEMINI_API_KEY=your-key   ^(and/or GROQ_API_KEY^)
    echo.
)

cd /d "%~dp0backend\reassignment-engine" || (echo Backend folder not found & pause & exit /b 1)

echo Starting backend on http://localhost:8080 (first run downloads dependencies)...
call mvn spring-boot:run
if errorlevel 1 (
    echo Backend failed to start. Java 17+ and Maven are required.
    pause
    exit /b 1
)
