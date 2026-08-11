@echo off
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not "%~1"=="" set "VERSION_NAME=%~1"
if not "%~2"=="" set "VERSION_CODE=%~2"

set "SIGN_COUNT=0"
if defined KEYSTORE_PATH set /a SIGN_COUNT+=1
if defined KEYSTORE_PASSWORD set /a SIGN_COUNT+=1
if defined KEY_ALIAS set /a SIGN_COUNT+=1
if defined KEY_PASSWORD set /a SIGN_COUNT+=1
if not "%SIGN_COUNT%"=="0" if not "%SIGN_COUNT%"=="4" goto :missing_signing

set "SIGNED=0"
if "%SIGN_COUNT%"=="4" (
    set "SIGNED=1"
    for %%I in ("%KEYSTORE_PATH%") do set "KEYSTORE_PATH=%%~fI"
    if not exist "%KEYSTORE_PATH%" (
        echo ERRO: Keystore nao encontrado: %KEYSTORE_PATH%
        exit /b 1
    )
) else if exist "%CD%\keystore.properties" set "SIGNED=1"

call gradlew.bat assembleRelease
if errorlevel 1 exit /b %errorlevel%

if "%SIGNED%"=="1" (
    set "APK=%CD%\app\build\outputs\apk\release\app-universal-release.apk"
) else (
    set "APK=%CD%\app\build\outputs\apk\release\app-universal-release-unsigned.apk"
)

if not exist "%APK%" (
    echo ERRO: APK nao encontrado em %APK%
    exit /b 1
)

if "%SIGNED%"=="1" (echo APK release assinado gerado em:) else (echo APK release nao assinado gerado em:)
echo %APK%
exit /b 0

:missing_signing
echo ERRO: Para assinar, defina KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS e KEY_PASSWORD.
exit /b 1
