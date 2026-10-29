#Requires -Version 5.1
<#
.SYNOPSIS
    从 Termux 软件源提取 aarch64 ELF，改名成 lib*.so 放进 jniLibs。

.DESCRIPTION
    Android 10+ 的应用私有目录是 noexec，无法直接执行 ELF；
    nativeLibraryDir 例外，所以把可执行文件伪装成 .so 打进 APK，
    运行时由 NativeBinaryInstaller 建立符号链接指回。

    内嵌的 Termux 组件（依赖链已实测闭合）：
      proot              → libproot.so             (PT_INTERP 指向 /system/bin/linker64)
      proot loader       → libloader.so            (PROOT_LOADER)
      libtalloc.so.2     → liblibtalloc.so.2.so
      libandroid-shmem   → liblibandroid-shmem.so  (proot 声明依赖)

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/prepare-jnilibs.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [string]$TermuxRepo = "https://packages.termux.dev/apt/termux-main",
    [string]$Arch = "aarch64"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$jniDir = Join-Path $RepoRoot "app\src\main\jniLibs\arm64-v8a"
$work = Join-Path $env:TEMP "vsc-android-jnilibs"
New-Item -ItemType Directory -Force -Path $jniDir, $work | Out-Null

function Write-Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "    $text" -ForegroundColor Yellow }

function Expand-DebData {
    param([string]$DebPath, [string]$OutDir)

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $stream = [System.IO.File]::OpenRead($DebPath)
    try {
        $reader = New-Object System.IO.BinaryReader($stream)
        $magic = [System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(8))
        if (-not $magic.StartsWith("!<arch>")) { throw "不是 ar 归档：$DebPath" }

        while (($stream.Position + 60) -le $stream.Length) {
            $header = [System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(60))
            # GNU ar 会以 '/' 结尾标记成员名，直接当文件名会得到非法路径
            $name = $header.Substring(0, 16).Trim().TrimEnd('/').Trim()
            $sizeText = $header.Substring(48, 10).Trim()
            if ([string]::IsNullOrWhiteSpace($sizeText)) { break }
            $size = [int]$sizeText
            $dataStart = $stream.Position

            if ($name -like "data.tar*") {
                $suffix = $name.Substring(8).TrimEnd('/')
                $tarPath = Join-Path $OutDir ("payload" + $suffix)
                $buffer = New-Object byte[] 1048576
                $out = [System.IO.File]::Create($tarPath)
                try {
                    $remaining = $size
                    while ($remaining -gt 0) {
                        $toRead = [math]::Min($buffer.Length, $remaining)
                        $read = $stream.Read($buffer, 0, $toRead)
                        if ($read -le 0) { break }
                        $out.Write($buffer, 0, $read)
                        $remaining -= $read
                    }
                }
                finally { $out.Dispose() }
                return $tarPath
            }

            $stream.Position = $dataStart + $size
            if ($size % 2 -eq 1) { $stream.Position++ }
        }
        throw "未在 deb 中找到 data.tar 成员：$DebPath"
    }
    finally { $stream.Dispose() }
}

# ---------------------------------------------------------- 解析 Termux 索引

Write-Step "读取 Termux $Arch 软件包索引"
$indexUri = "$TermuxRepo/dists/stable/main/binary-$Arch/Packages"
$indexFile = Join-Path $work "Packages-$Arch"
Invoke-WebRequest -Uri $indexUri -OutFile $indexFile -UseBasicParsing -TimeoutSec 300
$indexText = Get-Content $indexFile -Raw
Write-Ok "索引大小 $([math]::Round((Get-Item $indexFile).Length / 1KB)) KB"

function Get-PackageFilename {
    param([string]$PackageName)

    foreach ($stanza in ($indexText -split "(?m)^\s*$")) {
        if ($stanza -match "(?m)^Package:\s*$([regex]::Escape($PackageName))\s*$") {
            if ($stanza -match "(?m)^Filename:\s*(\S+)") {
                return $Matches[1]
            }
        }
    }
    throw "索引中找不到软件包 $PackageName"
}

