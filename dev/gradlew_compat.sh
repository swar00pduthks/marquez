#!/bin/sh
# Cross-platform Gradle wrapper launcher.
# Works in Git Bash, WSL bash, and PowerShell (via sh.exe on PATH).
# Usage: dev/gradlew_compat.sh <gradle-tasks...>

if [ -f "./gradlew" ]; then
    exec ./gradlew "$@"
elif [ -f "./gradlew.bat" ]; then
    exec cmd /c gradlew.bat "$@"
else
    echo "ERROR: No gradlew or gradlew.bat found in current directory." >&2
    exit 1
fi
