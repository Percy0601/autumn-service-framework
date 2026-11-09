@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0.."

rem ============================================================
rem  GraalVM native 镜像构建（Windows）
rem  用法：scripts\build.bat
rem        set SKIP_INSTALL=1 ^&^& scripts\build.bat               跳过 clean install
rem        set MODULES=a/b,c/d ^&^& scripts\build.bat               只编指定模块
rem ============================================================

if not defined JAVA_HOME (
    echo [ERROR] 请先设置 JAVA_HOME 指向 GraalVM：
    echo         set JAVA_HOME=D:\opt\graalvm-25.4.4.1.1+1.1
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\native-image.cmd" (
    echo [ERROR] %JAVA_HOME%\bin 下没有 native-image.cmd，JAVA_HOME 必须是带 native-image 的 GraalVM
    exit /b 1
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] 找不到 mvn，确认 Maven 的 bin 目录在 PATH 里
    exit /b 1
)

rem Visual Studio 的 C++ 环境：只在当前窗口生效，不用手动开 Native Tools 命令行
set "VCVARS="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%i"
)
if not defined VSINSTALL if exist "C:\Program Files\Microsoft Visual Studio\2022\Community" set "VSINSTALL=C:\Program Files\Microsoft Visual Studio\2022\Community"
if defined VSINSTALL if exist "%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat"

if defined VCVARS (
    echo 使用 Visual Studio 环境: !VCVARS!
    call "!VCVARS!" >nul
) else (
    echo [WARN] 没找到 vcvars64.bat，native-image 可能报 "Failed to automatically set up Windows build environment"
)

echo GraalVM: %JAVA_HOME%
call "%JAVA_HOME%\bin\native-image.cmd" --version

if not defined MODULES set "MODULES=autumn-samples/autumn-sample-boot-provider,autumn-samples/autumn-sample-boot-consumer"

if /i not "%SKIP_INSTALL%"=="1" (
    echo.
    echo [1/2] mvn clean install
    call mvn -B -U -DskipTests clean install
    if errorlevel 1 exit /b 1
)

echo.
echo [2/2] mvn -Pnative native:compile %MODULES%
call mvn -B -U -Pnative -DskipTests -pl "%MODULES%" native:compile
if errorlevel 1 exit /b 1

echo.
echo 产物：
for %%m in (%MODULES:,= %) do (
    if exist "%%m\target\*.exe" (
        for %%f in ("%%m\target\*.exe") do echo   %%f
    ) else (
        echo   [MISSING] %%m\target\*.exe
    )
)
endlocal
