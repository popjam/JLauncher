# Adds a scheduled task that runs run-windows.ps1 every minute while you're signed in, in a hidden
# window. Pass the TV's address if it changes: .\install-windows.ps1 -Tv 192.168.4.20
# Remove it with: Unregister-ScheduledTask -TaskName "JLauncher TV time" -Confirm:$false
param([string]$Tv = "192.168.4.20")
$script = Join-Path $PSScriptRoot "run-windows.ps1"
$action = New-ScheduledTaskAction -Execute "conhost.exe" `
    -Argument "--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$script`" -Tv $Tv"
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "JLauncher TV time" -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName "JLauncher TV time"
"Installed. Checks the TV at $Tv every minute and writes to G:\My Drive\JLauncher\JLauncher TV time.json."
