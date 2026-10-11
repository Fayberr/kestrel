@echo off
REM Launch a Kestrel server from this release zip on Windows.
REM
REM   start.bat                     world runtime\play\world, port 25565
REM   start.bat --port 25566        a different port
REM   start.bat --online            require Mojang authentication
REM   set KESTREL_MEMORY=4G         bigger heap (2G default)
REM
REM Offline mode is the default: it is what the project's own live server runs,
REM and it lets any client join without a Mojang session. Do not expose an
REM offline-mode server to the internet, because anyone can then connect under
REM any name. Pass --online to authenticate instead.
REM
REM Anything else on the command line is handed to the server unchanged, so any
REM KestrelServer flag works here too.
setlocal enableextensions enabledelayedexpansion
cd /d "%~dp0"

if not defined KESTREL_MEMORY set "KESTREL_MEMORY=2G"
set "PORT=25565"
set "AUTH=--offline"

REM Known flags are consumed here; everything else is passed through.
set "EXTRAS="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--port" (
  if "%~2"=="" ( echo [kestrel] --port needs a value & exit /b 2 )
  set "PORT=%~2"
  shift
  shift
  goto parse
)
if /i "%~1"=="--online" (
  set "AUTH=--online"
  shift
  goto parse
)
if /i "%~1"=="--offline" (
  set "AUTH=--offline"
  shift
  goto parse
)
set "EXTRAS=!EXTRAS! %1"
shift
goto parse
:parsed

if not exist "runtime\cp.txt" (
  echo [kestrel] first run: setting up the runtime ^(this needs network access once^)
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
  if errorlevel 1 (
    echo [kestrel] setup failed. See the messages above.
    exit /b 1
  )
)

set "JAVA="
if defined KESTREL_JAVA set "JAVA=%KESTREL_JAVA%"
if not defined JAVA if exist "%~dp0runtime\jdk\bin\java.exe" set "JAVA=%~dp0runtime\jdk\bin\java.exe"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for %%j in (java.exe) do set "JAVA=%%~$PATH:j"
if not defined JAVA (
  echo [kestrel] no Java found. Run setup.ps1, or install Temurin 25 and set KESTREL_JAVA.
  exit /b 1
)

if not defined KESTREL_WORLD set "KESTREL_WORLD=%~dp0runtime\play\world"
if not exist "%KESTREL_WORLD%" mkdir "%KESTREL_WORLD%"

set "CP="
set /p CP=<"runtime\cp.txt"

set "BUILD=unknown"
if exist "BUILD-COMMIT.txt" set /p BUILD=<"BUILD-COMMIT.txt"

set "SAVING=on"
if defined EXTRAS (
  echo !EXTRAS! | findstr /C:"--no-save" >nul
  if not errorlevel 1 set "SAVING=off (--no-save)"
)

echo [kestrel] java:  %JAVA%
echo [kestrel] build: %BUILD%
echo [kestrel] world: %KESTREL_WORLD%
echo [kestrel] port:  %PORT% (%AUTH%, saving %SAVING%)

"%JAVA%" -Xmx%KESTREL_MEMORY% ^
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="%~dp0oom.hprof" ^
  -XX:+UseG1GC ^
  -XX:G1PeriodicGCInterval=10000 -XX:G1PeriodicGCSystemLoadThreshold=0 ^
  -XX:+G1PeriodicGCInvokesConcurrent ^
  -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 ^
  -Dkestrel.datapack="%~dp0runtime\packdump\data" ^
  -cp "%CP%" ^
  dev.kestrel.net.KestrelServer "%KESTREL_WORLD%" %PORT% %AUTH% %EXTRAS%

endlocal
