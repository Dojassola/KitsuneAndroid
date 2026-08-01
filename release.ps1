param(
    [switch]$Commit,
    [string]$Message = ""
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $androidStudioJbr = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    if (Test-Path $androidStudioJbr) { $env:JAVA_HOME = $androidStudioJbr }
}

$gradleFile = Get-Content "app\build.gradle.kts" -Raw
$versionMatch = [regex]::Match($gradleFile, 'appVersionName\s*=\s*"(?<version>\d+\.\d+\.\d+)"')
if (-not $versionMatch.Success) { throw "Nao foi possivel ler appVersionName em app/build.gradle.kts." }

$version = $versionMatch.Groups["version"].Value
$tag = "v$version"
$status = @(git status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw "Falha ao consultar o Git." }

$sensitive = @($status | Where-Object {
    $_ -match '(?i)(local\.properties|keystore\.properties|\.env($|\.)|\.jks$|\.keystore$|google-services\.json$)'
})
if ($sensitive.Count -gt 0) { throw "Release cancelada: arquivo sensivel visivel no Git: $($sensitive -join ', ')" }

Write-Host "Compilando Kitsune $version..."
& .\gradlew.bat testDebugUnitTest lintDebug assembleRelease
if ($LASTEXITCODE -ne 0) { throw "A compilacao falhou." }

$status = @(git status --porcelain --untracked-files=all)
if ($Commit -and $status.Count -gt 0) {
    git add --all
    if ($LASTEXITCODE -ne 0) { throw "Falha ao preparar o commit." }
    $commitMessage = if ($Message.Trim()) { $Message.Trim() } else { "release: $tag" }
    git commit -m $commitMessage
    if ($LASTEXITCODE -ne 0) { throw "Falha ao criar o commit." }
} elseif ($status.Count -gt 0) {
    throw "Existem alteracoes sem commit. Rode novamente com -Commit ou faca o commit pelo Android Studio."
}

git fetch origin --tags --quiet
if ($LASTEXITCODE -ne 0) { throw "Falha ao atualizar as tags do GitHub." }
if (git tag --list $tag) { throw "A tag $tag ja existe. Atualize appVersionName e appVersionCode." }

git tag -a $tag -m "Kitsune $version"
if ($LASTEXITCODE -ne 0) { throw "Falha ao criar a tag $tag." }
git push origin HEAD
if ($LASTEXITCODE -ne 0) { throw "Falha ao enviar o commit." }
git push origin $tag
if ($LASTEXITCODE -ne 0) { throw "Falha ao enviar a tag." }

Write-Host "Release $tag enviada. O GitHub Actions vai publicar o APK."
