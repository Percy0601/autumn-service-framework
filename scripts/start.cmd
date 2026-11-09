@echo off
setlocal

rem 一键启动：Consul + autumn 示例服务，每个组件一个可见窗口。
rem 参数原样透传给 start.ps1，例如：
rem   start.cmd -Service provider -Foreground
rem   start.cmd -Runtime native -SkipConsul

set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
where pwsh.exe >nul 2>&1 && set "PS=pwsh.exe"

"%PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
if errorlevel 1 (
    echo.
    echo start.ps1 执行失败，错误信息见上。
    pause
)
