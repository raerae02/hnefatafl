@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0train.ps1" %*
exit /b %ERRORLEVEL%
