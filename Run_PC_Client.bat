@echo off
title CamConnect - 1-Click Installer and Launcher
echo ==========================================================
echo           📐 CamConnect PC Client Launcher
echo ==========================================================
echo.

:: Check if Python is installed
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python is not installed on your system!
    echo [ভুল] আপনার পিসিতে Python ইনস্টল করা নেই!
    echo.
    echo Please install Python first to run the zero-lag HD stream software.
    echo Make sure to check the box "Add Python to PATH" during installation.
    echo.
    echo Opening Python download page...
    timeout /t 5
    start https://www.python.org/downloads/
    pause
    exit
)

echo [SUCCESS] Python is installed.
echo [INFO] Checking/Installing dependencies (opencv-python, requests)...
echo.

:: Install required python libraries
python -m pip install --upgrade pip --quiet
python -m pip install opencv-python requests --quiet

if %errorlevel% neq 0 (
    echo [WARNING] Automatic dependency install failed. Trying alternate install method...
    pip install opencv-python requests
)

echo.
echo ==========================================================
echo [LAUNCHING] Starting CamConnect PC App...
echo ==========================================================
echo.

:: Run the client
python camconnect_client.py

echo.
echo Application closed. Thanks for using CamConnect!
pause
