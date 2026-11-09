<#
.SYNOPSIS
    一键停止：Consul + autumn 示例服务（窗口模式、前台模式、手动命令行起的都算）。

.DESCRIPTION
    进程来源，三重兜底：
      1) run\pids\<service>.json 记录的 PID（校验进程名 + 启动时间，防止 PID 被复用后误杀）
      2) 命令行 / 进程名：java 进程里带 autumn-sample-boot 的、native 镜像进程、consul.exe（-AllConsul 时全杀）
      3) 端口兜底（8080/8081/30880/30881/8500）：只在占用者确实是 java / powershell / consul /
         autumn-sample-* 时才动手；占用者是 sshd、System 这类不认识的进程只提示、不杀

    停完会回查端口（用真实 TCP 连接判断，不信 netstat 的归属），确认真的放开了。

.PARAMETER AppsOnly
    只停应用，不动 Consul。

.PARAMETER ConsulOnly
    只停 Consul，不动应用。

.PARAMETER AllConsul
    连带停掉所有 consul.exe（默认只停 pid 文件里 / 8500 上那个）。

.PARAMETER DryRun
    只列出将要结束的进程，不真动手。

.EXAMPLE
    .\scripts\stop.ps1

.EXAMPLE
    .\scripts\stop.ps1 -AppsOnly

.EXAMPLE
    .\scripts\stop.ps1 -ConsulOnly -DryRun
#>
[CmdletBinding()]
param(
    [switch]$AppsOnly,
    [switch]$ConsulOnly,
    [switch]$AllConsul,
    [switch]$DryRun,
    [int[]]$Port = @()
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root 'run'
$PidDir = Join-Path $RunDir 'pids'
$StageDir = Join-Path $RunDir 'staged'
Set-Location -LiteralPath $Root

$DoApps = -not $ConsulOnly
$DoConsul = -not $AppsOnly
if ($AppsOnly -and $ConsulOnly) {
    throw 'AppsOnly 和 ConsulOnly 不能同时用'
}

$AppPorts = @(8080, 8081, 30880, 30881)
$ConsulPorts = @(8500)

$PortsToHandle = @()
if ($DoApps) { $PortsToHandle += $AppPorts }
if ($DoConsul) { $PortsToHandle += $ConsulPorts }
$PortsToHandle += $Port

function Write-Step {
    param([string]$Text)
    Write-Host ''
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Write-Info {
    param([string]$Text)
    Write-Host "    $Text" -ForegroundColor DarkGray
}

function Test-PortOpen {
    # 真实连一下：不依赖 Get-NetTCPConnection / netstat 的枚举结果
    param([int]$TargetPort)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $TargetPort, $null, $null)
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
    # 仅用于显示归属，拿不到就返回 0
    param([int]$TargetPort)

    $conn = Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($conn) { return [int]$conn.OwningProcess }
    return 0
}

function Test-KillableName {
    # 只对我们自己的进程动手，sshd / System / 其它服务一律不碰
    param([string]$ProcessName)

    if ($ProcessName -eq 'java' -or $ProcessName -eq 'javaw') { return $true }
    if ($ProcessName -eq 'powershell' -or $ProcessName -eq 'pwsh') { return $true }
    if ($ProcessName -eq 'consul') { return $true }
    if ($ProcessName -match '^autumn-sample-boot') { return $true }
    return $false
}

$targets = @{}       # pid(string) -> 来源说明
$unmanaged = @()     # 端口被不认识的进程占用时的提示

function Add-Target {
    param([int]$ProcId, [string]$Source)

    if ($ProcId -le 0) { return }
    if ($ProcId -eq $PID) { return }
    $key = [string]$ProcId
    if (-not $targets.ContainsKey($key)) {
        $targets[$key] = $Source
    } elseif ($targets[$key] -ne $Source) {
        $targets[$key] = $targets[$key] + ' + ' + $Source
    }
}

# ---------- 1. PID 文件 ----------
$pidFiles = @()
if (Test-Path -LiteralPath $PidDir) {
    $pidFiles = @(Get-ChildItem -LiteralPath $PidDir -Filter '*.json' -File -ErrorAction SilentlyContinue)
}

$staleFiles = @()
foreach ($file in $pidFiles) {
    $serviceName = $file.BaseName
    $isConsul = $serviceName -eq 'consul'
    if ($isConsul -and -not $DoConsul) { continue }
    if (-not $isConsul -and -not $DoApps) { continue }

    $record = $null
    try {
        $record = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    } catch {
        $staleFiles += $file
        continue
    }

    $proc = Get-Process -Id ([int]$record.pid) -ErrorAction SilentlyContinue
    if (-not $proc) {
        $staleFiles += $file
        continue
    }
    if ($proc.ProcessName -ne [string]$record.name) {
        $staleFiles += $file
        continue
    }
    if ($record.started) {
        $recordedStart = [datetime]::Parse([string]$record.started)
        if ([Math]::Abs(($proc.StartTime - $recordedStart).TotalSeconds) -gt 5) {
            $staleFiles += $file
            continue
        }
    }
    Add-Target -ProcId $proc.Id -Source ('pid 文件 ' + $file.Name)
}

# ---------- 2. 命令行 / 进程名 ----------
if ($DoApps) {
    $appProcs = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            ($_.Name -eq 'java.exe' -and $_.CommandLine -and $_.CommandLine -match 'autumn-sample-boot') -or
            ($_.Name -match '^autumn-sample-boot.*\.exe$')
        })
    foreach ($proc in $appProcs) {
        Add-Target -ProcId ([int]$proc.ProcessId) -Source ('进程 ' + $proc.Name)
    }
}

