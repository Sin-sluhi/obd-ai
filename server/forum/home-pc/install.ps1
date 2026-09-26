# Переезд OBD AI на новый компьютер. Запускать с флешки:
#   powershell -ExecutionPolicy Bypass -File D:\obd-ai-move\install.ps1
# Кладёт репозиторий и сервер в папку пользователя (или в -Target), память Claude в профиль Claude,
# ставит автозагрузку форума и говорит, что осталось сделать руками.
param([string]$Target = $env:USERPROFILE)
$ErrorActionPreference = 'Continue'
$src = Split-Path -Parent $MyInvocation.MyCommand.Path

function Copy-Tree($from, $to) {
    if (-not (Test-Path $from)) { Write-Host "  нет папки $from, пропускаю"; return }
    New-Item -ItemType Directory -Force $to | Out-Null
    robocopy $from $to /E /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { Write-Host "  ОШИБКА копирования $from -> $to (код $LASTEXITCODE)" } else { Write-Host "  $from -> $to" }
}

Write-Host "1. Репозиторий и сервер"
Copy-Tree "$src\obd-ai" "$Target\obd-ai"
Copy-Tree "$src\obd-ai-server" "$Target\obd-ai-server"

Write-Host "2. Память Claude Code для этого проекта"
$projKey = ("$Target\obd-ai" -replace '[:\\]', '-')
$memDir = "$env:USERPROFILE\.claude\projects\$projKey\memory"
Copy-Tree "$src\claude-memory" $memDir

Write-Host "3. Автозагрузка форума"
$vbs = "$Target\obd-ai-server\obdai-forum.vbs"
$lines = @(
    "' Запуск форума OBD AI без окна (копия лежит в автозагрузке Windows)",
    'Set sh = CreateObject("WScript.Shell")',
    ('sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""' + "$Target\obd-ai-server\forum.ps1" + '""", 0, False')
)
[IO.File]::WriteAllLines($vbs, $lines, (New-Object Text.UTF8Encoding $false))
$startup = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\obdai-forum.vbs"
Copy-Item $vbs $startup -Force
Write-Host "  $startup"

Write-Host "4. Что есть на этой машине"
$py = Get-Command python -ErrorAction SilentlyContinue
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh -and (Test-Path 'C:\Program Files\GitHub CLI\gh.exe')) { $gh = @{ Source = 'C:\Program Files\GitHub CLI\gh.exe' } }
$ssh = Get-Command ssh -ErrorAction SilentlyContinue
$git = Get-Command git -ErrorAction SilentlyContinue
Write-Host ("  python: " + $(if ($py) { $py.Source } else { 'НЕТ — поставить Python 3.9+ с python.org, галочка Add to PATH' }))
Write-Host ("  git:    " + $(if ($git) { $git.Source } else { 'НЕТ — поставить Git for Windows' }))
Write-Host ("  gh:     " + $(if ($gh) { $gh.Source } else { 'НЕТ — поставить GitHub CLI (winget install GitHub.cli)' }))
Write-Host ("  ssh:    " + $(if ($ssh) { $ssh.Source } else { 'НЕТ — Параметры → Приложения → Дополнительные компоненты → Клиент OpenSSH' }))

Write-Host ""
Write-Host "Осталось руками:"
Write-Host "  1) gh auth login   (токен со старой машины не переносится, это одна команда)"
Write-Host "  2) На старой машине удалить obdai-forum.vbs из автозагрузки, иначе два сервера будут спорить за адрес форума"
Write-Host "  3) Запустить форум сейчас, не ждать перезагрузки:  wscript `"$vbs`""
Write-Host "  4) Через минуту проверить $Target\obd-ai-server\forum.log: должна быть строка «опубликован адрес https://…lhr.life»"
Write-Host "  5) В Claude Code открыть папку $Target\obd-ai — CLAUDE.md и память подхватятся сами"
