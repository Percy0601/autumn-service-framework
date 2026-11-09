@echo off
setlocal

rem 一键停止：Consul + autumn 示例服务。
rem   stop.cmd                停应用和 Consul
rem   stop.cmd -AppsOnly      只停应用
rem   stop.cmd -ConsulOnly    只停 Consul

set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
where pwsh.exe >nul 2>&1 && set "PS=pwsh.exe"

"%PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
set "RC=%ERRORLEVEL%"

rem 双击运行时（cmdcmdline 里带脚本名）留一下窗口，方便看结果；命令行里调用则直接返回
echo %cmdcmdline% | find /i "%~nx0" >nul && pause
exit /b %RC%
