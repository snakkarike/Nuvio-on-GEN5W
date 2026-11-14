#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
NEMESIS=/system/xbin/nemesis
PKG=packages/nuvio.gen5w.zip
APP=org.nuvio.gen5w
EXPECTED_CODE=__VERSION_CODE__
LOG=/data/nemesis/nuvio-auto-install.log

say() {
  echo "$1"
  echo "$1" >> "$LOG"
}

if [ ! -x "$NEMESIS" ]; then
  echo "NEMESIS command missing. Installation cancelled."
  exit 10
fi
if [ "$(/system/bin/getprop sys.boot_completed)" != "1" ]; then
  say "Android is not fully booted. No apps changed."
  exit 11
fi
if [ ! -r "$PKG" ]; then
  say "Replacement Nuvio package missing. Existing app left intact."
  exit 12
fi

INFO=$("$NEMESIS" pkg-info "$PKG" 2>&1)
RESULT=$?
if [ "$RESULT" -ne 0 ]; then
  say "Replacement failed NEMESIS preflight. Existing app left intact."
  exit 13
fi
case "$INFO" in
  *"id=nuvio.gen5w"*) ;;
  *) say "Unexpected package ID. Existing app left intact."; exit 14 ;;
esac
case "$INFO" in
  *"locked=1"*) say "Package is locked. No apps changed."; exit 15 ;;
esac

say "Nuvio package verified. Installing Android versionCode $EXPECTED_CODE."
/system/bin/am force-stop "$APP" >/dev/null 2>&1
case "$INFO" in
  *"installed=1"*)
    say "Uninstalling ONLY nuvio.gen5w (login and artwork cache will reset)."
    "$NEMESIS" uninstall nuvio.gen5w
    if [ "$?" -ne 0 ]; then
      say "Nuvio uninstall failed; replacement not attempted."
      exit 16
    fi
    ;;
  *) say "No existing Nuvio package registered; installing fresh." ;;
esac
"$NEMESIS" install "$PKG"
if [ "$?" -ne 0 ]; then
  say "Nuvio install failed. Recover using a regular NEMESIS install."
  exit 17
fi

# A successful installer command alone does not prove the new APK took effect.
DUMP=$(/system/bin/dumpsys package "$APP" 2>&1)
case "$DUMP" in
  *"versionCode=$EXPECTED_CODE"*)
    say "VERIFIED: Android installed Nuvio Auto versionCode $EXPECTED_CODE."
    ;;
  *)
    say "INSTALL VERIFICATION FAILED: Android did not report versionCode $EXPECTED_CODE."
    say "The previous app may still be installed. Check this installer log."
    exit 18
    ;;
esac
say "Nuvio Auto clean installation verified."
exit 0
