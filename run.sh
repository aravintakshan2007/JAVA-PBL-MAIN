#!/bin/bash
set -euo pipefail

JAVA_BIN="${JAVA_BIN:-java}"
JAVAC_BIN="${JAVAC_BIN:-javac}"

download_if_missing() {
    local file="$1"
    local url="$2"
    if [ ! -f "$file" ]; then
        echo "Downloading $file..."
        curl -fLsS "$url" -o "$file"
    fi
}

download_if_missing "sqlite-jdbc.jar" "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar"
download_if_missing "slf4j-api.jar" "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.12/slf4j-api-2.0.12.jar"
download_if_missing "slf4j-nop.jar" "https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.12/slf4j-nop-2.0.12.jar"

CP=".:sqlite-jdbc.jar:slf4j-api.jar:slf4j-nop.jar"

echo "Compiling Cashpoint.java..."
"$JAVAC_BIN" -cp "$CP" Cashpoint.java

if [ $? -eq 0 ]; then
    echo "Starting Cashpoint ATM Management System..."
    "$JAVA_BIN" -cp "$CP" Cashpoint
else
    echo "Compilation failed!"
    exit 1
fi
