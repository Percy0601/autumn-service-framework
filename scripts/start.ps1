<#
.SYNOPSIS
    启动 Consul + autumn 示例服务（Windows 版，替代 scripts/run-boot.sh 的后台 nohup 用法）。

.DESCRIPTION
    默认：Consul 与每个服务各开一个**可见**控制台窗口，日志实时打印并同时写入 run\logs。
    加 -Foreground：在当前窗口前台运行单个服务，Ctrl+C 直接停 —— 不会再出现"起了后台进程却找不到进程号"。

    进程号统一记在 run\pids\<service>.json，一键停止用 scripts\stop.ps1（它还会按端口、按命令行兜底）。

    端口（来自示例的 application.properties / application-consul.properties）：
        provider  server 8080  thrift 30880
        consumer  server 8081  thrift 30881
        consul    http 8500

.PARAMETER Service
    both（默认，provider + consumer）/ provider / consumer / consul。

.PARAMETER Runtime
    jar（默认，跑 *-exec.jar）或 native（跑 build-native.ps1 产出的 exe）。

.PARAMETER Foreground
    在当前窗口前台运行，一次只能一个服务；Ctrl+C 停止。

.PARAMETER SkipConsul
    复用已经在 8500 上跑的 Consul，不再启动。

.PARAMETER ConsulHome
    Consul 安装目录，默认 D:\opt\consul。目录里没有时会自动去找 D:\opt\consul*\consul.exe 和 PATH 上的 consul.exe。

.PARAMETER Profile
    Spring profile，默认 consul；传空字符串则不传。

.PARAMETER AppArgs
    追加给应用的参数，如 -AppArgs '--server.port=18080','--autumn.provider.port=30882'。

.PARAMETER DryRun
    只打印将要执行的命令，不启动任何进程。

.EXAMPLE
    .\scripts\start.ps1                      # Consul + provider + consumer，各开一个窗口

.EXAMPLE
    .\scripts\start.ps1 -Service provider -Foreground     # 当前窗口跑 provider，Ctrl+C 停

.EXAMPLE
    .\scripts\start.ps1 -Runtime native -SkipConsul

.EXAMPLE
    .\scripts\start.ps1 -Service provider -Foreground -AppArgs '--server.port=18080','--autumn.provider.port=30882'
