# 一键安装手机远程控制（工作台客户端 1.1.28 适配版）
# 只改客户端目录里的 server.js 和手机页/远程工具，不动 data、对话记录和 API Key。
param(
  [string]$AppDir = '',
  [switch]$Force,
  [switch]$NoOpen
)
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$overlay = Join-Path $repo 'overlay\claude-gui'
$serverFile = Join-Path $repo 'server\server-1.1.28-remote.js'
$expectedVersion = '1.1.28'

if (-not (Test-Path -LiteralPath $serverFile)) { throw "找不到远程版 server.js：$serverFile" }
if (-not (Test-Path -LiteralPath $overlay)) { throw "找不到 overlay 目录：$overlay" }

# 自动找客户端目录；找不到再让用户输入
if (-not $AppDir) {
  $candidates = @(
    (Join-Path (Split-Path -Parent $repo) 'claude-gui'),
    'E:\AI_Claude\claude-gui',
    (Join-Path $env:USERPROFILE 'AI_Claude\claude-gui'),
    'D:\AI_Claude\claude-gui',
    'C:\AI_Claude\claude-gui'
  )
  foreach ($c in $candidates) {
    if (Test-Path -LiteralPath (Join-Path $c 'server.js')) { $AppDir = $c; break }
  }
}
if (-not $AppDir) {
  Write-Host '没有自动找到工作台客户端目录。'
  $AppDir = (Read-Host '请输入 claude-gui 文件夹的完整路径（例如 E:\AI_Claude\claude-gui）').Trim('"', ' ')
}
$AppDir = [IO.Path]::GetFullPath($AppDir).TrimEnd('\')
$serverPath = Join-Path $AppDir 'server.js'
$versionPath = Join-Path $AppDir 'version.json'
if (-not (Test-Path -LiteralPath $serverPath)) { throw "不是工作台客户端目录，找不到 server.js：$AppDir" }
if (-not (Test-Path -LiteralPath (Join-Path $AppDir 'public'))) { throw "客户端目录里没有 public：$AppDir" }

$version = ''
if (Test-Path -LiteralPath $versionPath) {
  try { $version = [string](Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8 | ConvertFrom-Json).version } catch { }
}
if ($version -ne $expectedVersion -and -not $Force) {
  throw ("这个远程包适配客户端 $expectedVersion，当前是 " + ($(if ($version) { $version } else { '未知' })) +
    "。客户端升级后需要重新生成 server-<版本>-remote.js；确认要试可以加 -Force。")
}

# 备份会被覆盖的文件
$backup = Join-Path $AppDir ('data\backups\remote-install-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Copy-Item -LiteralPath $serverPath -Destination (Join-Path $backup 'server.js') -Force
Get-ChildItem -LiteralPath $overlay -Recurse -File | ForEach-Object {
  $rel = $_.FullName.Substring($overlay.Length).TrimStart('\')
  $dst = Join-Path $AppDir $rel
  if (Test-Path -LiteralPath $dst) {
    $b = Join-Path $backup $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $b -Parent) | Out-Null
    Copy-Item -LiteralPath $dst -Destination $b -Force
  }
}

Write-Host "客户端目录：$AppDir"
Write-Host "备份目录：$backup"
Write-Host '正在复制远程版 server.js 和手机页...'

Copy-Item -LiteralPath $serverFile -Destination $serverPath -Force
Get-ChildItem -LiteralPath $overlay -Recurse -File | ForEach-Object {
  $rel = $_.FullName.Substring($overlay.Length).TrimStart('\')
  $dst = Join-Path $AppDir $rel
  New-Item -ItemType Directory -Force -Path (Split-Path $dst -Parent) | Out-Null
  Copy-Item -LiteralPath $_.FullName -Destination $dst -Force
}

# 语法检查；失败就还原 server.js，避免客户端起不来
& node --check $serverPath
if ($LASTEXITCODE -ne 0) {
  Copy-Item -LiteralPath (Join-Path $backup 'server.js') -Destination $serverPath -Force
  throw 'server.js 语法检查失败，已还原。请检查客户端版本是否匹配。'
}

$apkDir = Join-Path $repo 'apk'
Write-Host ''
Write-Host '安装完成。还需要三步：'
Write-Host '  1. 从托盘退出工作台，再双击客户端的「启动界面.cmd」重新打开（让新 server.js 生效）。'
Write-Host '  2. 双击客户端的「远程访问.cmd」，窗口里会打印手机地址（带 token）。'
Write-Host '  3. 手机安装 APK（在下面目录），首次打开把第 2 步的地址粘贴进去：'
Write-Host "     $apkDir"
Write-Host ''
Write-Host '也可以不用 APK：手机 Chrome 打开「远程访问.cmd」打印的 HTTPS 地址，菜单里选「安装应用」。'
Write-Host ''
Write-Host '注意：客户端以后做核心更新时，更新包不会包含远程功能；'
Write-Host '更新后需要重新运行本安装器（或等待远程包适配新版本）。'
if ((Test-Path -LiteralPath $apkDir) -and -not $NoOpen) {
  Start-Process explorer.exe $apkDir
}
