# ============================================================
#  Vex VPN - PC Edition (Windows x64)
#  Core: sing-box  |  UI: WinForms (Persian)  |  Proxy: system auto
# ============================================================
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$AppKey         = 'vexk_8c3b1e664023f4df081d8dbb'
$GateHosts      = @('https://demonvpn.shop', 'https://vex-gate.pages.dev')
$CorePath       = Join-Path $PSScriptRoot 'sing-box.exe'
$BundledServers = Join-Path $PSScriptRoot 'servers.json'
$AppDir         = Join-Path $env:LOCALAPPDATA 'VexPC'
$ConfigPath     = Join-Path $AppDir 'config.json'
$LogPath        = Join-Path $AppDir 'singbox.log'
$SelPath        = Join-Path $AppDir 'selected.txt'
$ProxyAddr      = '127.0.0.1:2080'
$RegPath        = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$Script:VexProc = $null

New-Item -ItemType Directory -Force -Path $AppDir | Out-Null

if (-not (Test-Path $CorePath)) {
    [System.Windows.Forms.MessageBox]::Show("sing-box.exe در کنار برنامه پیدا نشد!" + [Environment]::NewLine + "فایل ZIP را کامل استخراج کنید.", 'Vex PC', 'OK', 'Error') | Out-Null
    exit 1
}

# ---------- system proxy (WinINET refresh) ----------
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class VexWinInet {
    [DllImport("wininet.dll", SetLastError = true)]
    public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);
    public static void Refresh() {
        InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0); // INTERNET_OPTION_OPTION_SETTINGS_CHANGED
        InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0); // INTERNET_OPTION_REFRESH
    }
}
"@

function Set-SystemProxy {
    param([bool]$On)
    try {
        if ($On) {
            Set-ItemProperty -Path $RegPath -Name ProxyEnable -Value 1
            Set-ItemProperty -Path $RegPath -Name ProxyServer -Value $ProxyAddr
            Set-ItemProperty -Path $RegPath -Name ProxyOverride -Value '<local>' -ErrorAction SilentlyContinue
        } else {
            Set-ItemProperty -Path $RegPath -Name ProxyEnable -Value 0
        }
        [VexWinInet]::Refresh()
    } catch { }
}

# ---------- share-link parsing (mirror of validated logic) ----------
function Convert-B64 {
    param([string]$s)
    $s = $s.Trim().Replace('-', '+').Replace('_', '/').Replace("`r", '').Replace("`n", '').Replace(' ', '')
    while (($s.Length % 4) -ne 0) { $s += '=' }
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
}

function Parse-Query {
    param([string]$query)
    $h = @{}
    if (-not $query) { return $h }
    $q = $query.TrimStart('?')
    foreach ($p in $q.Split('&')) {
        if ($p.Contains('=')) {
            $i = $p.IndexOf('=')
            $k = [Uri]::UnescapeDataString($p.Substring(0, $i))
            $v = [Uri]::UnescapeDataString($p.Substring($i + 1))
            if ($k) { $h[$k] = $v }
        }
    }
    return $h
}

function New-TlsObj {
    param([hashtable]$q, [string]$defSni)
    $sec = 'none'
    if ($q.ContainsKey('security') -and $q['security']) { $sec = $q['security'].ToLower() }
    if (($sec -ne 'tls') -and ($sec -ne 'reality')) { return $null }
    $t = @{ enabled = $true }
    $sni = $null
    if ($q.ContainsKey('sni') -and $q['sni']) { $sni = $q['sni'] }
    elseif ($q.ContainsKey('host') -and $q['host']) { $sni = $q['host'] }
    else { $sni = $defSni }
    if ($sni) { $t.server_name = $sni }
    $fp = $null
    if ($q.ContainsKey('fp')) { $fp = $q['fp'] }
    if ($fp -and ($fp -ne 'none')) { $t.utls = @{ enabled = $true; fingerprint = $fp } }
    if ($sec -eq 'reality') {
        $pbk = ''; if ($q.ContainsKey('pbk')) { $pbk = $q['pbk'] }
        $sid = ''; if ($q.ContainsKey('sid')) { $sid = $q['sid'] }
        $t.reality = @{ enabled = $true; public_key = $pbk; short_id = $sid }
    }
    $insec = '0'
    if ($q.ContainsKey('allowInsecure')) { $insec = $q['allowInsecure'] }
    elseif ($q.ContainsKey('insecure')) { $insec = $q['insecure'] }
    if (($insec -eq '1') -or ($insec -eq 'true')) { $t.insecure = $true }
    return $t
}

