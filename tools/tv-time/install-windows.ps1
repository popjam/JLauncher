# Adds a scheduled task that runs run-windows.ps1 every minute while you're signed in, in a hidden
# window. Pass the addresses if they change: .\install-windows.ps1 -Tv 192.168.4.20 -Frame 192.168.4.33
# (-Frame only with a Samsung The Frame; run setup-frame.sh in WSL first).
# Remove it with: Unregister-ScheduledTask -TaskName "JLauncher TV time" -Confirm:$false
param([string]$Tv = "192.168.4.20", [string]$Frame = "")
$script = Join-Path $PSScriptRoot "run-windows.ps1"
$frameArg = if ($Frame) { " -Frame $Frame" } else { "" }
$action = New-ScheduledTaskAction -Execute "conhost.exe" `
    -Argument "--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$script`" -Tv $Tv$frameArg"
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "JLauncher TV time" -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName "JLauncher TV time"
"Installed. Checks the TV at $Tv$(if ($Frame) { " and the Frame at $Frame" }) every minute and writes to G:\My Drive\JLauncher\JLauncher TV time.json."
