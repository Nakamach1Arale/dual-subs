@echo off
rem Clean build of the APK (always from scratch, so no stale files linger in it).
rem   build.cmd           -> app\build\outputs\apk\debug\app-debug.apk
rem   build.cmd release   -> app\build\outputs\apk\release\app-release.apk (not debuggable; for publishing)
rem Uses JAVA_HOME / ANDROID_HOME if already set, otherwise the toolchain installed on this PC.
setlocal
cd /d "%~dp0"

set "VARIANT=Debug"
set "OUT=debug\app-debug.apk"
if /i "%~1"=="release" (
  set "VARIANT=Release"
  set "OUT=release\app-release.apk"
)

if not defined JAVA_HOME set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not defined ANDROID_HOME set "ANDROID_HOME=E:\ClaudeBuilds\AndroidSdk"
if not exist local.properties (
  echo sdk.dir=%ANDROID_HOME:\=/%> local.properties
)

set "LOCAL_GRADLE=%USERPROFILE%\.gradle-dist\gradle-8.11.1\bin\gradle.bat"
if exist "%LOCAL_GRADLE%" (
  rem Create the Gradle wrapper (gradlew) for people who clone the repo, if it is missing.
  if not exist gradlew.bat call "%LOCAL_GRADLE%" wrapper --gradle-version 8.11.1 --no-daemon -q
  call "%LOCAL_GRADLE%" clean assemble%VARIANT% --no-daemon
) else (
  call gradlew.bat clean assemble%VARIANT%
)
if errorlevel 1 exit /b 1
echo.
echo APK: %~dp0app\build\outputs\apk\%OUT%
