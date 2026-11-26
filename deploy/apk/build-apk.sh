#!/bin/sh
# Builds the debug APK for the configured LAN address and publishes it, with its QR code and the download page,
# into /dist (served by the "download" service). Skips the build when the same source and address were built.
set -eu

: "${HOST_IP:?HOST_IP is required}"
DOWNLOAD_PORT="${DOWNLOAD_PORT:-8000}"
APK_URL="http://${HOST_IP}:${DOWNLOAD_PORT}/yousong.apk"
STAMP="$(cat /opt/source-id) ${HOST_IP} ${DOWNLOAD_PORT}"

mkdir -p /dist
if [ -f /dist/yousong.apk ] && [ -f /dist/.build-id ] && [ "$(cat /dist/.build-id)" = "$STAMP" ]; then
    echo "APK already built for ${HOST_IP} (same source): nothing to do"
    exit 0
fi

echo "Building the APK for http://${HOST_IP}:8080 ..."
cd /src
./gradlew --no-daemon --console=plain --quiet assembleDebug "-PyousongLanHost=${HOST_IP}"

cp app/build/outputs/apk/debug/app-debug.apk /dist/yousong.apk.tmp
mv /dist/yousong.apk.tmp /dist/yousong.apk
qrencode -o /dist/qr.png -s 12 -m 2 "$APK_URL"
sed -e "s|__APK_URL__|${APK_URL}|g" -e "s|__HOST_IP__|${HOST_IP}|g" /opt/index.html.template > /dist/index.html
printf '%s' "$STAMP" > /dist/.build-id
echo "APK published: ${APK_URL}"
