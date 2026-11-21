#!/usr/bin/env bash
# Runs the release R8 configuration over the app + scripts/r8probe/R8Probe.java in class-file mode,
# then executes the shrunk code on the JVM against a real sshd and a fake Proxmox API.
# Needs: an assembleRelease build (for the merged R8 config), sshd set up as described in
# SshIntegrationTest, openssl and python3.  Usage: scripts/r8-probe.sh <sshd-dir> [ssh-port]
# Commands assume the repository root is the cwd.
set -euo pipefail
SSHD_DIR=$1; SSH_PORT=${2:-2222}
WORK=$(mktemp -d)
trap 'kill $PVE_PID 2>/dev/null || true' EXIT
CP=$(./gradlew -q --no-daemon :app:printReleaseClasspath 2>/dev/null | sed -n 's/^CP=//p')
R8=$(find ~/.gradle/caches/modules-2 -name 'builder-*.jar' -path '*com.android.tools.build/builder*' | sort | tail -1)
SDK=${ANDROID_HOME:-/opt/android-sdk}
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
APP_CLASSES=app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes

mkdir -p "$WORK/probe"
javac -nowarn -d "$WORK/probe" -cp "$APP_CLASSES:$CP" scripts/r8probe/R8Probe.java
(cd "$WORK/probe" && jar cf ../probe.jar .)
(cd "$APP_CLASSES" && jar cf "$WORK/app.jar" .)

# The release build's merged rules, minus output directives, plus the probe entry point.
grep -vE '^-(printmapping|printseeds|printusage|printconfiguration)' app/build/outputs/mapping/release/configuration.txt > "$WORK/rules.pro"
echo '-keep class R8Probe { public static void main(java.lang.String[]); }' >> "$WORK/rules.pro"

PROGRAM=("$WORK/app.jar" "$WORK/probe.jar" app/build/intermediates/compile_and_runtime_r_class_jar/release/processReleaseResources/R.jar)
IFS=':' read -ra DEPS <<< "$CP"
java -cp "$R8" com.android.tools.r8.R8 --release --classfile --output "$WORK/shrunk.jar" \
  --lib "$ANDROID_JAR" --lib "$JAVA_HOME" --pg-conf "$WORK/rules.pro" "${PROGRAM[@]}" "${DEPS[@]}" 2>&1 | grep -E '^Error|Exception' || true

openssl req -x509 -newkey rsa:2048 -nodes -subj /CN=pve1 -days 2 -keyout "$WORK/k.pem" -out "$WORK/c.pem" 2>/dev/null
FP=$(openssl x509 -in "$WORK/c.pem" -noout -fingerprint -sha256 | cut -d= -f2)
PVE_PORT=18006
python3 scripts/r8probe/fake_pve.py $PVE_PORT "$WORK/c.pem" "$WORK/k.pem" & PVE_PID=$!
sleep 1
HOSTFP=$(ssh-keygen -lf "$SSHD_DIR/hostkey.pub" | cut -d' ' -f2)
# Signed-jar metadata from BouncyCastle is copied verbatim but no longer matches; APKs drop it too.
zip -q -d "$WORK/shrunk.jar" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/*.EC' 2>/dev/null || true
echo "shrunk jar: $(du -h "$WORK/shrunk.jar" | cut -f1)"
java -cp "$WORK/shrunk.jar" R8Probe "$SSHD_DIR" "$SSH_PORT" "https://127.0.0.1:$PVE_PORT" "$FP" "$HOSTFP"
