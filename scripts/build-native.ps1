<#
.SYNOPSIS
    构建 autumn 示例应用的 GraalVM native 镜像（Windows 版，等价于 scripts/build-native.sh）。

.DESCRIPTION
    前置条件：
      - GraalVM JDK 25（带 native-image 组件）。脚本自动挑选：-JavaHome → $env:JAVA_HOME → D:\opt\graalvm-* 里最新的一个
      - Maven 3.9+ 在 PATH
      - Visual Studio 2022 C++ 工具链。脚本用 vswhere 找到 vcvars64.bat，把它的环境变量导入当前进程，
        所以不需要先手动开 "x64 Native Tools Command Prompt"；找不到时才报错提示
      - Consul 只有运行期才需要，构建不需要

    反射 / 资源 / 动态代理元数据由 JSR-269 注解处理器（META-INF/native-image/xyz.we-win.autumn/autumn-*-hints/）
    和 Spring AOT RuntimeHints 在构建期生成，不需要 -H:*ConfigurationResources，也不需要手写 reflect-config.json。

.PARAMETER Module
    要编译的模块（相对仓库根目录），默认两个 boot 示例。

.PARAMETER SkipInstall
    跳过 "mvn clean install"（复用本地仓库里已有的 SNAPSHOT）；改了框架代码就别加这个开关。

.PARAMETER DryRun
    只打印解析出来的 JDK / MSVC / 命令行，不真正构建。

.EXAMPLE
    .\scripts\build-native.ps1

.EXAMPLE
    .\scripts\build-native.ps1 -Module autumn-samples\autumn-sample-boot-provider

.EXAMPLE
    .\scripts\build-native.ps1 -SkipInstall
