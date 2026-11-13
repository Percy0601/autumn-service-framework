@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
set "RUN=%ROOT%\run"
if not exist "%RUN%\pids" md "%RUN%\pids" >nul

rem ============================================================
rem  multicast 注册发现模式一键启动（Windows）：provider + consumer
rem  不启动 Consul；组播由框架自带 MulticastBackend 实现，无需外部注册中心。
rem
rem  用法：
rem    scripts\start-multicast.bat
rem    set RUNTIME=native ^&^& scripts\start-multicast.bat
rem    set MULTICAST_IP=224.0.0.1 ^&^& scripts\start-multicast.bat
rem    set MULTICAST_INTERFACE=Ethernet ^&^& scripts\start-multicast.bat
rem    set APP_ARGS=--server.port=18080 ^&^& scripts\start-multicast.bat
rem
rem  说明：
rem   - --spring.profiles.active=multicast 加载 application-multicast.properties；
rem     同时显式传 --autumn.registry-type=multicast，让 native 镜像（AOT 编译时可能
rem     没有该 profile 资源）也能生效，命令行参数优先级最高。
rem   - 与 start.bat 保持一致：jar 先复制到 run\ 再跑，避免 java 锁住 target 里的 jar。
rem   - 失败路径统一用 exit /b 1 单独标签返回，不要写在 if (...) 块里（会丢退出码）。
rem
rem  停止：scripts\stop.bat（multicast 模式没有 Consul，不用加 /all）
rem ============================================================

if not defined PROFILE set "PROFILE=multicast"
if not defined RUNTIME set "RUNTIME=jar"
if not defined MULTICAST_IP set "MULTICAST_IP=224.0.0.1"
if not defined MULTICAST_PORT set "MULTICAST_PORT=5555"
if not defined TIMEOUT set "TIMEOUT=60"

set "REGISTRY_ARGS=--autumn.registry-type=multicast --autumn.multicast.ip=%MULTICAST_IP% --autumn.multicast.port=%MULTICAST_PORT%"
if defined MULTICAST_INTERFACE set "REGISTRY_ARGS=%REGISTRY_ARGS% --autumn.network-interface=%MULTICAST_INTERFACE%"
if defined APP_ARGS set "REGISTRY_ARGS=%REGISTRY_ARGS% %APP_ARGS%"

echo ============================================================
echo  multicast 注册发现启动（不启动 Consul）
echo    profile : %PROFILE%
echo    runtime : %RUNTIME%
echo    组播    : %MULTICAST_IP%:%MULTICAST_PORT%
echo ============================================================

call :startapp provider 8080
if errorlevel 1 exit /b 1
call :startapp consumer 8081
if errorlevel 1 exit /b 1

echo.
echo 全部就绪。停止：scripts\stop.bat
endlocal
exit /b 0

rem ============================================================
rem  子过程
rem ============================================================

rem startapp <name> <port>
:startapp
set "_name=%~1"
set "_port=%~2"
set "_img=java.exe"

if not defined JAVA_HOME (set "_java=java") else (set "_java=%JAVA_HOME%\bin\java.exe")
set "_module=%ROOT%\autumn-samples\autumn-sample-boot-%_name%"

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
    copy /y "!_jar!" "%RUN%\autumn-sample-boot-%_name%-exec.jar" >nul
    set "_run=%RUN%\autumn-sample-boot-%_name%-exec.jar"
)

echo [%_name%] 启动 !_run!
call :snapshot !_img!
if /i "%RUNTIME%"=="native" (
    start "%_name%" "!_run!" --spring.profiles.active=%PROFILE% !REGISTRY_ARGS!
) else (
    start "%_name%" "!_java!" -jar "!_run!" --spring.profiles.active=%PROFILE% !REGISTRY_ARGS!
)

call :waitup %_port% %TIMEOUT%
if errorlevel 1 (
    echo [ERROR] %_name% %TIMEOUT% 秒内没起来，看那个 %_name% 窗口的报错
    exit /b 1
)
call :findnew !_img!
if defined NEWPID (
    echo !NEWPID!>"%RUN%\pids\%_name%.pid"
    echo       %_name% 就绪：PID !NEWPID!  http://127.0.0.1:%_port%
) else (
    echo       %_name% 就绪：http://127.0.0.1:%_port% ^(PID 没拿到，stop.bat 会按端口找^)
)
exit /b 0

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

rem pidof <镜像名>  ->  PIDS=该镜像名所有实例的 PID，逗号分隔
rem 不能用 tasklist 输出里的 ".exe" 判断：超过 25 字符的镜像名会被截断显示
:pidof
set "PIDS="
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq %~1" /NH 2^>nul') do call :keepnum %%p
exit /b 0

:keepnum
echo %~1| findstr /r "^[0-9][0-9]*$" >nul || exit /b 0
set "PIDS=!PIDS!%~1,"
exit /b 0

rem snapshot <镜像名>  ->  SNAP=启动前已有的 PID 列表，形如 ,1234,
:snapshot
call :pidof %~1
set "SNAP=,"
if defined PIDS set "SNAP=,!PIDS!"
exit /b 0

rem findnew <镜像名>  ->  NEWPID=新冒出来的那个 PID
rem PIDS 本身以逗号结尾，逗号在 FOR IN 子句里就是分隔符，直接展开即可
:findnew
set "NEWPID="
call :pidof %~1
if not defined PIDS exit /b 0
for %%p in (!PIDS!) do (
    echo !SNAP! | findstr /c:",%%p," >nul || set "NEWPID=%%p"
)
exit /b 0
