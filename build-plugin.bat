@echo off
setlocal

set "PROJECT_DIR=%~dp0"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JDK 17 not found: %JAVA_HOME%
    echo Update JAVA_HOME in %~nx0 or install JDK 17.
    echo.
    pause
    exit /b 1
)

cd /d "%PROJECT_DIR%"
call "%PROJECT_DIR%gradlew.bat" buildPlugin
set "EXIT_CODE=%ERRORLEVEL%"

if "%EXIT_CODE%"=="0" (
    echo.
    echo Build artifact:
    dir /b "%PROJECT_DIR%build\distributions\*.zip" 2>nul
)

echo.
pause
exit /b %EXIT_CODE%
