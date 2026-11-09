@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set "RUN=%CD%\run"

rem ============================================================
rem  一键停止
rem  用法：scripts\stop.bat              只停 provider + consumer
rem        scripts\stop.bat /all        连 Consul 一起停
rem        scripts\stop.bat /consul     只停 Consul
rem ============================================================

set "DOAPPS=1"
set "DOCONSUL=0"
if /i "%~1"=="/consul" (
    set "DOAPPS=0"
    set "DOCONSUL=1"
)
if /i "%~1"=="/all" set "DOCONSUL=1"
if /i "%~1"=="/all-consul" set "DOCONSUL=1"

set "FAILED="

echo == 停止服务
if "!DOAPPS!"=="1" (
    call :stopapp provider 8080
    call :stopapp consumer 8081
)
if "!DOCONSUL!"=="1" (
    call :stopapp consul 8500
)

rem 被强杀的进程来不及清自己复制出来的 jar 副本
if "!DOAPPS!"=="1" del /q "%RUN%\autumn-sample-boot-*-exec.jar" >nul 2>nul

echo.
echo == 回查端口
set "BUSY="
if "!DOAPPS!"=="1" (
    call :checkport 8080
    call :checkport 30880
    call :checkport 8081
    call :checkport 30881
)
if "!DOCONSUL!"=="1" call :checkport 8500

if not defined BUSY echo    端口全部已释放。
if defined BUSY (
    echo    有端口还在监听，见上。
    exit /b 1
)
if defined FAILED exit /b 1
endlocal
exit /b 0

rem ============================================================
rem  子过程
rem ============================================================

rem getpid <port>  ->  PID=监听该端口的进程号
:getpid
set "PID="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /c:"LISTENING" ^| findstr /c:":%~1 "') do if not defined PID set "PID=%%a"
exit /b 0

rem pidname <pid>  ->  PNAME=进程名，用来确认这个 PID 还是我们的进程（PID 会被复用）
:pidname
set "PNAME="
for /f "tokens=1" %%a in ('tasklist /FI "PID eq %~1" /NH 2^>nul ^| findstr /i /c:".exe"') do if not defined PNAME set "PNAME=%%a"
exit /b 0

rem stopapp <name> <port>
:stopapp
set "_name=%~1"
set "_port=%~2"

set "_pid="
if exist "%RUN%\pids\%_name%.pid" set /p _pid=<"%RUN%\pids\%_name%.pid"

if defined _pid (
    call :pidname !_pid!
    if defined PNAME (
        echo !PNAME! | findstr /i /c:"java.exe" /c:"consul.exe" /c:"autumn-sample-boot" >nul
        if not errorlevel 1 (
            taskkill /PID !_pid! /T /F >nul 2>nul
            if not errorlevel 1 (
                echo    %_name%  已停止（PID !_pid!）
                del /q "%RUN%\pids\%_name%.pid" >nul 2>nul
                exit /b 0
            )
        )
    )
    rem PID 对不上（进程已退出、PID 被别的程序复用了），丢掉这条记录
    del /q "%RUN%\pids\%_name%.pid" >nul 2>nul
)

call :getpid %_port%
if defined PID (
    taskkill /PID !PID! /T /F >nul 2>nul
    if not errorlevel 1 (
        echo    %_name%  已停止（端口 %_port%，PID !PID!）
        del /q "%RUN%\pids\%_name%.pid" >nul 2>nul
        exit /b 0
    )
    echo    [!] %_name% 停不掉（端口 %_port%，PID !PID!），可能需要管理员权限
    set "FAILED=1"
    exit /b 1
)

echo    %_name%  没有在运行
exit /b 0

rem checkport <port>
:checkport
call :getpid %~1
if defined PID (
    call :pidname !PID!
    if defined PNAME (
        echo    [!] 端口 %~1 仍被 PID !PID! ^(!PNAME!^) 占用
    ) else (
        echo    [!] 端口 %~1 仍被 PID !PID! 占用
    )
    set "BUSY=1"
)
exit /b 0
