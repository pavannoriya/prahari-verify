@echo off
set GRADLE_VERSION=8.9
set GRADLE_HOME=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin
if not exist "%GRADLE_HOME%\gradle-%GRADLE_VERSION%" (
  echo Downloading Gradle %GRADLE_VERSION%...
  mkdir "%GRADLE_HOME%" 2>nul
  powershell -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%GRADLE_HOME%\gradle.zip'"
  powershell -Command "Expand-Archive -Path '%GRADLE_HOME%\gradle.zip' -DestinationPath '%GRADLE_HOME%' -Force"
  del "%GRADLE_HOME%\gradle.zip"
)
"%GRADLE_HOME%\gradle-%GRADLE_VERSION%\bin\gradle.bat" %*
