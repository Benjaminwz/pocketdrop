# Build PocketDrop.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Needs (see README): JDK 17 and an Android SDK with "build-tools;35.0.0" and "platforms;android-35".
#   -Tools folder layout:  <Tools>\jdk-17*\   and   <Tools>\sdk\build-tools\35.0.0, <Tools>\sdk\platforms\android-35
#   or set JAVA_HOME / ANDROID_HOME yourself.
#
# Signing: the first build creates android\release.keystore and a random password in
# android\signing.txt. Both are git-ignored. Keep them: phones only accept updates signed with the same key.
#
# Usage:  powershell -ExecutionPolicy Bypass -File build_apk.ps1 [-VersionCode 2 -VersionName 1.1]
param(
    [int]$VersionCode = 1,
    [string]$VersionName = "1.0",
    [string]$Tools = (Join-Path $HOME "AndroidBuild")
)
$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    $env:JAVA_HOME = (Get-ChildItem "$Tools\jdk-*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}
if (-not $env:JAVA_HOME) { throw "JDK 17 not found: set JAVA_HOME or put it in $Tools\jdk-17..." }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$Tools\sdk" }
$BT = "$Sdk\build-tools\35.0.0"
$Jar = "$Sdk\platforms\android-35\android.jar"
if (-not (Test-Path $Jar)) { throw "Android SDK not found: $Jar" }

$Root = $PSScriptRoot
$A = Join-Path $Root "android"
$Out = Join-Path $A "build"
if (Test-Path $Out) { Get-ChildItem $Out | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force "$Out\gen", "$Out\classes", "$Out\dex" | Out-Null

function Run($exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "failed: $exe" }
}

Write-Host "1/6 resources"
Run "$BT\aapt2.exe" @("compile", "--dir", "$A\res", "-o", "$Out\res.zip")
Run "$BT\aapt2.exe" @("link", "-I", $Jar, "--manifest", "$A\AndroidManifest.xml", "--java", "$Out\gen",
    "--min-sdk-version", "29", "--target-sdk-version", "34",
    "--version-code", "$VersionCode", "--version-name", $VersionName,
    "-o", "$Out\unsigned.apk", "$Out\res.zip")

Write-Host "2/6 javac"
$src = Get-ChildItem "$A\src", "$Out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Run "javac" (@("-nowarn", "-Xlint:-options", "-encoding", "UTF-8", "--release", "8", "-classpath", $Jar, "-d", "$Out\classes") + $src)

Write-Host "3/6 d8"
$classes = Get-ChildItem "$Out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Run "$BT\d8.bat" (@("--release", "--min-api", "29", "--lib", $Jar, "--output", "$Out\dex") + $classes)

Write-Host "4/6 add classes.dex"
python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" "$Out\unsigned.apk" "$Out\dex\classes.dex"
if ($LASTEXITCODE -ne 0) { throw "zip failed" }

Write-Host "5/6 zipalign"
Run "$BT\zipalign.exe" @("-p", "-f", "4", "$Out\unsigned.apk", "$Out\aligned.apk")

Write-Host "6/6 sign"
$ks = Join-Path $A "release.keystore"
$pwFile = Join-Path $A "signing.txt"
if (-not (Test-Path $ks)) {
    $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content -Path $pwFile -Value $pw -Encoding ascii
    Run "keytool" @("-genkeypair", "-keystore", $ks, "-alias", "pocketdrop", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "36500", "-storepass", $pw, "-keypass", $pw, "-dname", "CN=PocketDrop")
}
$pw = (Get-Content $pwFile -Raw).Trim()
$apk = Join-Path $Root "PocketDrop.apk"
Run "$BT\apksigner.bat" @("sign", "--ks", $ks, "--ks-pass", "pass:$pw", "--key-pass", "pass:$pw", "--out", $apk, "$Out\aligned.apk")
Copy-Item $apk (Join-Path $Root "pc\PocketDrop.apk") -Force
Write-Host ("OK: {0} ({1:N0} KB)" -f $apk, ((Get-Item $apk).Length / 1KB))
