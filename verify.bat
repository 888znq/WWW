@echo off
cls
echo =====================================================
echo    FINAL PARITY CHECK: EXTENSION VS ANDROID APK
echo =====================================================
echo.

set "ASSETS=app\src\main\assets"
set "MAIN_ACTIVITY=app\src\main\java\com\example\urlhud\MainActivity.java"
set "PASS=1"

echo [1] CHECKING CORE EXTENSION FILES...
for %%F in (index.html app.js firebase-app.js firebase-database.js trinity_sync.js) do (
    if exist "%ASSETS%\%%F" (
        echo  [+] FOUND: %%F
    ) else (
        echo  [-] MISSING: %%F
        set "PASS=0"
    )
)
echo.

echo [2] CHECKING OBSOLETE FILES REMOVAL...
for %%F in (bar.html bar.js hud.js urlbar.html) do (
    if exist "%ASSETS%\%%F" (
        echo  [-] FAILED: %%F still exists!
        set "PASS=0"
    ) else (
        echo  [+] CLEAN: %%F is removed.
    )
)
echo.

echo [3] CHECKING NATIVE ANDROID CONFIGURATION...
if exist "%MAIN_ACTIVITY%" (
    findstr /C:"index.html" "%MAIN_ACTIVITY%" >nul
    if errorlevel 1 (
        echo  [-] FAILED: MainActivity is not loading index.html
        set "PASS=0"
    ) else (
        echo  [+] OK: MainActivity loads index.html
    )
    
    findstr /C:"AndroidAPI" "%MAIN_ACTIVITY%" >nul
    if errorlevel 1 (
        echo  [-] FAILED: AndroidAPI JavascriptInterface missing
        set "PASS=0"
    ) else (
        echo  [+] OK: AndroidAPI bridge is registered
    )
) else (
    echo  [-] FAILED: MainActivity.java not found!
    set "PASS=0"
)
echo.

echo =====================================================
if "%PASS%"=="1" (
    echo  RESULT: 100%% PARITY CONFIRMED. READY TO PUSH TO GIT!
) else (
    echo  RESULT: ERRORS DETECTED. PLEASE REVIEW THE LOG ABOVE.
)
echo =====================================================
pause