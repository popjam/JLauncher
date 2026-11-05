# JLauncher Vibecoding on Windows: reads this PC's sessions with vibecoding.py in WSL (Windows has no
# Python), then copies the day totals to Google Drive (which WSL can't see). Run every 15 minutes by
# the scheduled task install-windows.ps1 adds.
param(
    [string]$Drive = "G:\My Drive\JLauncher"
)
$ErrorActionPreference = "Stop"

$local = Join-Path $env:LOCALAPPDATA "JLauncher"
New-Item -ItemType Directory -Force $local | Out-Null
$out = Join-Path $local "JLauncher Vibecoding PC.json"

function ToWsl([string]$path) {
    $drive = $path.Substring(0, 1).ToLower()
    "/mnt/$drive" + $path.Substring(2).Replace("\", "/")
}

wsl.exe -e python3 /home/james/JLauncher/tools/vibecoding/vibecoding.py --device PC `
    --out (ToWsl $out) `
    --claude (ToWsl (Join-Path $env:USERPROFILE ".claude\projects")) `
    --codex (ToWsl (Join-Path $env:USERPROFILE ".codex\sessions")) `
    --rates (ToWsl (Join-Path $env:USERPROFILE ".t3\userdata\usage-model-rates.json"))
if ($LASTEXITCODE -ne 0) {
    # The scheduled task runs this through conhost, which hides the exit code, so leave a note
    "$(Get-Date -Format s) vibecoding.py failed with exit code $LASTEXITCODE" | Out-File -Append -Encoding utf8 (Join-Path $local "vibecoding-errors.log")
    exit $LASTEXITCODE
}

# Only when it changed, so Drive isn't syncing the same file every 15 minutes
$target = Join-Path $Drive "JLauncher Vibecoding PC.json"
$same = (Test-Path $target) -and ((Get-FileHash $target).Hash -eq (Get-FileHash $out).Hash)
if (-not $same) { Copy-Item $out $target -Force }
