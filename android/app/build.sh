#!/usr/bin/env bash
# 通用版 APK 构建脚本（不含任何个人地址/token）
#
# 需要：JDK 17、Android SDK（platform 34 + build-tools 34.0.0）、python。
# 用法：
#   ANDROID_SDK=/path/to/sdk JAVA_HOME=/path/to/jdk17 bash build.sh
#
# 可选：
#   KS_FILE=/path/to/keystore.jks   沿用已有签名（覆盖安装旧版时必须同一签名）
#   KS_PASS=******                   keystore 密码
#   PYTHON=python
set -e

SDK="${ANDROID_SDK:-E:/AI_Claude/android-sdk/sdk}"
JDK="${JAVA_HOME:-E:/AI_Claude/android-sdk/jdk17-tmp/jdk-17.0.13+11}"
APP="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$APP/../.." && pwd)"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
PYTHON="${PYTHON:-python}"
KS="${KS_FILE:-$APP/keystore.jks}"
KS_PASS="${KS_PASS:-claudeworkbench}"

# 从 PC 版 server.js / 手机页生成手机端资源（不会改 PC 版源码）
"$PYTHON" "$REPO/phone/build-phone-assets.py"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

cd "$APP"
rm -rf build gen dex
mkdir -p build gen dex

"$BT/aapt2.exe" compile --dir res -o build/resources.zip
"$BT/aapt2.exe" link -o build/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml --java gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  -0 tgz -0 apk -0 gz -A assets build/resources.zip

find src -name '*.java' > build/sources.txt
"$JDK/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath "$AJ" -d build/classes \
  gen/com/missyao/claudeworkbench/R.java \
  @build/sources.txt

"$BT/d8.bat" --lib "$AJ" --min-api 26 --output dex \
  $(find build/classes -name '*.class')


"$PYTHON" - <<'PYEOF'
import zipfile, os
apk = 'build/base.apk'
tmp = 'build/base_new.apk'
with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w') as zout:
    names = set()
    for item in zin.infolist():
        if item.filename == 'classes.dex':
            continue
        zout.writestr(item, zin.read(item.filename))
        names.add(item.filename)
    zi = zipfile.ZipInfo('classes.dex')
    zi.compress_type = zipfile.ZIP_STORED
    with open('dex/classes.dex', 'rb') as f:
        zout.writestr(zi, f.read())
    libdir = 'libs/arm64-v8a'
    if os.path.isdir(libdir):
        for name in sorted(os.listdir(libdir)):
            if not name.endswith('.so'):
                continue
            entry = 'lib/arm64-v8a/' + name
            if entry in names:
                continue
            zi = zipfile.ZipInfo(entry)
            zi.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(libdir, name), 'rb') as f:
                zout.writestr(zi, f.read())
os.replace(tmp, apk)
PYEOF

"$BT/zipalign.exe" -f 4 build/base.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool.exe" -genkeypair -keystore "$KS" -alias app -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Claude Workbench, OU=Personal, O=Personal, L=Shanghai, ST=Shanghai, C=CN"
fi

"$BT/apksigner.bat" sign --ks "$KS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out ClaudeWorkbench.apk build/aligned.apk

"$BT/apksigner.bat" verify --verbose ClaudeWorkbench.apk
echo "APK: $APP/ClaudeWorkbench.apk"
