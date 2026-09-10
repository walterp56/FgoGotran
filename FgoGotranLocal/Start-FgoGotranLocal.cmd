@echo off
chcp 65001 >nul
setlocal
set "LOCAL_ROOT=%~dp0."

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%LOCAL_ROOT%\scripts\windows\start.ps1" -ProjectRoot "%LOCAL_ROOT%"
set "LOCAL_EXIT=%ERRORLEVEL%"

if not "%LOCAL_EXIT%"=="0" (
  echo.
  echo FgoGotran Local failed to start.
  echo Read docs\TROUBLESHOOTING.md for the matching error.
  pause
)

exit /b %LOCAL_EXIT%
