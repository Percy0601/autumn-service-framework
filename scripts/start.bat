@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set "RUN=%CD%\run"
if not exist "%RUN%\pids" md "%RUN%\pids"

rem ============================================================
rem  一键启动：Consul（8500 已在跑就复用）+ provider + consumer
rem  每个服务一个窗口，PID 记到 run\pids\*.pid，stop.bat 一键停
rem
rem  用法：scripts\start.bat
rem        set PROFILE=default ^&^& scripts\start.bat    指定 profile（默认 consul）
rem        set RUNTIME=native  ^&^& scripts\start.bat    跑 native 镜像（默认跑 jar）
rem
rem  前提：consul.exe 已加入 PATH（脚本直接执行 consul.exe，不再探测安装目录）
rem ============================================================

if not defined PROFILE set "PROFILE=consul"
if not defined RUNTIME set "RUNTIME=jar"

rem ---------- 1. Consul ----------
curl -s -o nul --max-time 2 http://127.0.0.1:8500/v1/status/leader
if not errorlevel 1 (
    call :consulpid
    if defined PID (
        echo [1/3] Consul 已在运行（PID !PID!），跳过启动
        echo !PID!>"%RUN%\pids\consul.pid"
    ) else (
        echo [1/3] Consul 已在运行（8500 有响应），跳过启动
    )
    goto :apps
)

where consul.exe >nul 2>nul
if errorlevel 1 (
    echo [ERROR] PATH 里找不到 consul.exe，请把 consul 所在目录加入 PATH 后重试
    exit /b 1
)
echo [1/3] 启动 Consul: consul.exe（来自 PATH）
start "Consul" consul.exe agent -dev -bind=127.0.0.1 -client=127.0.0.1 -log-level=info
call :waitup 8500 40
if errorlevel 1 (
    echo [ERROR] Consul 40 秒内没起来，看那个 Consul 窗口的报错
    exit /b 1
)
call :consulpid
if defined PID echo !PID!>"%RUN%\pids\consul.pid"
echo       Consul 就绪：http://127.0.0.1:8500/ui

:apps
rem ---------- 2. provider / consumer ----------
call :startapp provider 8080
if errorlevel 1 exit /b 1
call :startapp consumer 8081
if errorlevel 1 exit /b 1

echo.
echo 全部就绪。停止：scripts\stop.bat      连 Consul 一起停：scripts\stop.bat /all
endlocal
exit /b 0

rem ============================================================
rem  子过程
rem ============================================================

rem waitup <port> <秒>  ->  errorlevel 0 表示 HTTP 或端口已经响应
:waitup
set /a WAIT=0
:waitup_loop
curl -s -o nul --max-time 2 http://127.0.0.1:%~1/
if not errorlevel 1 exit /b 0
netstat -ano | findstr /c:"LISTENING" | findstr /c:":%~1 " >nul
if not errorlevel 1 exit /b 0
set /a WAIT+=1
if %WAIT% GEQ %~2 exit /b 1
ping -n 2 127.0.0.1 >nul
goto waitup_loop

rem consulpid  ->  PID=consul.exe 的进程号
:consulpid
set "PID="
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq consul.exe" /NH 2^>nul ^| findstr /i /c:".exe"') do if not defined PID set "PID=%%p"
exit /b 0

rem snapshot <镜像名>  ->  SNAP=启动前已有的 PID 列表
:snapshot
set "SNAP=,"
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq %~1" /NH 2^>nul ^| findstr /i /c:".exe"') do set "SNAP=!SNAP!%%p,"
exit /b 0

rem findnew <镜像名>  ->  NEWPID=新冒出来的那个 PID
:findnew
set "NEWPID="
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq %~1" /NH 2^>nul ^| findstr /i /c:".exe"') do (
    echo !SNAP! | findstr /c:",%%p," >nul || set "NEWPID=%%p"
)
exit /b 0

rem startapp <name> <port>
:startapp
set "_name=%~1"
set "_port=%~2"
set "_img=java.exe"

if not defined JAVA_HOME (set "_java=java") else (set "_java=%JAVA_HOME%\bin\java.exe")
set "_module=%CD%\autumn-samples\autumn-sample-boot-%_name%"

if /i "%RUNTIME%"=="native" (
    set "_exe=!_module!\target\autumn-sample-boot-%_name%-native.exe"
    if not exist "!_exe!" (
        echo [ERROR] 找不到 native 镜像 !_exe!，先执行 scripts\build.bat
        exit /b 1
    )
    set "_img=autumn-sample-boot-%_name%-native.exe"
    set "_run=!_exe!"
) else (
    set "_jar="
    for %%j in ("!_module!\target\autumn-sample-boot-%_name%-*-exec.jar") do set "_jar=%%j"
    if not defined _jar (
        echo [ERROR] 找不到 !_module!\target\*-exec.jar，先执行 scripts\build.bat
        exit /b 1
    )
    rem 复制到 run 下再跑：java 会锁住 target 里的 jar，下次 repackage 会失败
    copy /y "!_jar!" "%RUN%\autumn-sample-boot-%_name%-exec.jar" >nul
    set "_java=%_java%"
    set "_run=%RUN%\autumn-sample-boot-%_name%-exec.jar"
)

echo [%_name%] 启动 %_run%
call :snapshot !_img!
if /i "%RUNTIME%"=="native" (
    start "%_name%" "!_run!" --spring.profiles.active=%PROFILE%
) else (
    start "%_name%" "!_java!" -jar "!_run!" --spring.profiles.active=%PROFILE%
)

call :waitup %_port% 60
if errorlevel 1 (
    echo [ERROR] %_name% 60 秒内没起来，看那个 %_name% 窗口的报错
    exit /b 1
)
call :findnew !_img!
if defined NEWPID (
    echo !NEWPID!>"%RUN%\pids\%_name%.pid"
    echo       %_name% 就绪：PID !NEWPID!  http://127.0.0.1:%_port%
) else (
    echo       %_name% 就绪：http://127.0.0.1:%_port% ^(PID 没拿到，stop 时按端口找^)
)
exit /b 0