function New-TransportObj {
    param([hashtable]$q)
    $net = 'tcp'
    if ($q.ContainsKey('type') -and $q['type']) { $net = $q['type'].ToLower() }
    if ($net -eq 'ws') {
        $tr = @{ type = 'ws' }
        if ($q.ContainsKey('path') -and $q['path']) { $tr.path = $q['path'] } else { $tr.path = '/' }
        if ($q.ContainsKey('host') -and $q['host']) { $tr.headers = @{ Host = $q['host'] } }
        return $tr
    }
    if ($net -eq 'grpc') {
        $sn = ''
        if ($q.ContainsKey('serviceName') -and $q['serviceName']) { $sn = $q['serviceName'] }
        elseif ($q.ContainsKey('path') -and $q['path']) { $sn = $q['path'] }
        return @{ type = 'grpc'; service_name = $sn }
    }
    if ($net -eq 'http') {
        $tr = @{ type = 'http' }
        if ($q.ContainsKey('path') -and $q['path']) { $tr.path = $q['path'] } else { $tr.path = '/' }
        if ($q.ContainsKey('host') -and $q['host']) { $tr.host = @($q['host']) }
        return $tr
    }
    return $null
}

function Out-FromVmess {
    param([string]$raw)
    $j = Convert-B64 $raw.Substring(8) | ConvertFrom-Json
    $q = @{ security = 'none'; type = 'tcp'; path = ''; host = ''; sni = ''; fp = '' }
    if ($j.PSObject.Properties['net'] -and $j.net) { $q['type'] = [string]$j.net }
    if ($j.PSObject.Properties['path'] -and $j.path) { $q['path'] = [string]$j.path }
    if ($j.PSObject.Properties['host'] -and $j.host) { $q['host'] = [string]$j.host }
    if ($j.PSObject.Properties['sni'] -and $j.sni) { $q['sni'] = [string]$j.sni }
    if ($j.PSObject.Properties['fp'] -and $j.fp) { $q['fp'] = [string]$j.fp }
    if ($j.PSObject.Properties['tls']) {
        $tv = $j.tls
        if (($tv -eq 'tls') -or ($tv -eq $true) -or ($tv -eq 1)) { $q['security'] = 'tls' }
    }
    $aid = 0
    if ($j.PSObject.Properties['aid'] -and $j.aid) { $aid = [int]$j.aid }
    $scy = 'auto'
    if ($j.PSObject.Properties['scy'] -and $j.scy) { $scy = [string]$j.scy }
    $out = [ordered]@{ type = 'vmess'; tag = 'proxy'; server = [string]$j.add; server_port = [int]$j.port; uuid = [string]$j.id; alter_id = $aid; security = $scy }
    $t = New-TlsObj $q ([string]$j.add)
    if ($t) { $out.tls = $t }
    $tr = New-TransportObj $q
    if ($tr) { $out.transport = $tr }
    return $out
}

