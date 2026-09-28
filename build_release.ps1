# Build everything for a release: PocketDrop.apk, dist\PocketDrop.exe (portable) and dist\PocketDrop-Setup-<ver>.exe.
# Usage:  powershell -ExecutionPolicy Bypass -File build_release.ps1 -Version 1.4.0 -VersionCode 4 [-Test]
#   -Test builds the installer as TESTBUILD (no admin, no shortcuts in Start menu, no firewall rule).
# Needs: the Android tools for build_apk.ps1, Python with PyInstaller + requirements.txt, and Inno Setup 6.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [switch]$Test
)
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

# The PC app shows its version and names the phone installer after it, so they must match.
$line = Select-String -Path "$Root\pc\pocketdrop.pyw" -Pattern '^APP_VERSION = "(.+)"' | Select-Object -First 1
if (-not $line -or $line.Matches[0].Groups[1].Value -ne $Version) { throw "APP_VERSION in pc\pocketdrop.pyw is not $Version" }

Write-Host "== APK"
& "$Root\build_apk.ps1" -VersionCode $VersionCode -VersionName $Version
if (-not $?) { throw "APK build failed" }

Write-Host "== PocketDrop.exe"
$pc = "$Root\pc"
$data = @("icon.ico", "icon-48.png", "icon-96.png", "icon-180.png", "web.html", "PocketDrop.apk") | ForEach-Object { "--add-data=$pc\$_;." }
python -m PyInstaller --noconfirm --onefile --windowed --name PocketDrop --icon "$pc\icon.ico" @data `
    --collect-all tkinterdnd2 --hidden-import qrcode --distpath "$Root\dist" --workpath "$Root\build_pyi" --specpath "$Root\build_pyi" "$pc\pocketdrop.pyw"
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed" }

Write-Host "== installer"
$iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw "Inno Setup 6 (ISCC.exe) not found" }
$defs = @("/DAppVersion=$Version")
if ($Test) { $defs += "/DTESTBUILD" }
& $iscc /Q @defs "$Root\installer\pocketdrop.iss"
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }
Get-ChildItem "$Root\dist" -File | ForEach-Object { "{0,-32} {1,8:N0} KB" -f $_.Name, ($_.Length / 1KB) }
