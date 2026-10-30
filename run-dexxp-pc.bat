@echo off
chcp 65001 >nul
title DexXP - Full PC Desktop Mode
color 0b
echo ================================================================
echo                   DexXP - Full PC Desktop Mode
echo ================================================================
echo.

set SCRCPY="C:\Users\Admin\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v5.0.1\scrcpy.exe"
set ADB="C:\Users\Admin\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v5.0.1\adb.exe"

echo [1/3] Detecting Android device connection...
%ADB% devices | findstr /R "device$" >nul
if %ERRORLEVEL% EQU 0 (
    echo [OK] Connected via USB.
    goto launch_virtual
)

echo [INFO] No USB device detected. Checking Wi-Fi at 192.168.10.54:5555...
%ADB% connect 192.168.10.54:5555 >nul 2>&1
%ADB% devices | findstr /R "device$" >nul
if %ERRORLEVEL% EQU 0 (
    echo [OK] Connected via Wi-Fi (192.168.10.54:5555).
    goto launch_virtual
)

echo [NOTICE] Device not detected over ADB yet. Launching scrcpy to auto-connect...
goto launch_virtual

:launch_virtual
echo.
echo [2/3] Starting DexXP Full Desktop Window (1920x1080 16:9)...
echo       (Creating clean virtual display - zero phone status bars or buttons!)
echo.
%SCRCPY% --new-display=1920x1080/180 --keyboard=uhid --stay-awake --window-title="DexXP - PC Desktop"
if %ERRORLEVEL% EQU 0 goto done

echo.
echo [FALLBACK] Virtual display closed or unsupported.
echo           Launching Standalone Immersive Desktop Shell...
%ADB% shell am start -n com.dexxp.desktop/.StandaloneDesktopActivity >nul 2>&1
%SCRCPY% --fullscreen --keyboard=uhid --stay-awake --window-title="DexXP - PC Desktop (Fullscreen)"

:done
echo.
echo DexXP PC Desktop session ended.
