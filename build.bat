@echo off
REM 前置条件：
REM   1) 已安装 JDK 17，且 JAVA_HOME 指向它
REM   2) 已安装 Gradle 8.2，且 gradle 在 PATH 中
REM      （或在本目录先执行：gradle wrapper --gradle-version 8.2，然后改用 gradlew.bat）
REM   3) 已安装 Android SDK，且 ANDROID_HOME 指向它（含 platform-34 / build-tools;34.0.0）
setlocal

if "%JAVA_HOME%"=="" (
  echo [错误] 未设置 JAVA_HOME，请指向 JDK 17 安装目录。
  exit /b 1
)
if "%ANDROID_HOME%"=="" (
  echo [错误] 未设置 ANDROID_HOME，请指向 Android SDK 目录。
  exit /b 1
)

where gradle >nul 2>nul
if errorlevel 1 (
  echo [错误] 未在 PATH 中找到 gradle，请先安装 Gradle 或用 gradlew.bat。
  exit /b 1
)

echo 开始构建 debug APK ...
gradle :app:assembleDebug

if exist app\build\outputs\apk\debug\app-debug.apk (
  echo.
  echo 构建成功！APK 位于：
  echo   app\build\outputs\apk\debug\app-debug.apk
) else (
  echo.
  echo 构建失败，请检查上面的错误输出。
)
endlocal
