@echo off
REM ============================================================
REM  ZipRun UI - Angular Frontend - Quick Start
REM  Run this in a SEPARATE terminal while backend is running
REM ============================================================

echo.
echo ╔════════════════════════════════════════════════════════╗
echo ║   ZipRun Angular Frontend - Quick Start               ║
echo ║   Make sure backend is running first!                 ║
echo ╚════════════════════════════════════════════════════════╝
echo.

cd /d "C:\ziprun-reassignment-engine\frontend\reassignment-ui"

if errorlevel 1 (
    echo ❌ Failed to change directory!
    pause
    exit /b 1
)

echo Step 1: Install dependencies (first time only)
echo ────────────────────────────────────────────────────────
if exist node_modules (
    echo ✅ node_modules exists, skipping npm install
) else (
    echo Running: npm install
    echo This may take a minute...
    call npm install

    if errorlevel 1 (
        echo ❌ npm install failed!
        pause
        exit /b 1
    )
    echo ✅ Dependencies installed
)

echo.
echo Step 2: Starting Angular dev server...
echo ────────────────────────────────────────────────────────
echo Running: npm start
echo.
echo When you see: "Application bundle generation complete"
echo The app will open at: http://localhost:4200
echo.
echo Use this terminal to see Angular logs.
echo.

call npm start

pause
