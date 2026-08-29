#!/usr/bin/env bash
# Builds the RealmShark library jar (this branch), feeds it into the Tomato
# front-end worktree as a dependency, then builds Tomato.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOMATO_DIR="$ROOT_DIR/tomato"

# Gradle 7.6.4 (used by both projects) doesn't support the JDK on PATH here
# (JDK 25). Pin JAVA_HOME to a compatible JDK so the build is deterministic
# regardless of which Gradle daemon happens to be reused.
COMPATIBLE_JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
if [ -d "$COMPATIBLE_JDK" ]; then
    export JAVA_HOME="$COMPATIBLE_JDK"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ ! -d "$TOMATO_DIR" ]; then
    echo "error: tomato worktree not found at $TOMATO_DIR" >&2
    exit 1
fi

echo "==> Building RealmShark ($ROOT_DIR)"
(cd "$ROOT_DIR" && ./gradlew shadowJar)

REALMSHARK_JAR="$(ls -t "$ROOT_DIR"/build/libs/RealmShark-*.jar 2>/dev/null | head -n1 || true)"
if [ -z "$REALMSHARK_JAR" ]; then
    echo "error: no RealmShark-*.jar found in $ROOT_DIR/build/libs" >&2
    exit 1
fi
JAR_NAME="$(basename "$REALMSHARK_JAR")"
echo "==> Built $JAR_NAME"

echo "==> Updating tomato/libs with $JAR_NAME"
rm -f "$TOMATO_DIR"/libs/RealmShark-*.jar
cp "$REALMSHARK_JAR" "$TOMATO_DIR/libs/$JAR_NAME"

BUILD_GRADLE="$TOMATO_DIR/build.gradle"
if ! grep -q "libs/RealmShark-.*\.jar" "$BUILD_GRADLE"; then
    echo "error: could not find RealmShark jar dependency line in $BUILD_GRADLE" >&2
    exit 1
fi
sed -i -E "s#libs/RealmShark-[^\"]*\.jar#libs/$JAR_NAME#" "$BUILD_GRADLE"
echo "==> Pointed $BUILD_GRADLE at $JAR_NAME"

echo "==> Building Tomato ($TOMATO_DIR)"
(cd "$TOMATO_DIR" && ./gradlew shadowJar)

echo "==> Done. Tomato jar(s):"
ls -1 "$TOMATO_DIR"/build/libs/