if ($DoConsul) {
    if ($AllConsul) {
        foreach ($proc in @(Get-Process -Name 'consul' -ErrorAction SilentlyContinue)) {
            Add-Target -ProcId $proc.Id -Source 'consul.exe（-AllConsul）'
        }
    } else {
        $owner = Get-PortOwner -Port 8500
        if ($owner -gt 0) {
            $proc = Get-Process -Id $owner -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -eq 'consul') {
                Add-Target -ProcId $proc.Id -Source '端口 8500'
            }
        }
    }
}

# ---------- 3. 端口兜底（只认自己的进程名） ----------
foreach ($targetPort in $PortsToHandle) {
    if (-not (Test-PortOpen -Port $targetPort)) { continue }

    $owner = Get-PortOwner -Port $targetPort
    if ($owner -le 0) {
        $unmanaged += ("端口 {0} 有东西在监听，但拿不到占用进程号" -f $targetPort)
        continue
    }

    $proc = Get-Process -Id $owner -ErrorAction SilentlyContinue
    if (-not $proc) { continue }

    $isConsulPort = $ConsulPorts -contains $targetPort
    if ($isConsulPort) {
        if ($proc.ProcessName -eq 'consul' -and $DoConsul) {
            Add-Target -ProcId $proc.Id -Source ('端口 ' + $targetPort)
        } elseif (-not $DoConsul) {
            continue
        } else {
            $unmanaged += ("端口 {0} 被 PID {1}（{2}）占用，不是 consul，跳过" -f $targetPort, $owner, $proc.ProcessName)
        }
    } elseif (Test-KillableName -ProcessName $proc.ProcessName) {
        Add-Target -ProcId $proc.Id -Source ('端口 ' + $targetPort)
    } else {
        $unmanaged += ("端口 {0} 被 PID {1}（{2}）占用，不在处理范围，跳过" -f $targetPort, $owner, $proc.ProcessName)
    }
}

# ---------- 执行 ----------
$failed = @()

