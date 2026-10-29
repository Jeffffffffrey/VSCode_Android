#Requires -Version 5.1
<#
.SYNOPSIS
    下载并校验内置运行时资产：code-server(linux-arm64) 与 Ubuntu Base 24.04 arm64 rootfs。

.DESCRIPTION
    产物写入 app/src/main/assets/runtime，同时生成 manifest.json 供 verify-runtime.ps1 复核。
    已存在且 SHA-256 一致的文件会跳过，支持中断后重跑。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/fetch-runtime.ps1
    powershell -ExecutionPolicy Bypass -File scripts/fetch-runtime.ps1 -CodeServerVersion 4.143.1 -UbuntuVersion 24.04.5
#>
[CmdletBinding()]
param(
    [string]$CodeServerVersion = "4.143.1",
    [string]$UbuntuVersion = "24.04.5",
    [string]$UbuntuSeries = "24.04",
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$assetsDir = Join-Path $RepoRoot "app\src\main\assets\runtime"
$cacheDir = Join-Path $env:TEMP "vsc-android-runtime"
New-Item -ItemType Directory -Force -Path $assetsDir, $cacheDir | Out-Null

# 已知官方 SHA-256（GitHub Release 摘要），为空则跳过强校验
$knownHashes = @{
    "4.143.1" = "51481a891754dc2585b86d007aec24ac675453fc5ef6cfa346c4462449277384"
}

function Write-Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn($text) { Write-Host "    $text" -ForegroundColor Yellow }

function Get-RemoteFile {
    param([string]$Uri, [string]$Target)

    if (Test-Path $Target) {
        Write-Ok "已存在，跳过下载：$(Split-Path -Leaf $Target)"
        return
    }

    $tmp = "$Target.part"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Write-Host "    下载 $Uri (第 $attempt 次)"
            Invoke-WebRequest -Uri $Uri -OutFile $tmp -UseBasicParsing -TimeoutSec 1800
            Move-Item -Force $tmp $Target
            Write-Ok "完成，大小 $([math]::Round((Get-Item $Target).Length / 1MB, 1)) MB"
            return
        }
        catch {
            Write-Warn "下载失败：$($_.Exception.Message)"
            Remove-Item -Force $tmp -ErrorAction SilentlyContinue
            if ($attempt -eq 3) { throw "下载失败：$Uri" }
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
}

function Get-Sha256($Path) { (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

# ---------------------------------------------------------------- code-server

$csUri = "https://github.com/coder/code-server/releases/download/v$CodeServerVersion/code-server-$CodeServerVersion-linux-arm64.tar.gz"
# 必须用 .tgz：aapt2 会把 .gz 结尾的 asset 自动解压并剥掉后缀
$csTarget = Join-Path $assetsDir "code-server-arm64.tgz"

Write-Step "code-server $CodeServerVersion (linux-arm64)"
Get-RemoteFile -Uri $csUri -Target $csTarget

$csHash = Get-Sha256 $csTarget
$expected = $knownHashes[$CodeServerVersion]
if ($expected) {
    if ($csHash -ne $expected) {
        Remove-Item -Force $csTarget
        throw "code-server 校验失败：期望 $expected，实际 $csHash（已删除损坏文件，请重跑）"
    }
    Write-Ok "SHA-256 校验通过"
}
else {
    Write-Warn "该版本无内置基准哈希，仅记录：$csHash"
}

# -------------------------------------------------------------------- rootfs

$ubuntuName = "ubuntu-base-$UbuntuVersion-base-arm64.tar.gz"
$ubuntuUri = "https://cdimage.ubuntu.com/ubuntu-base/releases/$UbuntuSeries/release/$ubuntuName"
$rootfsTarget = Join-Path $assetsDir "rootfs.tgz"

Write-Step "Ubuntu Base $UbuntuVersion (arm64 rootfs)"
Get-RemoteFile -Uri $ubuntuUri -Target $rootfsTarget

Write-Step "校验 rootfs 完整性（对比官方 SHA256SUMS）"
$sumsUri = "https://cdimage.ubuntu.com/ubuntu-base/releases/$UbuntuSeries/release/SHA256SUMS"
$sumsFile = Join-Path $cacheDir "ubuntu-SHA256SUMS"
Invoke-WebRequest -Uri $sumsUri -OutFile $sumsFile -UseBasicParsing -TimeoutSec 120

$sumLine = Get-Content $sumsFile | Where-Object { $_ -match [regex]::Escape($ubuntuName) } | Select-Object -First 1
if ($sumLine) {
    $expectedUbuntu = ($sumLine -split '\s+')[0].ToLowerInvariant()
    $actualUbuntu = Get-Sha256 $rootfsTarget
    if ($expectedUbuntu -ne $actualUbuntu) {
        Remove-Item -Force $rootfsTarget
        throw "rootfs 校验失败：期望 $expectedUbuntu，实际 $actualUbuntu（已删除，请重跑）"
    }
    Write-Ok "SHA-256 校验通过"
}
else {
    Write-Warn "SHA256SUMS 中未找到 $ubuntuName，跳过校验"
}

# ---------------------------------------------------------------- manifest

Write-Step "生成 manifest.json"
$manifest = [ordered]@{
    codeServerVersion = $CodeServerVersion
    codeServerPackage = "code-server-$CodeServerVersion-linux-arm64"
    ubuntuVersion     = $UbuntuVersion
    generatedAt       = (Get-Date).ToString("o")
    files             = [ordered]@{
        "code-server-arm64.tgz" = [ordered]@{
            source = $csUri
            sha256 = (Get-Sha256 $csTarget)
            size   = (Get-Item $csTarget).Length
        }
        "rootfs.tgz"            = [ordered]@{
            source = $ubuntuUri
            sha256 = (Get-Sha256 $rootfsTarget)
            size   = (Get-Item $rootfsTarget).Length
        }
    }
}
$manifestPath = Join-Path $assetsDir "manifest.json"
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestPath -Encoding UTF8
Write-Ok $manifestPath

$configFile = Join-Path $RepoRoot "app\src\main\java\com\vsc\android\runtime\RuntimeConfig.kt"
if (Test-Path $configFile) {
    $declared = (Select-String -Path $configFile -Pattern 'DEFAULT_CS_VERSION\s*=\s*"([^"]+)"').Matches.Groups[1].Value
    if ($declared -and $declared -ne $CodeServerVersion) {
        Write-Warn "注意：RuntimeConfig.DEFAULT_CS_VERSION=$declared 与下载版本 $CodeServerVersion 不一致，请同步修改"
    }
}

Write-Host ""
Write-Host "运行时资产就绪。下一步：scripts\prepare-jnilibs.ps1，然后重新构建 APK。" -ForegroundColor Cyan