#>
[CmdletBinding()]
param(
    [string[]]$Module = @(
        'autumn-samples\autumn-sample-boot-provider',
        'autumn-samples\autumn-sample-boot-consumer'
    ),
    [string]$JavaHome,
    [switch]$SkipInstall,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$Started = Get-Date

function Write-Step {
    param([string]$Text)
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Write-Info {
    param([string]$Text)
    Write-Host "    $Text" -ForegroundColor DarkGray
}

function Get-GraalVmHome {
    param([string]$Explicit)

    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    if (Test-Path -LiteralPath 'D:\opt') {
        $candidates += @(Get-ChildItem -LiteralPath 'D:\opt' -Directory -Filter 'graalvm-*' -ErrorAction SilentlyContinue |
            Sort-Object -Property Name -Descending |
            ForEach-Object { $_.FullName })
    }

    foreach ($candidate in $candidates) {
        $binDir = Join-Path $candidate 'bin'
        if (Test-Path -LiteralPath (Join-Path $binDir 'native-image.cmd')) {
            return $candidate
        }
        if (Test-Path -LiteralPath (Join-Path $binDir 'native-image.exe')) {
            return $candidate
        }
    }
    return $null
}

function Import-MsvcEnvironment {
    $cl = Get-Command cl.exe -ErrorAction SilentlyContinue
    if ($cl) { return $cl.Source }

    $vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
    $vsHome = $null
    if (Test-Path -LiteralPath $vswhere) {
        $vsHome = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($vsHome) {
            $vsHome = @($vsHome)[0].Trim()
        }
    }
    if (-not $vsHome -or -not (Test-Path -LiteralPath $vsHome)) {
        throw @'
未找到 Visual Studio 2022 的 C++ 工具链（Microsoft.VisualStudio.Component.VC.Tools.x86.x64）。
请安装 "使用 C++ 的桌面开发" 工作负载，或先手动执行
    "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
再运行本脚本。
'@
    }

    $vcvars = Join-Path $vsHome 'VC\Auxiliary\Build\vcvars64.bat'
    if (-not (Test-Path -LiteralPath $vcvars)) {
        throw "找不到 vcvars64.bat：$vcvars"
    }

    Write-Info "导入 MSVC 环境（首次约需 5-10 秒）：$vcvars"
    # /s 让 cmd 只剥掉最外层引号，内部带空格的路径才不会被打散
    $dump = & cmd.exe /s /c "call `"$vcvars`" >nul 2>&1 && set"
    if ($LASTEXITCODE -ne 0) {
        throw "vcvars64.bat 执行失败（exit $LASTEXITCODE）：$vcvars"
    }
    foreach ($line in @($dump)) {
        if ($line -match '^([^=]+)=(.*)$') {
            Set-Item -LiteralPath ('Env:' + $Matches[1]) -Value $Matches[2] -ErrorAction SilentlyContinue
        }
    }

    $cl = Get-Command cl.exe -ErrorAction SilentlyContinue
    if (-not $cl) { throw '导入 vcvars64.bat 环境后仍然找不到 cl.exe' }
    return $cl.Source
}

function Get-NativeOutput {
    # PowerShell 5.1 下 2>&1 会把原生程序的 stderr 变成 ErrorRecord，
    # 配合 $ErrorActionPreference='Stop' 会直接抛错，所以这里临时放宽错误策略。
    param([string]$Exe, [string[]]$Arguments)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return @(& $Exe @Arguments 2>&1 | ForEach-Object { [string]$_ })
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-Mvn {
    param([string]$Label, [string[]]$MvnArgs)

    Write-Step $Label
    Write-Info ('mvn ' + ($MvnArgs -join ' '))

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $script:MvnExe @MvnArgs
    } finally {
        $ErrorActionPreference = $previous
    }

    if ($LASTEXITCODE -ne 0) {
        throw "mvn 构建失败（exit $LASTEXITCODE）：$Label"
    }
}

$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root

# ---------- 1. GraalVM ----------
Write-Step '解析 GraalVM'
$GraalVm = Get-GraalVmHome -Explicit $JavaHome
if (-not $GraalVm) {
    throw @'
没有找到带 native-image 的 GraalVM JDK 25。
用 -JavaHome 指定，或设置 JAVA_HOME，或把它解压到 D:\opt\graalvm-<版本>\ 下。
'@
}
$env:JAVA_HOME = $GraalVm
$env:PATH = (Join-Path $GraalVm 'bin') + ';' + $env:PATH
Write-Info "JAVA_HOME = $env:JAVA_HOME"
$javaVersion = Get-NativeOutput -Exe (Join-Path $GraalVm 'bin\java.exe') -Arguments @('-version')
Write-Info ([string]($javaVersion | Select-Object -First 1))
$nativeImage = Join-Path $GraalVm 'bin\native-image.cmd'
if (-not (Test-Path -LiteralPath $nativeImage)) {
    $nativeImage = Join-Path $GraalVm 'bin\native-image.exe'
}
$niVersion = Get-NativeOutput -Exe $nativeImage -Arguments @('--version')
Write-Info ([string]($niVersion | Select-Object -Last 1))

# ---------- 2. Maven ----------
Write-Step '解析 Maven'
$mvnCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mvnCommand) { $mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue }
if (-not $mvnCommand) { throw 'mvn 不在 PATH 里，请先把 Maven 3.9+ 的 bin 目录加入 PATH' }
$script:MvnExe = $mvnCommand.Source
Write-Info "mvn = $script:MvnExe"

# ---------- 3. MSVC ----------
Write-Step '解析 MSVC（native-image 需要 cl.exe 与 Windows SDK）'
$ClExe = Import-MsvcEnvironment
Write-Info "cl.exe = $ClExe"

$joinedModules = (($Module | ForEach-Object { $_.Replace('\', '/') }) -join ',')
$installArgs = @('-B', '-U', '-DskipTests', 'clean', 'install')
$nativeArgs = @('-B', '-U', '-Pnative', '-DskipTests', '-pl', $joinedModules, 'native:compile')

if ($DryRun) {
    Write-Step 'DryRun：以下命令不会真正执行'
    if (-not $SkipInstall) { Write-Info ('mvn ' + ($installArgs -join ' ')) }
    Write-Info ('mvn ' + ($nativeArgs -join ' '))
    return
}

# ---------- 4. 构建 ----------
# java -jar 会锁住 target 下的 jar，导致 mvn clean 在 spring-boot:repackage 失败。
# 所以构建前先停掉跑着的服务实例。
if (-not $SkipInstall) {
    Invoke-Mvn 'install 全部模块（JDK 25）' $installArgs
}

Invoke-Mvn ('AOT + native-image 编译：' + $joinedModules) $nativeArgs

# ---------- 5. 产物 ----------
Write-Step 'native 镜像'
$missing = @()
foreach ($m in $Module) {
    $targetDir = Join-Path (Join-Path $Root $m) 'target'
    $images = @(Get-ChildItem -LiteralPath $targetDir -Filter '*.exe' -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending)
    if ($images.Count -eq 0) {
        $missing += $m
        Write-Host ("    [缺失] {0}\target\*.exe" -f $m) -ForegroundColor Yellow
        continue
    }
    foreach ($image in $images) {
        Write-Host ('    {0}  {1:N1} MB  {2}' -f $image.Name, ($image.Length / 1MB), $image.LastWriteTime)
    }
}

$elapsed = [int]((Get-Date) - $Started).TotalSeconds
Write-Host ''
Write-Host ("完成，用时 {0} 秒。" -f $elapsed) -ForegroundColor Green
if ($missing.Count -gt 0) {
    throw ('以下模块没有产出 exe：' + ($missing -join ', '))
}
Write-Host '启动 native 实例：.\scripts\start.ps1 -Runtime native' -ForegroundColor Green
