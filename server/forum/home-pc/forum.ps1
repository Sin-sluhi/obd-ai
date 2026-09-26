# Форум OBD AI на домашнем ПК: локальный сервер + туннель наружу (localhost.run по SSH, без аккаунта, HTTPS)
# + публикация текущего адреса в репозиторий (docs/forum_url.txt), откуда его берёт приложение.
# Запускается при входе в Windows (obdai-forum.vbs в автозагрузке), работает без окна. Лог: forum.log в этой папке.
#
# Пути не зашиты: скрипт живёт в <папка>\obd-ai-server, репозиторий ожидается рядом в <папка>\obd-ai.
# Другое место репозитория можно задать переменной окружения OBDAI_REPO.
$ErrorActionPreference = 'Continue'
$dir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = if ($env:OBDAI_REPO) { $env:OBDAI_REPO } else { Join-Path (Split-Path -Parent $dir) 'obd-ai' }
$gh   = (Get-Command gh.exe -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = 'C:\Program Files\GitHub CLI\gh.exe' }
$ssh  = (Get-Command ssh.exe -ErrorAction SilentlyContinue).Source
if (-not $ssh) { $ssh = 'C:\Windows\System32\OpenSSH\ssh.exe' }
$log  = "$dir\forum.log"
$port = 8080

function Log($m) { Add-Content -Path $log -Value ("{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m) -Encoding utf8 }

function Ensure-Server {
    try { $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 3; if ($r.ok) { return } } catch {}
    Log "запускаю сервер"
    Start-Process -FilePath 'python' -ArgumentList @("`"$repo\server\forum\server.py`"", '--host', '127.0.0.1', '--port', "$port", '--db', "`"$dir\forum.db`"") -WindowStyle Hidden -RedirectStandardOutput "$dir\server.out" -RedirectStandardError "$dir\server.err" | Out-Null
    Start-Sleep -Seconds 2
}

function Publish-Url($url) {
    # docs/forum_url.txt в GitHub: приложение читает его при запуске и при входе в чат
    try {
        $old = ''
        $sha = $null
        $cur = & $gh api repos/Sin-sluhi/obd-ai/contents/docs/forum_url.txt 2>$null | ConvertFrom-Json
        if ($cur -and $cur.content) { $old = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($cur.content)).Trim(); $sha = $cur.sha }
        if ($old -eq $url) { Log "адрес не изменился: $url"; return }
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($url + "`n"))
        $args = @('api', '-X', 'PUT', 'repos/Sin-sluhi/obd-ai/contents/docs/forum_url.txt', '-f', 'message=forum: адрес туннеля', '-f', "content=$b64")
        if ($sha) { $args += @('-f', "sha=$sha") }
        & $gh @args 2>&1 | Out-Null
        Log "опубликован адрес $url"
    } catch { Log "не удалось опубликовать адрес: $_" }
}

function Start-Tunnel {
    # localhost.run: ssh -R даёт https://<случайно>.lhr.life; адрес печатается в баннере
    $out = "$dir\tunnel.out"
    Remove-Item $out -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $ssh -ArgumentList @('-T', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ServerAliveInterval=30', '-o', 'ServerAliveCountMax=3', '-o', 'ExitOnForwardFailure=yes', '-R', "80:127.0.0.1:$port", 'nokey@localhost.run') -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError "$dir\tunnel.err" -PassThru
    $url = $null
    for ($i = 0; $i -lt 45 -and -not $url -and -not $p.HasExited; $i++) {
        Start-Sleep -Seconds 2
        if (Test-Path $out) {
            $m = Select-String -Path $out -Pattern 'https://[a-z0-9]+\.lhr\.life' -AllMatches | Select-Object -First 1
            if ($m) { $url = $m.Matches[0].Value }
        }
    }
    return @{ proc = $p; url = $url }
}

Log "старт (репозиторий: $repo)"
if (-not (Test-Path "$repo\server\forum\server.py")) { Log "нет файла $repo\server\forum\server.py — проверьте, где лежит репозиторий"; exit 1 }
while ($true) {
    Ensure-Server
    $t = Start-Tunnel
    $p = $t.proc
    if ($t.url) {
        Log "туннель: $($t.url)"
        # проверяем снаружи, прежде чем публиковать
        $ok = $false
        for ($i = 0; $i -lt 5 -and -not $ok; $i++) {
            Start-Sleep -Seconds 3
            try { $r = Invoke-RestMethod -Uri "$($t.url)/api/health" -TimeoutSec 15; if ($r.ok) { $ok = $true } } catch {}
        }
        if ($ok) { Publish-Url $t.url } else { Log "адрес снаружи не отвечает, перезапуск"; try { $p.Kill() } catch {} }
    } else {
        Log "туннель не поднялся, перезапуск"
        try { $p.Kill() } catch {}
    }
    # пока туннель жив — следим за сервером и самим туннелем; упал — цикл поднимет новый и опубликует новый адрес
    $fails = 0
    while (-not $p.HasExited) {
        Start-Sleep -Seconds 30
        Ensure-Server
        if ($t.url) {
            try { Invoke-RestMethod -Uri "$($t.url)/api/health" -TimeoutSec 15 | Out-Null; $fails = 0 }
            catch {
                $fails++
                Log "туннель не ответил снаружи ($fails): $($_.Exception.Message)"
                if ($fails -ge 2) { Log "перезапуск туннеля"; try { $p.Kill() } catch {} }
            }
        }
    }
    Log "туннель завершился (код $($p.ExitCode)), через 10 с новый"
    Start-Sleep -Seconds 10
}
