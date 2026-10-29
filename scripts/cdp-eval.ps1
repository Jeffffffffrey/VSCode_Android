#Requires -Version 5.1
<#
.SYNOPSIS
    在应用的 WebView 里执行一段 JS 并打印结果（调试用）。

.DESCRIPTION
    依赖 WebView 的 DevTools 协议：debug 构建下 setWebContentsDebuggingEnabled(true) 已生效。
    先用 scripts\cdp-forward.ps1 或手工执行：
        adb forward tcp:9222 localabstract:webview_devtools_remote_<应用pid>

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/cdp-eval.ps1 -Expression "location.href"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Expression,
    [int]$Port = 9222
)

$ErrorActionPreference = "Stop"

function Receive-UntilId {
    param($Socket, [int]$Id, $Token)

    $buffer = New-Object byte[] 262144
    $segment = [System.ArraySegment[byte]]::new($buffer)
    $builder = New-Object System.Text.StringBuilder

    for ($i = 0; $i -lt 40; $i++) {
        $builder.Clear() | Out-Null
        while ($true) {
            $result = $Socket.ReceiveAsync($segment, $Token).GetAwaiter().GetResult()
            if ($result.Count -gt 0) {
                $builder.Append([System.Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)) | Out-Null
            }
            if ($result.EndOfMessage) { break }
        }
        $text = $builder.ToString()
        if ($text -match "`"id`":\s*$Id") { return $text }
    }
    return $null
}

$targets = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 5
$page = $targets | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
if (-not $page) { throw "没有可用的 page target，请确认 adb forward 已建立" }

$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$cts = [System.Threading.CancellationTokenSource]::new()
$socket.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $cts.Token).GetAwaiter().GetResult()

$payload = @{
    id     = 1
    method = 'Runtime.evaluate'
    params = @{
        expression    = $Expression
        returnByValue = $true
        awaitPromise  = $true
    }
} | ConvertTo-Json -Depth 8 -Compress

$bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
$socket.SendAsync([System.ArraySegment[byte]]::new($bytes), 'Text', $true, $cts.Token).GetAwaiter().GetResult()

$response = Receive-UntilId -Socket $socket -Id 1 -Token $cts.Token
$socket.Dispose()

if (-not $response) { throw "未收到 id=1 的响应" }

$obj = $response | ConvertFrom-Json
if ($obj.result.exceptionDetails) {
    Write-Host "JS 异常：" -ForegroundColor Red
    Write-Host ($obj.result.exceptionDetails | ConvertTo-Json -Depth 6)
    exit 1
}

$value = $obj.result.result.value
if ($null -eq $value) {
    Write-Host "(undefined)" -ForegroundColor DarkGray
} else {
    $value | ConvertTo-Json -Depth 8
}
