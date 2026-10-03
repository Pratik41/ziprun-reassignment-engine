@echo off
REM ============================================================
REM  ZipRun AI Reassignment Engine - start the Angular UI (Windows)
REM  Run while the backend (RUN_PROJECT.bat) is running.
REM ============================================================

cd /d "%~dp0frontend\reassignment-ui" || (echo Frontend folder not found & pause & exit /b 1)

if not exist node_modules (
    echo Installing dependencies...
    call npm install || (echo npm install failed & pause & exit /b 1)
)

echo Starting UI on http://localhost:4200 ...
call npm start
