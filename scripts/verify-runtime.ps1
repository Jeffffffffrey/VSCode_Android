#Requires -Version 5.1
<#
.SYNOPSIS
    校验 jniLibs 内的 ELF 是否为 aarch64、是否满足 16KB 页对齐，并核对 assets 完整性。

.DESCRIPTION
    Android 15+ 要求 16KB page size 兼容，否则原生库无法加载。
    Termux 的 proot/busybox 若为 4KB 对齐，在部分新设备上会直接报错。
    本脚本读取 ELF 程序头中的 p_align 给出结论。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/verify-runtime.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [switch]$SkipArchives
)

$ErrorActionPreference = "Continue"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$jniDir = Join-Path $RepoRoot "app\src\main\jniLibs\arm64-v8a"
$assetsDir = Join-Path $RepoRoot "app\src\main\assets\runtime"

$script:errors = 0
$script:warnings = 0

function Write-Ok($text) { Write-Host "  [OK]   $text" -ForegroundColor Green }
function Write-Bad($text) { Write-Host "  [FAIL] $text" -ForegroundColor Red; $script:errors++ }
function Write-Warn($text) { Write-Host "  [WARN] $text" -ForegroundColor Yellow; $script:warnings++ }

function Read-ElfInfo {
    param([string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 64) { return $null }
    if (-not ($bytes[0] -eq 0x7F -and $bytes[1] -eq 0x45 -and $bytes[2] -eq 0x4C -and $bytes[3] -eq 0x46)) {
        return $null
    }

    $bits = $bytes[4]                      # 2 = 64-bit
    $endian = $bytes[5]                    # 1 = little endian
    $machine = [BitConverter]::ToUInt16($bytes, 0x12)

    $phoff = [BitConverter]::ToUInt64($bytes, 0x20)
    $phentsize = [BitConverter]::ToUInt16($bytes, 0x36)
    $phnum = [BitConverter]::ToUInt16($bytes, 0x38)

    $maxAlign = 0
    for ($i = 0; $i -lt $phnum; $i++) {
        $base = [int]$phoff + ($i * $phentsize)
        if ($base + 56 -gt $bytes.Length) { break }
        $pType = [BitConverter]::ToUInt32($bytes, $base)
        if ($pType -eq 1) {   # PT_LOAD
            $align = [BitConverter]::ToUInt64($bytes, $base + 0x30)
            if ($align -gt $maxAlign) { $maxAlign = $align }
        }
    }

    return [pscustomobject]@{
        Bits     = $bits
        Endian   = $endian
        Machine  = $machine
        MaxAlign = $maxAlign
    }
}

Write-Host ""
Write-Host "== 校验 jniLibs (arm64-v8a) ==" -ForegroundColor Cyan

if (-not (Test-Path $jniDir)) {
    Write-Bad "目录不存在：$jniDir（先运行 scripts\prepare-jnilibs.ps1）"
}
else {
    $libs = Get-ChildItem -Path $jniDir -Filter "*.so" -File -ErrorAction SilentlyContinue
    if (-not $libs) {
        Write-Bad "jniLibs 为空（先运行 scripts\prepare-jnilibs.ps1）"
    }

    $required = @("libproot.so", "libloader.so", "liblibtalloc.so.2.so", "liblibandroid-shmem.so")
    foreach ($name in $required) {
        if (-not ($libs | Where-Object { $_.Name -eq $name })) {
            Write-Bad "缺少 $name"
        }
    }

    foreach ($lib in $libs) {
        $info = Read-ElfInfo -Path $lib.FullName
        if (-not $info) {
            Write-Bad "$($lib.Name) 不是有效的 ELF"
            continue
        }
        if ($info.Bits -ne 2) {
            Write-Bad "$($lib.Name) 不是 64 位 ELF"
            continue
        }
        if ($info.Endian -ne 1) {
            Write-Bad "$($lib.Name) 不是小端 ELF"
            continue
        }
        if ($info.Machine -ne 0xB7) {
            Write-Bad ("{0} 架构不是 aarch64 (e_machine=0x{1:X})" -f $lib.Name, $info.Machine)
            continue
        }

        $alignKb = [math]::Round($info.MaxAlign / 1KB)
        if ($info.MaxAlign -ge 0x4000) {
            Write-Ok "$($lib.Name) aarch64，p_align=${alignKb}KB"
        }
        else {
            Write-Warn "$($lib.Name) aarch64，但 p_align=${alignKb}KB（Android 15+ 16KB 页设备可能无法加载）"
        }
    }
}

if (-not $SkipArchives) {
    Write-Host ""
    Write-Host "== 校验 assets/runtime ==" -ForegroundColor Cyan

    $manifestPath = Join-Path $assetsDir "manifest.json"
    if (-not (Test-Path $manifestPath)) {
        Write-Bad "缺少 manifest.json（先运行 scripts\fetch-runtime.ps1）"
    }
    else {
        $manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
        Write-Ok "code-server $($manifest.codeServerVersion) / Ubuntu $($manifest.ubuntuVersion)"

        foreach ($prop in $manifest.files.PSObject.Properties) {
            $file = Join-Path $assetsDir $prop.Name
            if (-not (Test-Path $file)) {
                Write-Bad "缺少 $($prop.Name)"
                continue
            }
            $size = (Get-Item $file).Length
            if ($size -ne $prop.Value.size) {
                Write-Bad "$($prop.Name) 大小不符（期望 $($prop.Value.size)，实际 $size）"
                continue
            }
            $hash = (Get-FileHash -Path $file -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($hash -ne $prop.Value.sha256) {
                Write-Bad "$($prop.Name) SHA-256 不符"
            }
            else {
                Write-Ok "$($prop.Name) 完整 ($([math]::Round($size / 1MB, 1)) MB)"
            }
        }
    }
}

Write-Host ""
if ($script:errors -gt 0) {
    Write-Host "校验未通过：$($script:errors) 个错误，$($script:warnings) 个警告" -ForegroundColor Red
    exit 1
}
Write-Host "校验通过（$($script:warnings) 个警告）" -ForegroundColor Green
exit 0
