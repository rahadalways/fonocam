@echo off
title Fonocam Desktop
set PY=C:\Users\rahad\AppData\Local\Programs\Python\Python312\python.exe
if not exist "%PY%" set PY=python
"%PY%" "%~dp0Fonocam_Desktop.py"
if errorlevel 1 pause
