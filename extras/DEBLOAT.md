# Optional: debloating the TV

TV Home only replaces the home screen. The ads, telemetry and vendor services that came with the TV
keep running unless you turn them off. This is what I did on a **JVC 4K Smart TV (NZ)**, which is a
Skyworth-made Google TV (Android TV 11, MediaTek/MStar M7332, 1.8 GB RAM). Treat it as an example:
package names differ between brands, so check what's on your own TV first.

Nothing here needs root. `pm uninstall --user 0` removes an app for the current user only. The APK
stays on the read-only system partition, the app never runs, and it can be restored at any time.
A factory reset also brings everything back.

## 1. Look before you remove

```sh
adb shell pm list packages -s          # system apps
adb shell pm list packages -3          # apps you installed
adb shell dumpsys meminfo | sed -n '/Total PSS by process/,/^$/p' | head -30   # what uses RAM
```

## 2. Removed on the JVC/Skyworth TV

| Package | What it is |
|---|---|
| `com.smartdevice.recommendation` | Vendor ad/recommendation feed |
| `com.smartdevice.dailyshortcut`, `com.smartdevice.dailymanage` | Vendor background services |
| `com.smartdevice.epopplayer`, `com.coocaa.demonow.lightining`, `fusion.android.tv.demo` | Shop-floor demo / popup players |
| `com.smartdevice.aftersale` | Vendor after-sales data collection |
| `com.smartdevice.myapp`, `com.smartdevice.leanbacklauncher.customizer`, `com.mediatek.android.leanbacklauncher.partnercustomizer` | Partner promo rows |
| `android.autoinstalls.config.google.gtvpai` | Silent installer for promoted apps |
| `com.google.android.feedback`, `com.google.android.play.games`, `com.android.printspooler`, `com.android.dynsystem` | Not useful on a TV |
| `com.google.android.apps.tv.dreamx` | Ambient mode with promoted content (~150 MB RAM); set a basic screensaver first, see below |
| `com.google.android.apps.tv.launcherx`, `com.google.android.videos` | Stock Google TV home and its recommendation feed (~160 MB RAM) |
| `com.google.android.tungsten.setupwraith` | Setup wizard; with launcherx gone it loops a "recovery" home screen. Only remove after setup is complete |

```sh
# basic screensaver instead of the ambient mode
adb shell settings put secure screensaver_components com.android.dreams.basic/.Colors
adb shell settings put secure screensaver_default_component com.android.dreams.basic/.Colors

for p in com.smartdevice.recommendation com.smartdevice.dailyshortcut ...; do
  adb shell pm uninstall -k --user 0 "$p"
done
```

**Kept on purpose:** the tuner/Live TV (`com.smartdevice.livetv`), TV input and MediaTek services,
Bluetooth and remote pairing, Dolby audio, Google Play services, the Play Store, Chromecast
(`com.google.android.apps.mediashell`), the remote service, the keyboard, WebView and Assistant/voice
search (`com.google.android.katniss`). Removing those is what actually breaks TVs.

## 3. Speed and privacy settings

```sh
# faster animations
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global $k 0.5
done
# no Wi-Fi/Bluetooth scanning while those are off
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0
```

Then on the TV: **Settings > Privacy > Ads > Delete advertising ID**.

## 4. Undo

```sh
adb shell cmd package install-existing --user 0 <package>   # restore any removed package
```
