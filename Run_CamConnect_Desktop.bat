@echo off
title CamConnect Desktop
set PY=C:\Users\rahad\AppData\Local\Programs\Python\Python312\python.exe
if not exist "%PY%" set PY=python
"%PY%" "%~dp0CamConnect_Desktop.py"
if errorlevel 1 pause
