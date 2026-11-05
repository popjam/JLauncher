# Adds a scheduled task that runs run-windows.ps1 every 15 minutes while you're signed in, in a
# hidden window. Remove it with: Unregister-ScheduledTask -TaskName "JLauncher Vibecoding" -Confirm:$false
$script = Join-Path $PSScriptRoot "run-windows.ps1"
$action = New-ScheduledTaskAction -Execute "conhost.exe" `
    -Argument "--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$script`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 15)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 5) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "JLauncher Vibecoding" -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName "JLauncher Vibecoding"
"Installed. Writes to G:\My Drive\JLauncher\JLauncher Vibecoding PC.json every 15 minutes."