function Out-FromVlessTrojan {
    param([string]$raw)
    $rest = $raw
    $hi = $raw.IndexOf('#')
    if ($hi -ge 0) { $rest = $raw.Substring(0, $hi) }
    if ($rest -match '^[a-zA-Z]+://([^@]+)@([^:/]+):([0-9]+)(.*)$') {
        $user = $Matches[1]
        $host = $Matches[2]
        $port = [int]$Matches[3]
        $tail = $Matches[4]
    } else {
        throw 'لینک نامعتبر است'
    }
    $query = ''
    $qi = $tail.IndexOf('?')
    if ($qi -ge 0) { $query = $tail.Substring($qi) }
    $q = Parse-Query $query
    if ($raw.ToLower().StartsWith('vless://')) {
        $out = [ordered]@{ type = 'vless'; tag = 'proxy'; server = $host; server_port = $port; uuid = $user }
        if ($q.ContainsKey('flow') -and $q['flow']) { $out.flow = $q['flow'] }
    } else {
        $out = [ordered]@{ type = 'trojan'; tag = 'proxy'; server = $host; server_port = $port; password = [Uri]::UnescapeDataString($user) }
    }
    $t = New-TlsObj $q $host
    if ($t) { $out.tls = $t }
    $tr = New-TransportObj $q
    if ($tr) { $out.transport = $tr }
    return $out
}

function Out-FromSS {
    param([string]$raw)
    $body = $raw.Substring(5)
    $hi = $body.IndexOf('#')
    if ($hi -ge 0) { $body = $body.Substring(0, $hi) }
    if ($body -match '^([^@]+)@([^:/]+):([0-9]+)') {
        $ui = $Matches[1]; $host = $Matches[2]; $port = [int]$Matches[3]
        if ($ui -notmatch ':') { $ui = Convert-B64 $ui }
        $ci = $ui.IndexOf(':')
        if ($ci -lt 0) { throw 'لینک ss نامعتبر است' }
        $method = $ui.Substring(0, $ci)
        $pw = [Uri]::UnescapeDataString($ui.Substring($ci + 1))
        return [ordered]@{ type = 'shadowsocks'; tag = 'proxy'; server = $host; server_port = $port; method = $method; password = $pw }
    }
    $dec = Convert-B64 $body
    if ($dec -match '^([^:]+):([^@]+)@([^:/]+):([0-9]+)') {
        return [ordered]@{ type = 'shadowsocks'; tag = 'proxy'; server = $Matches[3]; server_port = [int]$Matches[4]; method = $Matches[1]; password = $Matches[2] }
    }
    throw 'لینک ss نامعتبر است'
}

function Convert-RawToOutbound {
    param([string]$raw)
    $low = $raw.ToLower()
    if ($low.StartsWith('vmess://')) { return Out-FromVmess $raw }
    if ($low.StartsWith('vless://') -or $low.StartsWith('trojan://')) { return Out-FromVlessTrojan $raw }
    if ($low.StartsWith('ss://')) { return Out-FromSS $raw }
    throw 'این نوع سرور در نسخه PC پشتیبانی نمی‌شود'
}

# ---------- server list (cloud -> bundled fallback) ----------
function Get-Servers {
    foreach ($h in $GateHosts) {
        try {
            $r = Invoke-RestMethod -Uri ($h + '/v1/freeservers') -Headers @{ 'X-Vex-Key' = $AppKey } -TimeoutSec 10
            $list = @($r.servers)
            if ($list.Count -gt 0) { return $list }
        } catch { }
    }
    if (Test-Path $BundledServers) {
        try { return @((Get-Content $BundledServers -Raw -Encoding UTF8 | ConvertFrom-Json).servers) } catch { }
    }
    return @()
}

