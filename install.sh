#!/bin/bash
# Installs TVerk on an Android TV / Google TV over the network and makes it the home screen.
#
#   ./install.sh 192.168.1.50                  # install + set as home
#   ./install.sh 192.168.1.50 --replace-stock  # also disable the stock launcher (needed on Google TV)
#   ./install.sh 192.168.1.50 --restore-stock  # re-enable the stock launcher and switch back to it
#
# Uses build/tverk.apk if you built it, otherwise downloads the latest release.
# On the TV first: Settings > System > About > press "Build" 7 times, then
# Developer options > USB debugging ON (and Network debugging, if listed).
set -euo pipefail
cd "$(dirname "$0")"

REPO="dknz-maker/TVerk"
PKG="nz.home.tv"
# Stock launchers that outrank a third-party home screen. Disabled with `pm disable-user`, which is
# fully reversible (see --restore-stock); nothing is uninstalled.
STOCK_LAUNCHERS="com.google.android.apps.tv.launcherx com.google.android.tvlauncher"
# On Google TV, the setup app keeps relaunching a "recovery" home while launcherx is disabled.
GTV_SETUP="com.google.android.tungsten.setupwraith"

TV=${1:-}
MODE=${2:-}
[ -n "$TV" ] || { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
command -v adb >/dev/null || { echo "adb not found (macOS: brew install --cask android-platform-tools)" >&2; exit 1; }

[[ "$TV" == *:* ]] || TV="$TV:5555"
adb connect "$TV" >/dev/null
echo "Connecting to $TV - if the TV asks 'Allow USB debugging?', tick 'Always allow' and press OK."
for _ in $(seq 1 30); do
    state=$(adb -s "$TV" get-state 2>/dev/null || true)
    [ "$state" = device ] && break
    sleep 2
    adb connect "$TV" >/dev/null 2>&1 || true
done
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Could not connect to $TV (debugging enabled? same network?)" >&2; exit 1; }
tv() { adb -s "$TV" shell "$@" | tr -d '\r'; }

if [ "$MODE" = --restore-stock ]; then
    for p in $STOCK_LAUNCHERS $GTV_SETUP; do
        tv pm path "$p" >/dev/null 2>&1 && tv pm enable "$p" >/dev/null && echo "re-enabled $p"
    done
    for p in $STOCK_LAUNCHERS; do
        if tv pm path "$p" >/dev/null 2>&1; then
            act=$(tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep "$p/" | head -1 | tr -d ' ')
            [ -n "$act" ] && tv cmd package set-home-activity "$act" >/dev/null && echo "home screen: $act"
            break
        fi
    done
    echo "Stock launcher restored. To remove TVerk completely: adb -s $TV uninstall $PKG"
    exit 0
fi

APK=build/tverk.apk
if [ ! -f "$APK" ]; then
    APK=$(mktemp -d "${TMPDIR:-/tmp}/tverk.XXXXXX")/tverk.apk
    echo "Downloading the latest release..."
    curl -fsSL "https://github.com/$REPO/releases/latest/download/tverk.apk" -o "$APK"
fi

echo "Installing..."
adb -s "$TV" install -r "$APK" >/dev/null || {
    echo "Install failed. If an older copy was signed with a different key: adb -s $TV uninstall $PKG" >&2; exit 1; }

# Lets the "Continue watching" row see what YouTube is playing (media session titles only).
tv cmd notification allow_listener "$PKG/$PKG.MediaListener" >/dev/null

if [ "$MODE" = --replace-stock ]; then
    for p in $STOCK_LAUNCHERS; do
        tv pm path "$p" >/dev/null 2>&1 && tv pm disable-user --user 0 "$p" >/dev/null && echo "disabled $p"
    done
    if tv pm path "$GTV_SETUP" >/dev/null 2>&1 && [ "$(tv settings get secure user_setup_complete)" = 1 ]; then
        tv pm disable-user --user 0 "$GTV_SETUP" >/dev/null && echo "disabled $GTV_SETUP (setup is complete; re-enabled by --restore-stock)"
    fi
fi

# Photo screensaver (dimmed, slowly drifting photos + clock) instead of the stock one.
tv settings put secure screensaver_components "$PKG/.PhotoDream" >/dev/null
tv settings put secure screensaver_default_component "$PKG/.PhotoDream" >/dev/null

tv cmd package set-home-activity "$PKG/.MainActivity" >/dev/null
home=$(tv cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)
tv input keyevent KEYCODE_HOME

if [ "$home" = "$PKG/.MainActivity" ]; then
    echo "Done - TVerk is the home screen."
else
    echo "Installed, but the home button still opens: $home"
    echo "The stock launcher takes priority on this TV. Re-run with --replace-stock to disable it (reversible)."
fi
cat <<EOF

Configure (defaults: Auckland, NZ, English). Example:
  adb -s $TV shell am broadcast -a nz.home.tv.CONFIG -n nz.home.tv/.ConfigReceiver \\
      --es city London --es lat 51.5072 --es lon -0.1276 --es tide off
See README.md for all settings.
EOF
