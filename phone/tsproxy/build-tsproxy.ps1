param(
    [string]$Go = $(if ($env:GO_EXE) { $env:GO_EXE } else { 'go' }),
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
Push-Location $here
try {
    if (-not $env:GOPROXY) { $env:GOPROXY = 'https://goproxy.cn,direct' }
    $env:CGO_ENABLED = '0'
    $env:GOOS = 'linux'
    $env:GOARCH = 'arm64'
    & $Go mod vendor
    if ($LASTEXITCODE -ne 0) { throw 'go mod vendor failed' }
    & $Python (Join-Path $here 'patch-netmon.py')
    if ($LASTEXITCODE -ne 0) { throw 'patch-netmon.py failed' }
    & $Go build -mod=vendor -trimpath -ldflags '-s -w' -o tsproxy-linux-arm64 .
    if ($LASTEXITCODE -ne 0) { throw 'go build failed' }
    Write-Host 'built phone/tsproxy/tsproxy-linux-arm64'
} finally {
    Pop-Location
}
