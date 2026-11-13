@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
set "RUN=%ROOT%\run"
set "LAUNCH_DIR=%RUN%\launchers"
if not exist "%RUN%\pids" md "%RUN%\pids" >nul
if not exist "%LAUNCH_DIR%" md "%LAUNCH_DIR%" >nul

rem ============================================================
rem  GraalVM native image 启动（Windows）
rem  直接跑 build.bat 产出的 *-native.exe，不经过 java -jar：
rem    autumn-samples\autumn-sample-boot-provider\target\autumn-sample-boot-provider-native.exe
rem    autumn-samples\autumn-sample-boot-consumer\target\autumn-sample-boot-consumer-native.exe
rem
rem  本脚本只负责「跑」，不负责「建」；镜像不存在时会提示先执行 scripts\build.bat。
rem
rem  用法：scripts\start-native.bat                       provider + consumer（8500 上没有 Consul 就起一个）
rem        scripts\start-native.bat provider              只起 provider
rem        scripts\start-native.bat consumer              只起 consumer
rem        scripts\start-native.bat /skip-consul          完全复用已在跑的 Consul，自己不起
rem        set PROFILE=default ^&^& scripts\start-native.bat        换 profile（默认 consul，设成空值则不传）
rem        set TIMEOUT=120 ^&^& scripts\start-native.bat            就绪等待秒数（默认 60）
rem        set APP_ARGS=--server.port=18080 ^&^& scripts\start-native.bat   额外的应用参数
rem
rem  停止：scripts\stop.bat
rem        native 进程名同样以 autumn-sample-boot 开头，pid 文件与端口两条路 stop.bat 都认。
rem
rem  前提：consul.exe 已在 PATH 里（8500 已有 Consul 则直接复用，不再重复启动）
rem
rem  日志：
rem    控制台            每个服务一个独立的 cmd 窗口，日志实时打在上面（和 java -jar 一样）
rem    logs\*.log        logback 的文件日志，落在仓库根目录 logs\ 下（.gitignore 已忽略 /logs）
rem                      provider: logs\boot-sample-provider-log.<date>.<i>.log
rem                      consumer: logs\boot-sample-consumer-log.<date>.<i>.log
rem
rem  踩过的三个坑，改脚本前先看一下：
rem   1) 每个服务先写一个 run\launchers\<name>-native.bat 包装脚本，再 start 出去。
rem      工作目录和参数都落在包装脚本里，避免在 bat 里嵌套引号——
rem      cmd 会吃掉「start ... cmd /c ""exe" args > "log" 2>&1"」这类写法里的重定向。
rem      另外别在这里再重定向 stdout：重定向后窗口就是空的，控制台里啥都看不到。
rem   2) 包装脚本要用 cmd /c 显式跑。直接 start 一个 .bat 会走文件关联的 cmd /K，
rem      应用退出后还留一个空 shell 窗口。
rem   3) 主流程里不要用「if ... ( ... exit /b 1 )」：块内的 exit /b 会提前结束脚本，
rem      但退出码会丢成 0。失败路径统一走 :err_* 标签。
rem ============================================================

if not defined PROFILE set "PROFILE=consul"
if not defined TIMEOUT set "TIMEOUT=60"

set "SERVICES=provider consumer"
set "SKIP_CONSUL=0"

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="provider" (
    set "SERVICES=provider"
    shift
    goto parse
)
if /i "%~1"=="consumer" (
    set "SERVICES=consumer"
    shift
    goto parse
)
if /i "%~1"=="all" (
    set "SERVICES=provider consumer"
    shift
    goto parse
)
if /i "%~1"=="/skip-consul" goto parse_skip
if /i "%~1"=="--skip-consul" goto parse_skip
if /i "%~1"=="/?" goto usage
if /i "%~1"=="/help" goto usage
if /i "%~1"=="-h" goto usage
echo [ERROR] 未知参数：%~1
:usage
echo 用法：scripts\start-native.bat [provider^|consumer^|all] [/skip-consul]
echo       set PROFILE=...  set TIMEOUT=...  set APP_ARGS=...
exit /b 1

:parse_skip
set "SKIP_CONSUL=1"
shift
goto parse

:parsed
where consul.exe >nul 2>nul
set "HAS_CONSUL=0"
if not errorlevel 1 set "HAS_CONSUL=1"

echo ============================================================
echo  GraalVM native 启动
echo    profile : %PROFILE%
echo    服务    : !SERVICES!
echo    native  : 各模块 target\*-native.exe
echo ============================================================

