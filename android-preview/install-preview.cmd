@echo off
setlocal
cd /d "%~dp0"
set "ADB=%~dp0..\.android-tools\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb.exe"
if not exist "earclean-preview.apk" (
    echo earclean-preview.apk was not found.
    pause
    exit /b 1
)
"%ADB%" -d install -r earclean-preview.apk
set "INSTALL_STATUS=%ERRORLEVEL%"
if not "%INSTALL_STATUS%"=="0" echo Check the data cable, unlock the phone, and accept its USB debugging prompt.
pause
exit /b %INSTALL_STATUS%
