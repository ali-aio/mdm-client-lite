#!/usr/bin/env bash
# Build the MDM Lite demo app and publish it to an MDM as the hosted build for the
# "lite-demo" slot. From then on the server owns its versioning: the Clients page shows
# which devices are behind, and "Update app" installs this build over the old one.
#
#   tools/publish-demo.sh -s https://mdm-stage.dev.aioapp.com -k "$ADMIN_API_KEY" \
#       -t enr_xxx -c 12 -v 0.1.11
#
# The enrolment token is baked in at build time (MDM Lite reads it from MdmConfig and
# has no way to be told a server remotely), so a build is tied to the server it was
# built for. Mint one with:
#   curl -X POST "$SERVER/api/v1/enrollment-profiles" -H "X-API-Key: $KEY" \
#        -d '{"name":"Lite demo","device_class":"dongle"}'
set -euo pipefail

SERVER=""; KEY=""; TOKEN=""; VCODE=""; VNAME=""; APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s|--server) SERVER="${2%/}"; shift 2 ;;
    -k|--key) KEY="$2"; shift 2 ;;
    -t|--token) TOKEN="$2"; shift 2 ;;
    -c|--version-code) VCODE="$2"; shift 2 ;;
    -v|--version-name) VNAME="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    -h|--help) sed -n 2,15p "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SERVER" ] && [ -n "$KEY" ] || { echo "need -s SERVER and -k ADMIN_API_KEY (see -h)" >&2; exit 2; }

cd "$(dirname "$0")/.."

if [ -z "$APK" ]; then
  [ -n "$TOKEN" ] || { echo "need -t ENROLL_TOKEN when building (see -h)" >&2; exit 2; }
  # A self-update installs only over a strictly higher version code, so a build that
  # forgets to bump can never reach the devices it was meant to fix.
  [ -n "$VCODE" ] || { echo "need -c VERSION_CODE — an update must be newer than what is installed" >&2; exit 2; }
  ARGS=(-PmdmServerUrl="$SERVER" -PmdmEnrollToken="$TOKEN" -PversionCode="$VCODE")
  [ -n "$VNAME" ] && ARGS+=(-PversionName="$VNAME")
  echo "→ building the demo app for $SERVER"
  ./gradlew :app:assembleDebug "${ARGS[@]}" --console=plain -q
  APK=app/build/outputs/apk/debug/app-debug.apk
fi
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

echo "→ publishing $(stat -c %s "$APK") bytes to $SERVER (slot lite-demo)"
curl -fsS -X POST "$SERVER/api/v1/agent-apk?slot=lite-demo&name=$(basename "$APK")" \
  -H "X-API-Key: $KEY" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK"
echo
echo "→ Clients page now shows this as the hosted build for the demo app."