# ---------- connect / disconnect ----------
function Write-Config {
    param($outbound)
    # PS 5.1 ConvertTo-Json collapses single-element arrays, so the config shell
    # is built as a template and only the outbound object goes through JSON conversion.
    $obJson = ConvertTo-Json -InputObject ([pscustomobject]$outbound) -Depth 12
    $logEsc = $LogPath.Replace('\', '\\')
    $template = @"
{
  "log": { "level": "info", "timestamp": true, "output": "__LOG__" },
  "inbounds": [ { "type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 2080 } ],
  "outbounds": [ __OUT__, { "type": "direct", "tag": "direct" } ],
  "route": { "auto_detect_interface": true, "final": "proxy" }
}
"@
    $json = $template.Replace('__LOG__', $logEsc).Replace('__OUT__', $obJson)
    [IO.File]::WriteAllText($ConfigPath, $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Stop-VexCore {
    if ($null -ne $Script:VexProc) {
        try { if (-not $Script:VexProc.HasExited) { $Script:VexProc.Kill() } } catch { }
        $Script:VexProc = $null
    }
    Get-Process -Name 'sing-box' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $CorePath } | Stop-Process -Force -ErrorAction SilentlyContinue
    Set-SystemProxy $false
}

function Start-VexCore {
    param($server)
    Stop-VexCore
    try {
        $ob = Convert-RawToOutbound $server.raw
    } catch {
        return @{ ok = $false; msg = $_.Exception.Message }
    }
    try { Write-Config $ob } catch {
        return @{ ok = $false; msg = 'خطا در ساخت کانفیگ: ' + $_.Exception.Message }
    }
    try {
        $Script:VexProc = Start-Process -FilePath $CorePath -ArgumentList @('run', '-c', ('"' + $ConfigPath + '"')) -WindowStyle Hidden -PassThru
    } catch {
        return @{ ok = $false; msg = 'اجرای هسته ناموفق: ' + $_.Exception.Message }
    }
    Start-Sleep -Seconds 3
    if ($Script:VexProc.HasExited) {
        $err = 'هسته بلافاصله متوقف شد'
        try {
            if (Test-Path $LogPath) {
                $lines = @(Get-Content $LogPath -Tail 3 -Encoding UTF8)
                $err = ($lines -join ' | ')
                if ($err.Length -gt 160) { $err = $err.Substring($err.Length - 160) }
            }
        } catch { }
        $Script:VexProc = $null
        return @{ ok = $false; msg = $err }
    }
    Set-SystemProxy $true
    return @{ ok = $true; msg = '' }
}

function Test-ProxyConnection {
    try {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Invoke-WebRequest -Uri 'https://www.gstatic.com/generate_204' -Proxy ('http://' + $ProxyAddr) -UseBasicParsing -TimeoutSec 12 | Out-Null
        $sw.Stop()
        return @{ ok = $true; ms = $sw.ElapsedMilliseconds }
    } catch {
        return @{ ok = $false; ms = 0 }
    }
}

# ============================================================
#  GUI
# ============================================================
[System.Windows.Forms.Application]::EnableVisualStyles()

$form                 = New-Object System.Windows.Forms.Form
$form.Text            = 'Vex VPN - PC'
$form.Size            = New-Object System.Drawing.Size(480, 400)
$form.StartPosition   = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox     = $false
$form.BackColor       = [System.Drawing.Color]::FromArgb(21, 24, 33)
$form.RightToLeft     = [System.Windows.Forms.RightToLeft]::Yes

$title                = New-Object System.Windows.Forms.Label
$title.Text           = 'Vex VPN'
$title.Font           = New-Object System.Drawing.Font('Segoe UI', 22, [System.Drawing.FontStyle]::Bold)
$title.ForeColor      = [System.Drawing.Color]::FromArgb(138, 180, 255)
$title.AutoSize       = $false
$title.Size           = New-Object System.Drawing.Size(440, 50)
$title.Location       = New-Object System.Drawing.Point(10, 14)
$title.TextAlign      = 'MiddleCenter'
$form.Controls.Add($title)

$sub                  = New-Object System.Windows.Forms.Label
$sub.Text             = 'نسخه ویندوز — هسته sing-box'
$sub.Font             = New-Object System.Drawing.Font('Segoe UI', 10)
$sub.ForeColor        = [System.Drawing.Color]::FromArgb(150, 158, 175)
$sub.AutoSize         = $false
$sub.Size             = New-Object System.Drawing.Size(440, 24)
$sub.Location         = New-Object System.Drawing.Point(10, 62)
$sub.TextAlign        = 'MiddleCenter'
$form.Controls.Add($sub)

$srvLabel             = New-Object System.Windows.Forms.Label
$srvLabel.Text        = 'انتخاب سرور:'
$srvLabel.Font        = New-Object System.Drawing.Font('Segoe UI', 10)
$srvLabel.ForeColor   = [System.Drawing.Color]::Gainsboro
$srvLabel.AutoSize    = $false
$srvLabel.Size        = New-Object System.Drawing.Size(440, 22)
$srvLabel.Location    = New-Object System.Drawing.Point(10, 100)
$srvLabel.TextAlign   = 'MiddleCenter'
$form.Controls.Add($srvLabel)

$combo                = New-Object System.Windows.Forms.ComboBox
$combo.Font           = New-Object System.Drawing.Font('Segoe UI', 11)
$combo.DropDownStyle  = 'DropDownList'
$combo.FlatStyle      = 'Flat'
$combo.Size           = New-Object System.Drawing.Size(420, 32)
$combo.Location       = New-Object System.Drawing.Point(20, 124)
$form.Controls.Add($combo)

$btnConnect                 = New-Object System.Windows.Forms.Button
$btnConnect.Text            = 'اتصال'
$btnConnect.Font            = New-Object System.Drawing.Font('Segoe UI', 11, [System.Drawing.FontStyle]::Bold)
$btnConnect.Size            = New-Object System.Drawing.Size(200, 46)
$btnConnect.Location        = New-Object System.Drawing.Point(240, 176)
$btnConnect.FlatStyle       = 'Flat'
$btnConnect.FlatAppearance.BorderSize = 0
$btnConnect.BackColor       = [System.Drawing.Color]::FromArgb(46, 204, 113)
$btnConnect.ForeColor       = [System.Drawing.Color]::White
$btnConnect.Cursor          = 'Hand'
$form.Controls.Add($btnConnect)

$btnStop                    = New-Object System.Windows.Forms.Button
$btnStop.Text               = 'قطع اتصال'
$btnStop.Font               = New-Object System.Drawing.Font('Segoe UI', 11, [System.Drawing.FontStyle]::Bold)
$btnStop.Size               = New-Object System.Drawing.Size(200, 46)
$btnStop.Location           = New-Object System.Drawing.Point(20, 176)
$btnStop.FlatStyle          = 'Flat'
$btnStop.FlatAppearance.BorderSize = 0
$btnStop.BackColor          = [System.Drawing.Color]::FromArgb(231, 76, 60)
$btnStop.ForeColor          = [System.Drawing.Color]::White
$btnStop.Cursor             = 'Hand'
$form.Controls.Add($btnStop)

$btnTest                    = New-Object System.Windows.Forms.Button
$btnTest.Text               = 'تست اتصال'
$btnTest.Font               = New-Object System.Drawing.Font('Segoe UI', 10)
$btnTest.Size               = New-Object System.Drawing.Size(200, 38)
$btnTest.Location           = New-Object System.Drawing.Point(130, 236)
$btnTest.FlatStyle          = 'Flat'
$btnTest.FlatAppearance.BorderSize = 0
$btnTest.BackColor          = [System.Drawing.Color]::FromArgb(52, 73, 94)
$btnTest.ForeColor          = [System.Drawing.Color]::White
$btnTest.Cursor             = 'Hand'
$form.Controls.Add($btnTest)

$status               = New-Object System.Windows.Forms.Label
$status.Text          = 'وضعیت: در حال دریافت سرورها...'
$status.Font          = New-Object System.Drawing.Font('Segoe UI', 10)
$status.ForeColor     = [System.Drawing.Color]::FromArgb(150, 158, 175)
$status.AutoSize      = $false
$status.Size          = New-Object System.Drawing.Size(440, 46)
$status.Location      = New-Object System.Drawing.Point(10, 292)
$status.TextAlign     = 'MiddleCenter'
$form.Controls.Add($status)

function Set-Status {
    param([string]$text, [System.Drawing.Color]$color)
    $status.Text = $text
    $status.ForeColor = $color
}

function Get-SelectedServer {
    if ($combo.SelectedIndex -lt 0) { return $null }
    return $combo.SelectedItem
}

# ---------- actions ----------
$btnConnect.Add_Click({
    $srv = Get-SelectedServer
    if ($null -eq $srv) { Set-Status 'اول یک سرور انتخاب کن' ([System.Drawing.Color]::OrangeRed); return }
    Set-Status 'در حال اتصال...' ([System.Drawing.Color]::Gold)
    $form.Refresh()
    [System.Windows.Forms.Application]::DoEvents()
    $r = Start-VexCore $srv
    if ($r.ok) {
        try { [IO.File]::WriteAllText($SelPath, [string]$srv.id, (New-Object System.Text.UTF8Encoding($false))) } catch { }
        Set-Status ('وضعیت: متصل ✅ — ' + $srv.name + '  (پروکسی سیستمی روشن شد)') ([System.Drawing.Color]::FromArgb(46, 204, 113))
    } else {
        Set-Status ('خطا: ' + $r.msg) ([System.Drawing.Color]::OrangeRed)
    }
})

$btnStop.Add_Click({
    Stop-VexCore
    Set-Status 'وضعیت: متصل نیست — پروکسی سیستمی خاموش شد' ([System.Drawing.Color]::FromArgb(150, 158, 175))
})

$btnTest.Add_Click({
    if (($null -eq $Script:VexProc) -or ($Script:VexProc.HasExited)) {
        Set-Status 'اول وصل شو، بعد تست بزن' ([System.Drawing.Color]::OrangeRed)
        return
    }
    $btnTest.Enabled = $false
    Set-Status 'در حال تست...' ([System.Drawing.Color]::Gold)
    $form.Refresh()
    [System.Windows.Forms.Application]::DoEvents()
    $r = Test-ProxyConnection
    $btnTest.Enabled = $true
    if ($r.ok) {
        Set-Status ('تست موفق ✅ — تأخیر: ' + $r.ms + ' میلی‌ثانیه') ([System.Drawing.Color]::FromArgb(46, 204, 113))
    } else {
        Set-Status 'تست ناموفق — این سرور جواب نمی‌دهد، سرور دیگری امتحان کن' ([System.Drawing.Color]::OrangeRed)
    }
})

$form.Add_FormClosing({
    Stop-VexCore
})

# watchdog: core died unexpectedly
$timer              = New-Object System.Windows.Forms.Timer
$timer.Interval     = 2000
$timer.Add_Tick({
    if (($null -ne $Script:VexProc) -and ($Script:VexProc.HasExited)) {
        $Script:VexProc = $null
        Set-SystemProxy $false
        Set-Status 'اتصال قطع شد ⚠️ — دوباره وصل شو' ([System.Drawing.Color]::OrangeRed)
    }
})
$timer.Start()

# ---------- load ----------
$servers = Get-Servers
if ($servers.Count -eq 0) {
    Set-Status 'هیچ سروری پیدا نشد — اینترنت را چک کن' ([System.Drawing.Color]::OrangeRed)
} else {
    foreach ($s in $servers) { [void]$combo.Items.Add($s) }
    $combo.DisplayMember = 'name'
    $savedId = $null
    try { if (Test-Path $SelPath) { $savedId = [int](Get-Content $SelPath -Raw) } } catch { }
    if ($savedId -ne $null) {
        for ($i = 0; $i -lt $servers.Count; $i++) {
            if ($servers[$i].id -eq $savedId) { $combo.SelectedIndex = $i; break }
        }
    }
    if ($combo.SelectedIndex -lt 0) { $combo.SelectedIndex = 0 }
    Set-Status ('آماده — ' + $servers.Count + ' سرور رایگان بارگذاری شد') ([System.Drawing.Color]::FromArgb(150, 158, 175))
}

$form.Add_Shown({
    # auto-connect to the last used server
    if ($combo.SelectedIndex -ge 0) { $btnConnect.PerformClick() }
})

[void]$form.ShowDialog()
Stop-VexCore
