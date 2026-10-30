#!/bin/sh
# Gradle wrapper stub - downloads and runs Gradle 8.9
GRADLE_VERSION="8.9"
GRADLE_HOME="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_ZIP_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

if [ ! -d "$GRADLE_HOME/gradle-${GRADLE_VERSION}" ]; then
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  mkdir -p "$GRADLE_HOME"
  curl -sL "$GRADLE_ZIP_URL" -o "$GRADLE_HOME/gradle.zip"
  unzip -q "$GRADLE_HOME/gradle.zip" -d "$GRADLE_HOME"
  rm "$GRADLE_HOME/gradle.zip"
fi
exec "$GRADLE_HOME/gradle-${GRADLE_VERSION}/bin/gradle" "$@"
