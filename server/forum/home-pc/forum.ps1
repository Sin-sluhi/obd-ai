# Форум OBD AI на домашнем ПК: локальный сервер + постоянный адрес (Tailscale Funnel, public.url) и запасные туннели serveo.net / localhost.run
# + публикация текущего адреса в репозиторий (docs/forum_url.txt), откуда его берёт приложение и сайт.
# + APK для выдачи по заявкам с сайта: раз в 10 минут забирает последнюю сборку (Update-Apk, нужен apk.pass).
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
# Токен GitHub для gh: файл gh.token рядом со скриптом. Вход «gh auth login», сделанный внутри приложения из
# Microsoft Store (например, из сессии Claude), хранится в его изолированной папке и при автозапуске не виден —
# тогда gh молча не публикует адрес и не скачивает сборки.
if (Test-Path "$dir\gh.token") { $env:GH_TOKEN = (Get-Content "$dir\gh.token" -Raw).Trim() }

function Log($m) { Add-Content -Path $log -Value ("{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m) -Encoding utf8 }

function Ensure-Server {
    try { $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 3; if ($r.ok) { return } } catch {}
    Log "запускаю сервер"
    Start-Process -FilePath 'python' -ArgumentList @("`"$repo\server\forum\server.py`"", '--host', '127.0.0.1', '--port', "$port", '--db', "`"$dir\forum.db`"") -WindowStyle Hidden -RedirectStandardOutput "$dir\server.out" -RedirectStandardError "$dir\server.err" | Out-Null
    Start-Sleep -Seconds 2
}

function Publish-Url($url) {
    # docs/forum_url.txt в GitHub: приложение читает его при запуске и при входе в чат
    if ($script:published -eq $url) { return }
    try {
        $old = ''
        $sha = $null
        $cur = & $gh api repos/Sin-sluhi/obd-ai/contents/docs/forum_url.txt 2>$null | ConvertFrom-Json
        if ($cur -and $cur.content) { $old = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($cur.content)).Trim(); $sha = $cur.sha }
        if ($old -eq $url) { Log "адрес не изменился: $url"; $script:published = $url; return }
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($url + "`n"))
        $args = @('api', '-X', 'PUT', 'repos/Sin-sluhi/obd-ai/contents/docs/forum_url.txt', '-f', 'message=forum: адрес туннеля', '-f', "content=$b64")
        if ($sha) { $args += @('-f', "sha=$sha") }
        $res = & $gh @args 2>&1
        if ($LASTEXITCODE -ne 0) { Log "не удалось опубликовать адрес: $(($res | Out-String).Trim() -replace '\s+', ' ')"; return }
        $script:published = $url
        Log "опубликован адрес $url"
    } catch { Log "не удалось опубликовать адрес: $_" }
}

function Start-Tunnel($kind) {
    # serveo.net держится дольше localhost.run; оба без аккаунта, адрес случайный — публикуем его в docs/forum_url.txt.
    # Сайт обходит страницу-предупреждение serveo: она показывается только запросам с Accept: text/html, а сайт и приложение
    # ходят в API скриптом.
    $out = "$dir\tunnel.out"
    Remove-Item $out -ErrorAction SilentlyContinue
    $common = @('-T', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ServerAliveInterval=20', '-o', 'ServerAliveCountMax=3', '-o', 'ExitOnForwardFailure=yes')
    if ($kind -eq 'serveo') {
        $sshArgs = $common + @('-R', "80:127.0.0.1:$port", 'serveo.net')
        $pattern = 'https://[a-z0-9-]+\.serveousercontent\.com'
    } else {
        $sshArgs = $common + @('-R', "80:127.0.0.1:$port", 'nokey@localhost.run')
        $pattern = 'https://[a-z0-9]+\.lhr\.life'
    }
    $p = Start-Process -FilePath $ssh -ArgumentList $sshArgs -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError "$dir\tunnel.err" -PassThru
    $url = $null
    for ($i = 0; $i -lt 30 -and -not $url -and -not $p.HasExited; $i++) {
        Start-Sleep -Seconds 2
        if (Test-Path $out) {
            $m = Select-String -Path $out -Pattern $pattern -AllMatches | Select-Object -First 1
            if ($m) { $url = $m.Matches[0].Value }
        }
    }
    return @{ proc = $p; url = $url; kind = $kind }
}

function Update-Apk {
    # APK для выдачи по заявкам с сайта: последняя удачная сборка build.yml, артефакт «apk» зашифрован паролем из apk.pass
    $apkDir = "$dir\apk"
    $passFile = "$dir\apk.pass"
    if (-not (Test-Path $passFile)) { return }
    try {
        $raw = & $gh run list -R Sin-sluhi/obd-ai --workflow build.yml --status success -L 1 --json databaseId 2>&1
        $runs = $null
        try { $runs = ($raw | Where-Object { $_ -is [string] }) -join '' | ConvertFrom-Json } catch {}
        if (-not $runs) { Log "APK: gh не вернул сборку: $(($raw | Out-String).Trim() -replace '\s+', ' ')"; return }
        $id = "$($runs[0].databaseId)"
        $mark = "$apkDir\run.txt"
        if ((Test-Path "$apkDir\app-debug.apk") -and (Test-Path $mark) -and ((Get-Content $mark -Raw).Trim() -eq $id)) { return }
        $tmp = "$dir\apk.tmp"
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path $tmp) { Log "APK: не удалось очистить $tmp — скачивание поверх старых файлов не пройдёт"; return }
        & $gh run download $id -R Sin-sluhi/obd-ai -n apk -D $tmp 2>&1 | Out-Null
        if (-not (Test-Path "$tmp\app.apk.enc")) { Log "APK: в сборке $id нет зашифрованного артефакта"; Set-Content -Path $mark -Value $id; return }
        $openssl = (Get-Command openssl.exe -ErrorAction SilentlyContinue).Source
        if (-not $openssl) { $openssl = 'C:\Program Files\Git\usr\bin\openssl.exe' }
        $env:OBDAI_APK_PASS = (Get-Content $passFile -Raw).Trim()
        & $openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in "$tmp\app.apk.enc" -out "$tmp\app-debug.apk" -pass env:OBDAI_APK_PASS 2>&1 | Out-Null
        Remove-Item Env:\OBDAI_APK_PASS -ErrorAction SilentlyContinue
        if (-not (Test-Path "$tmp\app-debug.apk") -or (Get-Item "$tmp\app-debug.apk").Length -lt 100000) { Log "APK: не расшифровался (пароль в apk.pass не совпадает с секретом APK_PASS?)"; return }
        New-Item -ItemType Directory -Force $apkDir | Out-Null
        # пока кто-то качает, Windows не даст заменить файл — тогда попробуем в следующий раз
        Move-Item "$tmp\app-debug.apk" "$apkDir\app-debug.apk" -Force -ErrorAction Stop
        if (Test-Path "$tmp\version.txt") { Copy-Item "$tmp\version.txt" "$apkDir\version.txt" -Force }
        # пароль этой сборки для сайта (APK под ним лежит на Pages в dl/app-<run>.bin) — сервер выдаёт его одобренной заявке
        if (Test-Path "$tmp\pass.enc") {
            $env:OBDAI_APK_PASS = (Get-Content $passFile -Raw).Trim()
            & $openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in "$tmp\pass.enc" -out "$apkDir\build.pass" -pass env:OBDAI_APK_PASS 2>&1 | Out-Null
            Remove-Item Env:\OBDAI_APK_PASS -ErrorAction SilentlyContinue
            Copy-Item "$tmp\meta.json" "$apkDir\meta.json" -Force
        }
        # версия разработчика (кнопка «Версия разработчика» в админке) — тем же паролем, только для владельца
        if (Test-Path "$tmp\app-dev.apk.enc") {
            $env:OBDAI_APK_PASS = (Get-Content $passFile -Raw).Trim()
            & $openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in "$tmp\app-dev.apk.enc" -out "$tmp\app-dev.apk" -pass env:OBDAI_APK_PASS 2>&1 | Out-Null
            Remove-Item Env:\OBDAI_APK_PASS -ErrorAction SilentlyContinue
            if ((Test-Path "$tmp\app-dev.apk") -and (Get-Item "$tmp\app-dev.apk").Length -gt 100000) {
                Move-Item "$tmp\app-dev.apk" "$apkDir\app-dev.apk" -Force -ErrorAction SilentlyContinue
            }
        }
        Set-Content -Path $mark -Value $id
        Log "APK обновлён: сборка $id"
    } catch { Log "APK: $($_.Exception.Message)" }
}

# Новая сборка проверяется по часам, а не по числу оборотов цикла: при сбоях туннеля обороты растягиваются
# таймаутами или обрываются, и счётчик до проверки не доходил — сервер часами отдавал старый APK.
$script:lastApk = [datetime]::MinValue
function Maybe-UpdateApk {
    if (((Get-Date) - $script:lastApk).TotalMinutes -ge 10) { $script:lastApk = Get-Date; Update-Apk }
}

# Проверки наружу — напрямую, минуя системный прокси Windows (VPN на 127.0.0.1:10809): когда VPN лежит, прокси рвёт
# соединение, и скрипт считал мёртвым живой Funnel, уходил на туннели и обратно не возвращался — отсюда вечные 502.
[System.Net.WebRequest]::DefaultWebProxy = $null
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

function Test-Url($url) {
    try { $r = Invoke-RestMethod -Uri "$url/api/health" -TimeoutSec 15; return [bool]$r.ok } catch { return $false }
}

function Repair-Funnel {
    # Funnel иногда пропадает (VPN на ПК); повторная публикация пути /obd идемпотентна и другие пути (сайт GPU Check) не трогает
    $ts = 'C:\Program Files\Tailscale\tailscale.exe'
    if (-not (Test-Path $ts)) { return }
    try { & $ts funnel --bg --set-path /obd "http://127.0.0.1:$port" 2>&1 | Out-Null; Log "Funnel: путь /obd переопубликован" } catch { Log "Funnel: $($_.Exception.Message)" }
}

function Start-PublishedTunnel($kind) {
    # поднять туннель, убедиться снаружи, опубликовать; $null — не получилось (следующий оборот попробует другой вид)
    $t = Start-Tunnel $kind
    if (-not $t.url) { Log "туннель ($kind) не поднялся"; try { $t.proc.Kill() } catch {}; return $null }
    Log "туннель ($($t.kind)): $($t.url)"
    $ok = $false
    for ($i = 0; $i -lt 5 -and -not $ok; $i++) { Start-Sleep -Seconds 3; $ok = Test-Url $t.url }
    if (-not $ok) { Log "адрес туннеля снаружи не отвечает"; try { $t.proc.Kill() } catch {}; return $null }
    Publish-Url $t.url
    $t.started = Get-Date
    $t.fails = 0
    return $t
}

Log "старт (репозиторий: $repo)"
if (-not (Test-Path "$repo\server\forum\server.py")) { Log "нет файла $repo\server\forum\server.py — проверьте, где лежит репозиторий"; exit 1 }
Update-Apk

# Постоянный адрес (Tailscale Funnel): файл public.url рядом со скриптом, например https://sh01.tailec27da.ts.net/obd.
# Он всегда главный: пока отвечает — публикуется он. Не отвечает 2 минуты — переопубликовать путь Funnel; 10 минут —
# временно поднять туннель serveo/localhost.run и опубликовать его. Как только постоянный адрес отвечает три раза
# подряд — туннель гасится и публикуется снова постоянный адрес. Раньше дорога была в одну сторону.
$publicFile = "$dir\public.url"
$public = $null
if (Test-Path $publicFile) { $public = (Get-Content $publicFile -Raw).Trim().TrimEnd('/'); Log "постоянный адрес: $public" }

$kind = 'serveo'
$tunnel = $null
$publicFails = 0
$publicOk = 0
while ($true) {
    Ensure-Server
    Maybe-UpdateApk
    if ($public) {
        if (Test-Url $public) {
            $publicOk++
            if ($publicFails -gt 0) { Log "постоянный адрес снова отвечает" }
            $publicFails = 0
            if ($tunnel) {
                if ($publicOk -ge 3) { Log "возвращаюсь на постоянный адрес"; try { $tunnel.proc.Kill() } catch {}; $tunnel = $null; Publish-Url $public }
            } else { Publish-Url $public }
        } else {
            $publicOk = 0
            $publicFails++
            if ($publicFails -eq 1 -or $publicFails % 10 -eq 0) { Log "постоянный адрес не ответил ($publicFails)" }
            if ($publicFails -eq 4) { Repair-Funnel }
            if ($publicFails -eq 20) { Log "постоянный адрес не отвечает 10 минут — временно поднимаю туннель" }
        }
    }
    $needTunnel = (-not $public) -or ($publicFails -ge 20)
    if ($needTunnel -and -not $tunnel) {
        $tunnel = Start-PublishedTunnel $kind
        if (-not $tunnel) { $kind = if ($kind -eq 'serveo') { 'lhr' } else { 'serveo' } }
    }
    if ($tunnel) {
        if ($tunnel.proc.HasExited) {
            Log "туннель завершился (код $($tunnel.proc.ExitCode))"
            if (((Get-Date) - $tunnel.started).TotalSeconds -lt 180) { $kind = if ($kind -eq 'serveo') { 'lhr' } else { 'serveo' }; Log "переключаюсь на $kind" }
            $tunnel = $null
        } elseif (-not (Test-Url $tunnel.url)) {
            $tunnel.fails++
            Log "туннель не ответил снаружи ($($tunnel.fails))"
            if ($tunnel.fails -ge 2) { Log "перезапуск туннеля"; try { $tunnel.proc.Kill() } catch {}; $tunnel = $null }
        } else { $tunnel.fails = 0 }
    }
    Start-Sleep -Seconds 30
}
