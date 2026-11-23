# JLauncher TV time on Windows: checks the TV with tv-time.py in WSL (where adb is), then copies the
# day totals to Google Drive (which WSL can't see) when they change. Run every minute by the
# scheduled task install-windows.ps1 adds. With -Frame, the Android TV box is plugged into a Samsung
# The Frame at that address, which says whether the screen is really on, and lockdown can close its apps.
param(
    [string]$Tv = "192.168.4.20",
    [string]$Frame = "",
    [string]$Drive = "G:\My Drive\JLauncher"
)
$local = Join-Path $env:LOCALAPPDATA "JLauncher"
New-Item -ItemType Directory -Force $local | Out-Null
$out = Join-Path $local "JLauncher TV time.json"
$wslOut = "/mnt/" + $out.Substring(0, 1).ToLower() + $out.Substring(2).Replace("\", "/")

# The Frame's library lives in its own environment (setup-frame.sh); plain python3 without it
$python = "/home/james/.local/share/jlauncher-tv/venv/bin/python"
$frameArgs = @()
if ($Frame) { $frameArgs = @("--frame", $Frame) }
wsl.exe -e sh -c "test -x $python || exit 3"
if ($LASTEXITCODE -eq 3) { $python = "python3" }
wsl.exe -e $python /home/james/JLauncher/tools/tv-time/tv-time.py --tv $Tv @frameArgs --out $wslOut

if (Test-Path $out) {
    $target = Join-Path $Drive "JLauncher TV time.json"
    $same = (Test-Path $target) -and ((Get-FileHash $target).Hash -eq (Get-FileHash $out).Hash)
    if (-not $same) { Copy-Item $out $target -Force }
}