if ($targets.Count -eq 0) {
    Write-Step '没有找到运行中的进程'
    Write-Info '（应用与 Consul 都没有在跑，或者已经被停掉了）'
} else {
    Write-Step ('结束 {0} 个进程' -f $targets.Count)
    foreach ($key in @($targets.Keys)) {
        $proc = Get-Process -Id ([int]$key) -ErrorAction SilentlyContinue
        if (-not $proc) {
            Write-Info ("PID {0,-7} 已经不存在，跳过（来源：{1}）" -f $key, $targets[$key])
            continue
        }
        $line = '{0,-7} {1,-24} {2}' -f $key, $proc.ProcessName, $targets[$key]
        if ($DryRun) {
            Write-Host ('    [DryRun] 将结束 ' + $line) -ForegroundColor Yellow
            continue
        }
        Write-Host ('    结束 ' + $line) -ForegroundColor Yellow

        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & taskkill.exe /PID ([int]$key) /T /F 2>&1 | ForEach-Object {
                $text = [string]$_
                if ($text -and $text -notmatch 'SUCCESS|成功') { Write-Info $text }
            }
        } finally {
            $ErrorActionPreference = $previous
        }

        Start-Sleep -Milliseconds 300
        $still = Get-Process -Id ([int]$key) -ErrorAction SilentlyContinue
        if ($still) {
            # taskkill 被权限拦下时再兜底一次
            try {
                Stop-Process -Id ([int]$key) -Force -ErrorAction Stop
                Start-Sleep -Milliseconds 300
                $still = Get-Process -Id ([int]$key) -ErrorAction SilentlyContinue
            } catch {
                $still = Get-Process -Id ([int]$key) -ErrorAction SilentlyContinue
            }
        }
        if ($still) { $failed += $line }
    }
}

# ---------- 清理 ----------
if (-not $DryRun) {
    foreach ($file in $pidFiles) {
        if (-not (Test-Path -LiteralPath $file.FullName)) { continue }
        $serviceName = $file.BaseName
        if ($serviceName -eq 'consul' -and -not $DoConsul) { continue }
        if ($serviceName -ne 'consul' -and -not $DoApps) { continue }

        $record = $null
        try { $record = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json } catch { }
        if ($record -and (Get-Process -Id ([int]$record.pid) -ErrorAction SilentlyContinue)) {
            continue   # 进程还活着，保留记录
        }
        Remove-Item -LiteralPath $file.FullName -Force -ErrorAction SilentlyContinue
    }

    # 被强杀的实例来不及删自己复制出来的 jar 副本，这里顺手清掉（还在用的副本删不掉，忽略即可）
    if ($DoApps -and (Test-Path -LiteralPath $StageDir)) {
        Get-ChildItem -LiteralPath $StageDir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

if ($DryRun) {
    Write-Step 'DryRun 结束，未做任何修改'
    return
}

if ($failed.Count -gt 0) {
    Write-Step '以下进程没停掉'
    foreach ($line in $failed) { Write-Host ('    ' + $line) -ForegroundColor Red }
    Write-Info '可能有权限限制，用管理员身份的 PowerShell 再执行一次'
}

# ---------- 回查端口 ----------
Write-Step '回查端口'
$portsToCheck = @()
if ($DoApps) { $portsToCheck += $AppPorts }
if ($DoConsul) { $portsToCheck += $ConsulPorts }
$portsToCheck += $Port

$busy = @()
if ($portsToCheck.Count -gt 0) {
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        $busy = @($portsToCheck | Where-Object { Test-PortOpen -Port $_ })
        if ($busy.Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
}

if ($busy.Count -eq 0) {
    Write-Host '    端口全部已释放。' -ForegroundColor Green
} else {
    foreach ($item in $busy) {
        $owner = Get-PortOwner -Port $item
        $name = '?'
        if ($owner -gt 0) {
            $proc = Get-Process -Id $owner -ErrorAction SilentlyContinue
            if ($proc) { $name = $proc.ProcessName }
        }
        Write-Host ("    端口 {0} 仍有连接（PID {1} / {2}）" -f $item, $owner, $name) -ForegroundColor Red
    }
}

if ($unmanaged.Count -gt 0) {
    Write-Step '未处理（不是本脚本起的进程）'
    foreach ($line in $unmanaged) { Write-Info $line }
}

if ($busy.Count -gt 0 -or $failed.Count -gt 0) { exit 1 }
