@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
rem GraalVM native 版 multicast 启动（Windows）：provider + consumer，不启动 Consul。
rem 跑 build.bat 产出的 target\*-native.exe；jar 版见 scripts\start-multicast.bat。
rem
rem 用法：
rem   scripts\start-multicast-native.bat
rem   set MULTICAST_INTERFACE=Ethernet ^&^& scripts\start-multicast-native.bat
rem   set APP_ARGS=--server.port=18080 ^&^& scripts\start-multicast-native.bat
rem
rem 停止：scripts\stop.bat
set "RUNTIME=native"
call "%~dp0start-multicast.bat" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