rem ---------- 1. Consul ----------
if "!SKIP_CONSUL!"=="1" goto consul_skip

curl -s -o nul --max-time 2 http://127.0.0.1:8500/v1/status/leader
if not errorlevel 1 goto consul_reuse
if "!HAS_CONSUL!"=="0" goto err_consulbin

echo [1/2] 启动 Consul: consul.exe（来自 PATH）
start "Consul" consul.exe agent -dev -bind=127.0.0.1 -client=127.0.0.1 -log-level=info
call :waitup 8500 40
if errorlevel 1 goto err_consulboot
call :consulpid
if defined PID echo !PID!>"%RUN%\pids\consul.pid"
echo       Consul 就绪：http://127.0.0.1:8500/ui
goto apps

:consul_skip
curl -s -o nul --max-time 2 http://127.0.0.1:8500/v1/status/leader
if errorlevel 1 goto err_consulmissing
echo [1/2] Consul 已在运行，按要求复用（127.0.0.1:8500）
goto apps

:consul_reuse
call :consulpid
if not defined PID goto consul_reuse_nopid
echo [1/2] Consul 已在运行（PID !PID!），跳过启动
echo !PID!>"%RUN%\pids\consul.pid"
goto apps

:consul_reuse_nopid
echo [1/2] Consul 已在运行（8500 有响应），跳过启动
goto apps

rem ---------- 2. provider / consumer ----------
:apps
if /i "!SERVICES!"=="provider" goto svc_provider
if /i "!SERVICES!"=="consumer" goto svc_consumer
call :startapp provider 8080
if errorlevel 1 exit /b 1
call :startapp consumer 8081
if errorlevel 1 exit /b 1
goto summary

:svc_provider
call :startapp provider 8080
if errorlevel 1 exit /b 1
goto summary

:svc_consumer
call :startapp consumer 8081
if errorlevel 1 exit /b 1
goto summary

:summary
echo.
echo 全部就绪。停止：scripts\stop.bat      连 Consul 一起停：scripts\stop.bat /all
echo 日志：各服务独立的控制台窗口；文件日志在 logs\ 下（logback 滚动日志）
endlocal
exit /b 0

rem ============================================================
rem  失败路径（都放在主流程之后，靠 exit /b 1 把退出码带出去）
rem ============================================================
:err_consulbin
echo [ERROR] PATH 里找不到 consul.exe，请把 consul 所在目录加入 PATH 后重试
exit /b 1

:err_consulmissing
echo [ERROR] 指定了 /skip-consul，但 127.0.0.1:8500 上没有就绪的 Consul
exit /b 1

:err_consulboot
echo [ERROR] Consul 40 秒内没起来，看那个 Consul 窗口的报错
exit /b 1

:err_missing
echo [ERROR] 找不到 native 镜像：!_exe!
echo         先构建：scripts\build.bat（产物就在各模块的 target 下）
exit /b 1

:err_busy
echo [ERROR] 端口 !_port! 已被 PID !PORTPID! 占用（!_name! 可能已经在跑），先 scripts\stop.bat
exit /b 1

:err_dead
echo [ERROR] !_name! 进程已退出（端口 !_port! 没起来）
call :taillog "%ROOT%\logs" "boot-sample-!_name!-log.*.log"
exit /b 1

:err_timeout
echo [ERROR] !_name! %TIMEOUT% 秒内没起来（端口 !_port!）
call :taillog "%ROOT%\logs" "boot-sample-!_name!-log.*.log"
exit /b 1

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

rem pidof <镜像名>  ->  PIDS=该镜像名所有实例的 PID，逗号分隔（没有则为空）
rem 不能用 tasklist 输出里的 ".exe" 判断：tasklist 会把超过 25 字符的镜像名截断显示，
rem autumn-sample-boot-provider-native 就看不到 ".exe"。所以只认第二列的数字。
:pidof
set "PIDS="
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq %~1" /NH 2^>nul') do call :keepnum %%p
exit /b 0

:keepnum
echo %~1| findstr /r "^[0-9][0-9]*$" >nul || exit /b 0
set "PIDS=!PIDS!%~1,"
exit /b 0

rem consulpid  ->  PID=consul.exe 的进程号
:consulpid
set "PID="
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq consul.exe" /NH 2^>nul') do call :firstnum %%p
exit /b 0

:firstnum
if defined PID exit /b 0
echo %~1| findstr /r "^[0-9][0-9]*$" >nul || exit /b 0
set "PID=%~1"
exit /b 0

