@echo off
setlocal

set "ROOT=%~dp0"
set "HCP_DIR=%ROOT%.hcp"
set "CMD=%~1"

if "%CMD%"=="" goto :help
if /I "%CMD%"=="help" goto :help
if /I "%CMD%"=="/help" goto :help

if "%CMD:~0,1%"=="/" set "CMD=%CMD:~1%"
shift /1

set "ARGS="
:collect_args
if "%~1"=="" goto :args_done
set "ARGS=%ARGS% "%~1""
shift /1
goto :collect_args
:args_done

if /I "%CMD%"=="checkpoint" goto :checkpoint
if /I "%CMD%"=="resume" goto :resume
if /I "%CMD%"=="audit" goto :audit
if /I "%CMD%"=="replay" goto :replay
if /I "%CMD%"=="recover" goto :recover
if /I "%CMD%"=="guard" goto :guard
if /I "%CMD%"=="hcp-guard" goto :guard
if /I "%CMD%"=="config" goto :config

echo Unknown HCP command: %CMD%
echo.
goto :help

:checkpoint
powershell -NoProfile -ExecutionPolicy Bypass -File "%HCP_DIR%\checkpoint.ps1" %ARGS%
exit /b %ERRORLEVEL%

:resume
powershell -NoProfile -ExecutionPolicy Bypass -File "%HCP_DIR%\resume.ps1" %ARGS%
exit /b %ERRORLEVEL%

:audit
powershell -NoProfile -ExecutionPolicy Bypass -File "%HCP_DIR%\audit.ps1" %ARGS%
exit /b %ERRORLEVEL%

:replay
powershell -NoProfile -ExecutionPolicy Bypass -File "%HCP_DIR%\replay.ps1" %ARGS%
exit /b %ERRORLEVEL%

:recover
powershell -NoProfile -ExecutionPolicy Bypass -File "%HCP_DIR%\recover.ps1" %ARGS%
exit /b %ERRORLEVEL%

:guard
powershell -NoProfile -ExecutionPolicy Bypass -File "%HCP_DIR%\hcp-guard.ps1" %ARGS%
exit /b %ERRORLEVEL%

:config
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Content -Raw '%HCP_DIR%\hcp-config.json'"
exit /b %ERRORLEVEL%

:help
echo Hermes Continuity Protocol (HCP) wrapper
echo.
echo Usage:
echo   hcp.cmd checkpoint [args]       Save current state
echo   hcp.cmd resume [args]           Load checkpoint
echo   hcp.cmd audit [args]            View timeline + decisions
echo   hcp.cmd replay [args]           State transition history
echo   hcp.cmd recover [args]          Restore from error
echo   hcp.cmd guard [args]            Build + auto-checkpoint + recover
echo   hcp.cmd config                  Show config
echo.
echo Slash-style aliases also work:
echo   hcp.cmd /checkpoint
echo   hcp.cmd /audit
echo   hcp.cmd /replay --full
exit /b 0
