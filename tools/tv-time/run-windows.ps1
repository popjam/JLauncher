# JLauncher TV time on Windows: checks the TV with tv-time.py in WSL (where adb is), then copies the
# day totals to Google Drive (which WSL can't see) when they change. Run every minute by the
# scheduled task install-windows.ps1 adds.
param(
    [string]$Tv = "192.168.4.20",
    [string]$Drive = "G:\My Drive\JLauncher"
)
$local = Join-Path $env:LOCALAPPDATA "JLauncher"
New-Item -ItemType Directory -Force $local | Out-Null
$out = Join-Path $local "JLauncher TV time.json"
$wslOut = "/mnt/" + $out.Substring(0, 1).ToLower() + $out.Substring(2).Replace("\", "/")

wsl.exe -e python3 /home/james/JLauncher/tools/tv-time/tv-time.py --tv $Tv --out $wslOut

if (Test-Path $out) {
    $target = Join-Path $Drive "JLauncher TV time.json"
    $same = (Test-Path $target) -and ((Get-FileHash $target).Hash -eq (Get-FileHash $out).Hash)
    if (-not $same) { Copy-Item $out $target -Force }
}