rem snapshot <镜像名>  ->  SNAP=启动前已有的 PID 列表，形如 ,1234,
:snapshot
call :pidof %~1
set "SNAP=,"
if defined PIDS set "SNAP=,!PIDS!"
exit /b 0

rem findnew <镜像名>  ->  NEWPID=启动前没有、现在出现的那个 PID
rem 别写成 for %%p in (!PIDS:,= !)：FOR 的 IN 子句会按逗号/空格把 !PIDS:,= ! 拆成
rem "!PIDS:"、"="、"!" 几段，延迟展开再把 ! 吞掉，最后拿到的是字面量 PIDS:
rem PIDS 本身以逗号结尾，逗号在 IN 子句里就是分隔符，直接展开即可
:findnew
set "NEWPID="
call :pidof %~1
if not defined PIDS exit /b 0
for %%p in (!PIDS!) do (
    echo !SNAP! | findstr /c:",%%p," >nul || set "NEWPID=%%p"
)
exit /b 0

rem portpid <port>  ->  PORTPID=监听该端口的进程号
:portpid
set "PORTPID="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /c:"LISTENING" ^| findstr /c:":%~1 "') do if not defined PORTPID set "PORTPID=%%a"
exit /b 0

rem taillog <目录> <通配符>  ->  打印该目录下最新的匹配文件的最后 30 行
rem logback 文件名带日期和滚动序号（boot-sample-provider-log.2026-11-13.0.log），
rem 名字不固定，所以按修改时间取最新的那个
:taillog
set "_tailfile="
for /f "delims=" %%f in ('dir /b /a-d /o-d "%~1\%~2" 2^>nul') do if not defined _tailfile set "_tailfile=%~1\%%f"
if not defined _tailfile (
    echo        ^(没有日志文件：%~1\%~2^)
    exit /b 0
)
echo ---- !_tailfile! 最后 30 行 ----
powershell -NoProfile -Command "Get-Content -LiteralPath '!_tailfile!' -Tail 30" 2>nul
echo ---- 日志结束 ----
exit /b 0

rem waitapp <port> <镜像名> <秒>  ->  errorlevel 0 就绪 / 1 超时 / 2 进程已退出
rem 边等端口边看进程：native 起来得快，崩了就别干等到超时
:waitapp
set /a WAIT=0
:waitapp_loop
curl -s -o nul --max-time 2 http://127.0.0.1:%~1/
if not errorlevel 1 exit /b 0
netstat -ano | findstr /c:"LISTENING" | findstr /c:":%~1 " >nul
if not errorlevel 1 exit /b 0
set /a WAIT+=1
call :findnew %~2
if not defined NEWPID if %WAIT% GEQ 5 exit /b 2
if %WAIT% GEQ %~3 exit /b 1
ping -n 2 127.0.0.1 >nul
goto waitapp_loop

rem startapp <name> <port>
:startapp
set "_name=%~1"
set "_port=%~2"
set "_module=%ROOT%\autumn-samples\autumn-sample-boot-%_name%"
set "_exe=!_module!\target\autumn-sample-boot-%_name%-native.exe"

if not exist "!_exe!" goto err_missing

call :portpid %_port%
if defined PORTPID goto err_busy

set "_img=autumn-sample-boot-%_name%-native.exe"
set "_launcher=%LAUNCH_DIR%\%_name%-native.bat"
set "_args="
if defined PROFILE set "_args=--spring.profiles.active=%PROFILE%"
if defined APP_ARGS set "_args=!_args! %APP_ARGS%"

echo.
echo [!_name!] native 启动：!_exe!
if defined _args echo         参数：!_args!
echo         日志：独立控制台窗口 + logs\boot-sample-!_name!-log.*.log

rem 包装脚本只做两件事：切工作目录、把命令行传下去。
rem 不重定向 stdout——一旦重定向，窗口里就什么都看不到了
(
echo @echo off
echo cd /d "!ROOT!"
echo "!_exe!" !_args!
) > "!_launcher!"

call :snapshot !_img!
rem 不加 /min：窗口要立起来给用户看日志
start "!_name!" cmd /c "!_launcher!"

call :waitapp %_port% !_img! %TIMEOUT%
if errorlevel 2 goto err_dead
if errorlevel 1 goto err_timeout

call :findnew !_img!
if defined NEWPID (
    echo !NEWPID!>"%RUN%\pids\%_name%.pid"
    echo         !_name! 就绪：PID !NEWPID!  http://127.0.0.1:%_port%
) else (
    echo         !_name! 就绪：http://127.0.0.1:%_port% ^(PID 没拿到，stop 时按端口找^)
)
exit /b 0
