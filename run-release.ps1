param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $androidStudioJbr = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    if (Test-Path -LiteralPath $androidStudioJbr) {
        $env:JAVA_HOME = $androidStudioJbr
    }
}

if (-not (Test-Path -LiteralPath ".\keystore.properties") -and -not $env:KEYSTORE_PATH) {
    throw "Configure keystore.properties ou KEYSTORE_PATH para assinar a build release."
}

$sdkRoots = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk")
) | Where-Object { $_ }
$adb = $sdkRoots |
    ForEach-Object { Join-Path $_ "platform-tools\adb.exe" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

if (-not $adb) {
    throw "adb.exe nao encontrado. Configure ANDROID_HOME ou ANDROID_SDK_ROOT."
}

$adbTarget = @()
if ($Serial) {
    $adbTarget = @("-s", $Serial)
}

if ($Serial) {
    $deviceState = (& $adb @adbTarget get-state).Trim()
    if ($LASTEXITCODE -ne 0 -or $deviceState -ne "device") {
        throw "O aparelho $Serial nao esta conectado ou autorizado."
    }
} else {
    $connectedDevices = @(& $adb devices) | Where-Object { $_ -match "\tdevice$" }
    if ($connectedDevices.Count -ne 1) {
        throw "Conecte um aparelho autorizado ou informe -Serial quando houver mais de um."
    }
}

& .\gradlew.bat testDebugUnitTest assembleRelease
if ($LASTEXITCODE -ne 0) {
    throw "A build release falhou."
}

$deviceAbi = (& $adb @adbTarget shell getprop ro.product.cpu.abi).Trim()
$releaseDirectory = ".\app\build\outputs\apk\release"
$apkPath = @(
    (Join-Path $releaseDirectory "app-$deviceAbi-release.apk"),
    (Join-Path $releaseDirectory "app-universal-release.apk"),
    (Join-Path $releaseDirectory "app-release.apk")
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $apkPath) {
    throw "Nenhum APK release compatível foi encontrado."
}
$apk = Resolve-Path $apkPath
& $adb @adbTarget install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "A atualizacao falhou. O app instalado precisa ter a mesma assinatura release."
}

& $adb @adbTarget shell am start -n "com.kitsuneandroid/.MainActivity"
if ($LASTEXITCODE -ne 0) {
    throw "O APK foi instalado, mas o app nao abriu."
}

Write-Host "Kitsune release atualizado sem apagar os dados e aberto no aparelho."