#>
[CmdletBinding()]
param(
    [ValidateSet('both', 'provider', 'consumer', 'consul')]
    [string]$Service = 'both',

    [ValidateSet('jar', 'native')]
    [string]$Runtime = 'jar',

    [switch]$Foreground,
    [switch]$SkipConsul,

    [string]$ConsulHome = 'D:\opt\consul',
    [string]$Profile = 'consul',
    [string]$JavaHome,
    [string[]]$AppArgs = @(),

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root 'run'
$PidDir = Join-Path $RunDir 'pids'
$LogDir = Join-Path $RunDir 'logs'
$StageDir = Join-Path $RunDir 'staged'
Set-Location -LiteralPath $Root

$Apps = @{
    provider = @{
        Module     = 'autumn-samples\autumn-sample-boot-provider'
        Image      = 'autumn-sample-boot-provider-native'
        ServerPort = 8080
        ThriftPort = 30880
    }
    consumer = @{
        Module     = 'autumn-samples\autumn-sample-boot-consumer'
        Image      = 'autumn-sample-boot-consumer-native'
        ServerPort = 8081
        ThriftPort = 30881
    }
}

function Write-Step {
    param([string]$Text)
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Write-Info {
    param([string]$Text)
    Write-Host "    $Text" -ForegroundColor DarkGray
}

function Invoke-StreamWithTee {
    # PowerShell 5.1 下 2>&1 会把原生程序的 stderr 变成 ErrorRecord，配合 Stop 策略会直接中断；
    # 这里放宽策略并把两条流统一成字符串，再同时输出到控制台和日志文件。
    param([string]$Exe, [string[]]$Arguments, [string]$LogPath)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments 2>&1 |
            ForEach-Object { [string]$_ } |
            Tee-Object -FilePath $LogPath
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-ConsulExe {
    param([string]$HomeDir)

    $candidates = @()
    if ($HomeDir) {
        $candidates += (Join-Path $HomeDir 'consul.exe')
        $candidates += (Join-Path (Join-Path $HomeDir 'bin') 'consul.exe')
    }
    if (Test-Path -LiteralPath 'D:\opt') {
        $dirs = @(Get-ChildItem -LiteralPath 'D:\opt' -Directory -Filter 'consul*' -ErrorAction SilentlyContinue |
            Sort-Object -Property Name -Descending)
        foreach ($dir in $dirs) { $candidates += (Join-Path $dir.FullName 'consul.exe') }
    }
    $onPath = Get-Command consul.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Test-PortOpen {
    # 直接连一下最准，不依赖 Get-NetTCPConnection 的枚举结果
    param([int]$Port)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(300)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-PortOwner {
    # 只用来显示"是谁占的"，拿不到就返回 0；占用与否一律以 Test-PortOpen 为准，
    # 因为 netstat 在某些网卡/代理/注入环境下会把端口归到无关进程上。
    param([int]$Port)

    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($conn) { return [int]$conn.OwningProcess }
    return 0
}

function Wait-PortOpen {
    param([int]$Port, [int]$TimeoutSec = 60)

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen -Port $Port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Wait-ConsulHealthy {
    param([int]$TimeoutSec = 40)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri 'http://127.0.0.1:8500/v1/status/leader' -UseBasicParsing -TimeoutSec 2
            if ($resp.StatusCode -eq 200 -and $resp.Content -match '\S') { return $true }
        } catch {
            # 还没起来，继续等
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function New-LogPath {
    param([string]$Name)
    if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    return (Join-Path $LogDir ($Name + '-' + $stamp + '.log'))
}

function Register-Pid {
    param([string]$Name, [string]$LogPath)
    if (-not (Test-Path -LiteralPath $PidDir)) { New-Item -ItemType Directory -Path $PidDir -Force | Out-Null }
    $self = Get-Process -Id $PID
    $record = [ordered]@{
        service = $Name
        pid     = $PID
        name    = $self.ProcessName
        started = $self.StartTime.ToString('yyyy-MM-ddTHH:mm:ss')
        log     = $LogPath
    }
    $json = ($record | ConvertTo-Json)
    [IO.File]::WriteAllText((Join-Path $PidDir ($Name + '.json')), $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Unregister-Pid {
    param([string]$Name)
    $path = Join-Path $PidDir ($Name + '.json')
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
}

function Get-JavaExe {
    param([string]$HomeDir)
    $candidates = @()
    if ($HomeDir) { $candidates += (Join-Path $HomeDir 'bin\java.exe') }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Stage-Jar {
    param([string]$JarPath)
    if (-not (Test-Path -LiteralPath $StageDir)) { New-Item -ItemType Directory -Path $StageDir -Force | Out-Null }

    # 只清理一天前的残留副本（上次被强杀留下的），当天的副本是正在跑的服务，别碰
    $stale = @(Get-ChildItem -LiteralPath $StageDir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { ((Get-Date) - $_.LastWriteTime).TotalDays -gt 1 })
    if ($stale.Count -gt 0) {
        $stale | Remove-Item -Force -ErrorAction SilentlyContinue
    }

    $tag = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $base = [IO.Path]::GetFileNameWithoutExtension($JarPath)
    $dest = Join-Path $StageDir ($base + '-' + $tag + '.jar')
    Copy-Item -LiteralPath $JarPath -Destination $dest -Force
    return $dest
}

function Get-AppLaunch {
    param([string]$Name)

    $meta = $Apps[$Name]
    $moduleDir = Join-Path $Root $meta.Module

    $serviceArgs = @()
    if ($Profile -and -not ($AppArgs -match '^--spring\.profiles\.active=')) {
        $serviceArgs += ('--spring.profiles.active=' + $Profile)
    }
    $serviceArgs += $AppArgs

    if ($Runtime -eq 'native') {
        $exe = Join-Path (Join-Path $moduleDir 'target') ($meta.Image + '.exe')
        if (-not (Test-Path -LiteralPath $exe)) {
            throw ("找不到 native 镜像：{0}`n先运行 .\scripts\build-native.ps1（或 .\scripts\build-native.ps1 -Module {1}）" -f $exe, $meta.Module)
        }
        return @{
            Kind    = 'native'
            Exe     = $exe
            WorkDir = $moduleDir
            Args    = $serviceArgs
            Log     = (New-LogPath $Name)
            Cleanup = $null
        }
    }

    $jarItem = @(Get-ChildItem -LiteralPath (Join-Path $moduleDir 'target') -Filter '*-exec.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending |
        Select-Object -First 1)
    if ($jarItem.Count -eq 0) {
        throw ("找不到 {0}\target\*-exec.jar`n先构建：mvn -DskipTests clean install" -f $meta.Module)
    }

    # Windows 上 java -jar 会锁住 target 里的 jar，mvn clean install 会在 repackage 阶段失败，
    # 所以先复制到 run\staged 再跑（副本名唯一，也允许多实例并存）。
    $staged = Stage-Jar -JarPath $jarItem[0].FullName

    $java = Get-JavaExe -HomeDir $JavaHome
    if (-not $java) {
        throw '找不到 java.exe：请设置 JAVA_HOME，或用 -JavaHome 指定'
    }

    return @{
        Kind    = 'jar'
        Exe     = $java
        WorkDir = $moduleDir
        Args    = (@('-jar', $staged) + $serviceArgs)
        Log     = (New-LogPath $Name)
        Cleanup = $staged
    }
}

function Assert-PortsFree {
    param([string]$Name)

    $meta = $Apps[$Name]
    $checks = @(
        @{ Port = $meta.ServerPort; Flag = '--server.port' },
        @{ Port = $meta.ThriftPort; Flag = '--autumn.provider.port' }
    )
    foreach ($check in $checks) {
        # 命令行显式覆盖了端口（例如起第二个 provider），就不再按默认端口拦
        if ($AppArgs -match ('^' + [regex]::Escape($check.Flag))) { continue }
        if (Test-PortOpen -Port $check.Port) {
            $owner = Get-PortOwner -Port $check.Port
            $detail = ''
            if ($owner -ne 0) { $detail = "（PID $owner）" }
            throw ("端口 {0} 已被占用{1}，{2} 可能已经在跑。先执行 .\scripts\stop.ps1 -AppsOnly" -f $check.Port, $detail, $Name)
        }
    }
}

function Show-LogTail {
    param([string]$Name, [int]$Lines = 20)

    $latest = @(Get-ChildItem -LiteralPath $LogDir -Filter ($Name + '-*.log') -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending |
        Select-Object -First 1)
    if ($latest.Count -eq 0) { return }

    Write-Host ("    日志尾部（{0}）：" -f $latest[0].FullName) -ForegroundColor Yellow
    Get-Content -LiteralPath $latest[0].FullName -Tail $Lines | ForEach-Object {
        Write-Host ('      ' + $_) -ForegroundColor Yellow
    }
}

function Invoke-ConsulForeground {
    $exe = Get-ConsulExe -HomeDir $ConsulHome
    if (-not $exe) {
        throw ("找不到 consul.exe：{0} 下没有，D:\opt\consul* 和 PATH 里也没有`n用 -ConsulHome 指定安装目录" -f $ConsulHome)
    }

    if (Test-PortOpen -Port 8500) {
        $owner = Get-PortOwner -Port 8500
        $detail = ''
        if ($owner -ne 0) { $detail = "（PID $owner）" }
        throw ("Consul 已在运行{0}。要重启先执行 .\scripts\stop.ps1 -ConsulOnly" -f $detail)
    }

    $argv = @('agent', '-dev', '-bind=127.0.0.1', '-client=127.0.0.1', '-log-level=info')
    $log = New-LogPath 'consul'

    if ($DryRun) {
        Write-Step 'DryRun：Consul'
        Write-Info ("{0} {1}" -f $exe, ($argv -join ' '))
        Write-Info ("日志 {0}" -f $log)
        return
    }

    Write-Step 'Consul（前台）'
    Write-Info ("{0} {1}" -f $exe, ($argv -join ' '))
    Write-Info ("日志 {0}" -f $log)
    Write-Host '    Ctrl+C 停止；或另一个窗口执行 .\scripts\stop.ps1 -ConsulOnly' -ForegroundColor DarkGray

    Register-Pid -Name 'consul' -LogPath $log
    try {
        Invoke-StreamWithTee -Exe $exe -Arguments $argv -LogPath $log
    } finally {
        Unregister-Pid -Name 'consul'
        Write-Host '    Consul 已停止。' -ForegroundColor Yellow
    }
}

function Invoke-AppForeground {
    param([string]$Name)

    $launch = Get-AppLaunch -Name $Name

    if ($DryRun) {
        Write-Step ("DryRun：" + $Name)
        Write-Info ("{0} {1}" -f $launch.Exe, ($launch.Args -join ' '))
        Write-Info ("工作目录 {0}" -f $launch.WorkDir)
        Write-Info ("日志 {0}" -f $launch.Log)
        return
    }

    Assert-PortsFree -Name $Name

    Write-Step ("{0}（{1}，前台）" -f $Name, $Runtime)
    Write-Info ("{0} {1}" -f $launch.Exe, ($launch.Args -join ' '))
    Write-Info ("日志 {0}" -f $launch.Log)
    Write-Host '    Ctrl+C 停止；或另一个窗口执行 .\scripts\stop.ps1' -ForegroundColor DarkGray

    Register-Pid -Name $Name -LogPath $launch.Log
    try {
        Push-Location -LiteralPath $launch.WorkDir
        Invoke-StreamWithTee -Exe $launch.Exe -Arguments @($launch.Args) -LogPath $launch.Log
    } finally {
        Pop-Location
        Unregister-Pid -Name $Name
        if ($launch.Cleanup -and (Test-Path -LiteralPath $launch.Cleanup)) {
            Remove-Item -LiteralPath $launch.Cleanup -Force -ErrorAction SilentlyContinue
        }
        Write-Host ("    {0} 已停止。" -f $Name) -ForegroundColor Yellow
    }
}

function Start-ChildWindow {
    param([string]$Name)

    $hostExe = (Get-Process -Id $PID).Path
    if (-not $hostExe) { $hostExe = (Get-Command powershell.exe).Source }

    $argList = @(
        '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', $PSCommandPath,
        '-Service', $Name,
        '-Foreground',
        '-Runtime', $Runtime,
        '-ConsulHome', $ConsulHome
    )
    if ($Profile) { $argList += @('-Profile', $Profile) }
    if ($JavaHome) { $argList += @('-JavaHome', $JavaHome) }
    if ($AppArgs.Count -gt 0) { $argList += @('-AppArgs', ($AppArgs -join ',')) }

    return (Start-Process -FilePath $hostExe -ArgumentList $argList -PassThru)
}

function Show-Summary {
    param([string[]]$Names)

    Write-Step '已启动'
    if (Test-PortOpen -Port 8500) {
        $consulPid = Get-PortOwner -Port 8500
        if ($consulPid -eq 0) { $consulPid = '?' }
        Write-Host ("    {0,-9} PID {1,-7} http://127.0.0.1:8500/ui" -f 'consul', $consulPid)
    }
    foreach ($name in $Names) {
        $meta = $Apps[$name]
        $pidFile = Join-Path $PidDir ($name + '.json')
        $procId = '?'
        $log = '(见 run\logs)'
        if (Test-Path -LiteralPath $pidFile) {
            $record = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
            $procId = $record.pid
            $log = $record.log
        }
        Write-Host ("    {0,-9} PID {1,-7} http://127.0.0.1:{2,-5} {3}" -f $name, $procId, $meta.ServerPort, $log)
    }
    Write-Host ''
    Write-Host '    一键停止：.\scripts\stop.ps1      单个窗口也可以直接 Ctrl+C' -ForegroundColor Green
}

# ---------- 入口 ----------
if ($Foreground) {
    if ($Service -eq 'both') {
        throw '前台模式一次只能跑一个：用 -Service provider / consumer / consul；要一起起就去掉 -Foreground（每个服务各开一个窗口）'
    }
    if ($Service -eq 'consul') {
        Invoke-ConsulForeground
    } else {
        Invoke-AppForeground -Name $Service
    }
    return
}

if ($Service -eq 'consul') {
    # 非前台且只起 Consul：开一个独立窗口，不牵扯应用
    if (Test-PortOpen -Port 8500) {
        $owner = Get-PortOwner -Port 8500
        $detail = ''
        if ($owner -ne 0) { $detail = "（PID $owner）" }
        Write-Host ("Consul 已在运行{0}" -f $detail) -ForegroundColor Yellow
        return
    }
    $exe = Get-ConsulExe -HomeDir $ConsulHome
    if (-not $exe) {
        throw ("找不到 consul.exe：{0} 下没有，D:\opt\consul* 和 PATH 里也没有" -f $ConsulHome)
    }
    if ($DryRun) {
        Write-Step 'DryRun：Consul'
        Write-Info ("{0} agent -dev -bind=127.0.0.1 -client=127.0.0.1" -f $exe)
        return
    }
    Write-Step 'Consul'
    Write-Info ("启动 {0}" -f $exe)
    $null = Start-ChildWindow -Name 'consul'
    if (-not (Wait-ConsulHealthy -TimeoutSec 40)) {
        Show-LogTail -Name 'consul'
        throw 'Consul 40 秒内没有变健康，日志见上'
    }
    Write-Info 'Consul 就绪：http://127.0.0.1:8500/ui'
    return
}

$names = @()
if ($Service -eq 'both') { $names = @('provider', 'consumer') } else { $names = @($Service) }

if ($DryRun) {
    Write-Step 'DryRun：启动计划'
    $exe = Get-ConsulExe -HomeDir $ConsulHome
    if ($SkipConsul) {
        Write-Info 'Consul：复用已运行的实例（-SkipConsul）'
    } elseif (Test-PortOpen -Port 8500) {
        Write-Info 'Consul：复用已运行的实例（8500 已监听）'
    } else {
        Write-Info ("Consul：{0} agent -dev -bind=127.0.0.1 -client=127.0.0.1" -f $exe)
    }
    foreach ($name in $names) {
        $meta = $Apps[$name]
        if ($Runtime -eq 'native') {
            $exePath = Join-Path (Join-Path $Root $meta.Module) ('target\' + $meta.Image + '.exe')
            Write-Info ("{0}：{1} --spring.profiles.active={2}" -f $name, $exePath, $Profile)
        } else {
            $jarDir = Join-Path (Join-Path $Root $meta.Module) 'target'
            Write-Info ("{0}：java -jar {1}\*-exec.jar --spring.profiles.active={2}" -f $name, $jarDir, $Profile)
        }
    }
    return
}

# Consul 先行：应用启动就要注册
Write-Step 'Consul'
if ($SkipConsul) {
    if (-not (Test-PortOpen -Port 8500)) {
        throw '指定了 -SkipConsul，但 8500 上并没有 Consul 在跑'
    }
    $owner = Get-PortOwner -Port 8500
    Write-Info ("复用已在运行的 Consul（PID {0}）" -f $(if ($owner -ne 0) { $owner } else { '?' }))
} else {
    if (Test-PortOpen -Port 8500) {
        $owner = Get-PortOwner -Port 8500
        Write-Info ("Consul 已在运行（PID {0}），复用" -f $(if ($owner -ne 0) { $owner } else { '?' }))
    } else {
        $exe = Get-ConsulExe -HomeDir $ConsulHome
        if (-not $exe) {
            throw ("找不到 consul.exe：{0} 下没有，D:\opt\consul* 和 PATH 里也没有`n用 -ConsulHome 指定安装目录" -f $ConsulHome)
        }
        Write-Info ("启动 {0}（独立窗口，PID 记在 run\pids\consul.json）" -f $exe)
        $null = Start-ChildWindow -Name 'consul'
        if (-not (Wait-ConsulHealthy -TimeoutSec 40)) {
            Show-LogTail -Name 'consul'
            throw 'Consul 40 秒内没有变健康，日志见上'
        }
        Write-Info 'Consul 就绪：http://127.0.0.1:8500/ui'
    }
}

foreach ($name in $names) {
    Write-Step $name
    Assert-PortsFree -Name $name

    $proc = Start-ChildWindow -Name $name

    # 等端口真的开始监听，而不是睡 3 秒就当成功（进程可能起来后立刻退出）
    $portToWait = $Apps[$name].ServerPort
    if ($AppArgs -match '^--server\.port') { $portToWait = 0 }
    Write-Info ("窗口已打开（PID {0}），等待端口 {1} ..." -f $proc.Id, $(if ($portToWait -gt 0) { $portToWait } else { '（命令行已覆盖）' }))

    $up = $false
    if ($portToWait -gt 0) {
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            if ($proc.HasExited) { break }
            if (Test-PortOpen -Port $portToWait) { $up = $true; break }
            Start-Sleep -Milliseconds 500
        }
    } else {
        Start-Sleep -Seconds 5
        $up = -not $proc.HasExited
    }

    if (-not $up) {
        Show-LogTail -Name $name
        throw ("{0} 启动失败（端口 {1} 没监听），日志见上" -f $name, $portToWait)
    }
    Write-Info ("{0} 已就绪，PID {1}，http://127.0.0.1:{2}" -f $name, $proc.Id, $Apps[$name].ServerPort)
}

Show-Summary -Names $names
