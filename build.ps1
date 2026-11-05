$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# The source may ship as lean-tv-source.zip, with launcher/ and phone/ inside it.
if ((-not (Test-Path launcher\app\build.gradle)) -or (-not (Test-Path phone\app\build.gradle))) {
    if (Test-Path lean-tv-source.zip) {
        Write-Host "unpacking lean-tv-source.zip"
        Expand-Archive -Path lean-tv-source.zip -DestinationPath . -Force
    } else {
        throw "no launcher/ or phone/ here, and no lean-tv-source.zip to unpack"
    }
}

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