# ------------------------------------------------------------------ 下载解包

$wanted = @("proot", "libtalloc", "libandroid-shmem")
$extractRoots = @{}

foreach ($pkg in $wanted) {
    $filename = Get-PackageFilename -PackageName $pkg
    $debUri = "$TermuxRepo/$filename"
    # Termux 的 deb 文件名带 epoch（如 termux-exec_1:2.5.0-1），冒号在 Windows 上非法
    $debName = (Split-Path -Leaf $filename) -replace ':', '_'
    $debPath = Join-Path $work $debName

    Write-Step $pkg
    if (-not (Test-Path $debPath)) {
        Write-Host "    下载 $debUri"
        Invoke-WebRequest -Uri $debUri -OutFile $debPath -UseBasicParsing -TimeoutSec 600
    }
    else {
        Write-Ok "已缓存 $(Split-Path -Leaf $debPath)"
    }

    $payload = Expand-DebData -DebPath $debPath -OutDir (Join-Path $work "ar-$pkg")
    $extractDir = Join-Path $work "x-$pkg"
    if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null

    # bsdtar 无法在 Windows 上创建 Termux 的绝对路径符号链接，报错属预期
    $ErrorActionPreference = "Continue"
    & tar -xf $payload -C $extractDir 2>$null
    $ErrorActionPreference = "Stop"

    $extractRoots[$pkg] = $extractDir
    Write-Ok "解压完成"
}

# ------------------------------------------------------------------ 定位 ELF

function Find-TermuxFile {
    param([string]$Root, [scriptblock]$Predicate, [string]$What)

    $hit = Get-ChildItem -Path $Root -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object $Predicate |
        Sort-Object Length -Descending |
        Select-Object -First 1
    if (-not $hit) { throw "在 $Root 中找不到 $What" }
    return $hit.FullName
}

$prootPath = Find-TermuxFile -Root $extractRoots["proot"] -What "proot" -Predicate {
    $_.Name -eq "proot" -and $_.FullName -match "[/\\]bin[/\\]"
}

$loaderPath = Find-TermuxFile -Root $extractRoots["proot"] -What "proot loader" -Predicate {
    $_.Name -eq "loader" -or $_.Name -like "*proot-loader*"
}

$tallocPath = Find-TermuxFile -Root $extractRoots["libtalloc"] -What "libtalloc.so.2" -Predicate {
    $_.Name -like "libtalloc.so.2.*"
}

$shmemPath = Find-TermuxFile -Root $extractRoots["libandroid-shmem"] -What "libandroid-shmem.so" -Predicate {
    $_.Name -like "libandroid-shmem.so*"
}

# ------------------------------------------------------------------ 复制改名

Write-Step "写入 jniLibs"
$mapping = @(
    @{ Source = $prootPath; Target = "libproot.so" },
    @{ Source = $loaderPath; Target = "libloader.so" },
    @{ Source = $tallocPath; Target = "liblibtalloc.so.2.so" },
    @{ Source = $shmemPath; Target = "liblibandroid-shmem.so" }
)

foreach ($item in $mapping) {
    $dest = Join-Path $jniDir $item.Target
    Copy-Item -Path $item.Source -Destination $dest -Force
    $size = [math]::Round((Get-Item $dest).Length / 1KB)
    Write-Ok ("{0,-26} <- {1} ({2} KB)" -f $item.Target, (Split-Path -Leaf $item.Source), $size)
}

# 清理上一版遗留的 busybox（改由 Kotlin 直接解包，不再需要）
$staleBusybox = Join-Path $jniDir "libbusybox.so"
if (Test-Path $staleBusybox) {
    Remove-Item -Force $staleBusybox
    Write-Ok "已移除不再使用的 libbusybox.so"
}

Write-Host ""
Write-Step "校验 ELF 架构与页对齐"
& (Join-Path $PSScriptRoot "verify-runtime.ps1") -RepoRoot $RepoRoot -SkipArchives
