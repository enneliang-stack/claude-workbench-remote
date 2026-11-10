# 开启 Windows 网卡的 IPv6，让 Tailscale 能在手机 5G 和电脑之间直连。
# 只改 IPv6 协议绑定，不做端口映射、不开防火墙端口，可随时在网卡属性里关回去。

$ErrorActionPreference = 'Stop'

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $p = New-Object Security.Principal.WindowsPrincipal($id)
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    try {
        $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
        Start-Process powershell.exe -Verb RunAs -Wait -ArgumentList $argList
    } catch {
        Write-Host '需要管理员权限。请右键此脚本，选择“以管理员身份运行”。' -ForegroundColor Yellow
        Read-Host '按回车关闭'
    }
    exit
}

$adapters = @(Get-NetAdapter | Where-Object {
    $_.Status -eq 'Up' -and -not $_.Virtual -and
    $_.InterfaceDescription -notlike '*Tailscale*' -and
    $_.InterfaceDescription -notlike '*TAP*'
})
if ($adapters.Count -eq 0) {
    Write-Host '没有找到已连接的物理网卡。' -ForegroundColor Yellow
    Read-Host '按回车关闭'
    exit 1
}

foreach ($a in $adapters) {
    Write-Host "开启 IPv6：$($a.Name)  $($a.InterfaceDescription)"
    try {
        Enable-NetAdapterBinding -Name $a.Name -ComponentID ms_tcpip6 -ErrorAction Stop
    } catch {
        Write-Host "  $($a.Name) 开启失败：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host '等待路由器下发 IPv6 地址…'
Start-Sleep -Seconds 5

$globalAddrs = @()
foreach ($a in $adapters) {
    $globalAddrs += @(Get-NetIPAddress -AddressFamily IPv6 -InterfaceIndex $a.ifIndex -ErrorAction SilentlyContinue |
        Where-Object {
            $_.AddressState -eq 'Preferred' -and
            $_.IPAddress -notlike 'fe80*' -and
            $_.IPAddress -notlike 'fd*' -and
            $_.IPAddress -ne '::1'
        })
}

Write-Host ''
if ($globalAddrs.Count -gt 0) {
    Write-Host '已拿到公网 IPv6：' -ForegroundColor Green
    $globalAddrs | Select-Object InterfaceAlias, IPAddress, PrefixLength | Format-Table -AutoSize
    Write-Host '手机 5G 也有 IPv6 时，Tailscale 会优先直连；手机 App 的电脑端日志应显示「直连」。'
    if (Get-Command tailscale -ErrorAction SilentlyContinue) {
        Write-Host ''
        Write-Host 'Tailscale 检测：'
        tailscale netcheck 2>&1 | Select-String -Pattern 'UDP:|IPv4:|IPv6:' | ForEach-Object {
            Write-Host ('  ' + $_.Line.Trim())
        }
    }
} else {
    Write-Host '网卡 IPv6 已开启，但路由器没有下发公网 IPv6。' -ForegroundColor Yellow
    Write-Host '需要进路由器后台开启 IPv6 / IPv6 双栈（不同品牌路径不同），然后再运行一次本脚本。'
}

Write-Host ''
Read-Host '按回车关闭'
