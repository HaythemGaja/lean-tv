$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "== Lean TV launcher (TV) =="
Push-Location launcher
.\gradlew.bat --no-daemon assembleRelease
Pop-Location

Write-Host "== Lean TV Remote (phone) =="
Push-Location phone
.\gradlew.bat --no-daemon assembleRelease
Pop-Location

Write-Host "`n== APKs =="
Get-ChildItem launcher\app\build\outputs\apk\release\app-release.apk,
              phone\app\build\outputs\apk\release\app-release.apk |
    Select-Object @{n = "Size"; e = { "$($_.Length) B" } }, @{n = "Path"; e = { $_.FullName } }
