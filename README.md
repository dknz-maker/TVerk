# TVerk

A calm, ad-free home screen for **Android TV / Google TV**, styled after Apple TV.

![Home screen](docs/screenshot-top.png)
![Rows](docs/screenshot-rows.png)

- **Continue watching:** the last YouTube videos played on the TV. Select one to resume where you
  stopped. Works whether you're signed in or watching as a guest.
- **Weather:** current conditions plus a 10-day forecast ([Open-Meteo](https://open-meteo.com), no
  API key). On clear nights it shows the real **moon phase**, mirrored in the southern hemisphere.
- **Tide:** time until the next high or low tide, the next turns, and a 24-hour curve for a spot you choose.
- **Calendar:** the current month, with weeks starting on your locale's first day.
- **Photo backgrounds:** a new landscape every 15 minutes, with its location
  ([Bing image archive](https://www.bing.com)).
- **Screensaver:** the same photos full screen with a slow drift, a large clock and dimmed
  brightness. It replaces the stock screensaver, which on some TVs is a rainbow gradient or ads.
- **Apps** in the order you choose, and **inputs** with HDMI-CEC device names (e.g. "PlayStation 4").
- English and Russian, following the system language or set separately.
- Plain Java on the Android framework, with no libraries and no Gradle. The APK is about 50 KB and
  uses roughly 55–65 MB of RAM.
- No accounts, analytics or tracking. The only network calls are to Open-Meteo, Bing images and
  YouTube (thumbnails and video lookup).

Requires Android 8.0+ (API 26). Tested on Android TV 11 / Google TV (Skyworth-made JVC 4K TV).

## Install

**1. On the TV**, turn on debugging: **Settings > System > About**, press **Build** (or "Android TV
OS build") 7 times. Then go to **Settings > System > Developer options** and switch on **USB
debugging** (and **Network debugging**, if listed). Note the TV's IP address in **Settings > Network**.

**2. On your computer**, with `adb` installed (`brew install --cask android-platform-tools`,
`apt install adb`, or the Android SDK):

```sh
git clone https://github.com/dknz-maker/TVerk.git && cd TVerk
./install.sh 192.168.1.50                    # your TV's IP
```

The TV asks **"Allow USB debugging?"**: tick *Always allow* and press OK. The script downloads the
latest release (or uses your own build), installs it, lets it read YouTube's "now playing" info,
makes it the home screen and sets it as the screensaver.

**Google TV:** the stock launcher outranks third-party home screens, so the script tells you if the
Home button still opens it. Then run:

```sh
./install.sh 192.168.1.50 --replace-stock    # TVerk takes over the Home button
./install.sh 192.168.1.50 --restore-stock    # undo: give Home back to the stock launcher
```

`--replace-stock` turns on TVerk's **Home button** accessibility service. It opens TVerk when you
press Home, and whenever the stock home screen comes to the front (after a restart, or when you
leave an app; you may see the stock screen for a moment). The stock launcher stays installed and
enabled. Don't uninstall or disable it, or the Google TV setup app
(`com.google.android.tungsten.setupwraith`): other system apps can depend on them. On Skyworth-made
TVs (JVC and others), Live TV crashes without the setup app, which breaks HDMI input switching and
one-touch play from consoles.

Prefer to install by hand? Download `tverk.apk` from
[Releases](https://github.com/dknz-maker/TVerk/releases), then:

```sh
adb install -r tverk.apk
adb shell cmd notification allow_listener nz.home.tv/nz.home.tv.MediaListener
adb shell cmd package set-home-activity nz.home.tv/.MainActivity
adb shell settings put secure screensaver_components nz.home.tv/.PhotoDream   # optional
```

**Afterwards** you can switch **Network debugging** (and USB debugging) off again in Developer
options. While it's on, any computer on your network can ask the TV for debugging access; the TV
only allows computers you've approved on screen, but leaving it off is safer. Switch it back on
when you want to change settings or update.

## Configure

Settings are changed over adb. The home screen refreshes with the new values:

```sh
adb shell am broadcast -a nz.home.tv.CONFIG -n nz.home.tv/.ConfigReceiver \
    --es city "London" --es lat 51.5072 --es lon -0.1276 --es tide off
```

| Key | Default | Meaning |
|---|---|---|
| `city` | `Auckland` | Name shown with the weather |
| `lat`, `lon` | `-36.8485`, `174.7633` | Weather location |
| `units` | `celsius` | `celsius` or `fahrenheit` |
| `tide` | `Browns Bay` | Tide spot name, or `off` to hide the tide card |
| `tide_lat`, `tide_lon` | `-36.7166`, `174.75` | Tide location; pick a point just offshore |
| `lang` | system | `en`, `ru`, or empty for the system language |
| `order` | popular apps | Comma-separated package names shown first in the Apps row |
| `hide` | none | Comma-separated package names to hide |
| `captions` | `true` | Show the photo location |
| `market` | `en-US` | Bing market for photos |

`--ez reset true` restores all defaults. Package names: `adb shell pm list packages`.

Only the adb shell (or the system) can send this broadcast. The receiver requires
`android.permission.DUMP`, which normal apps can't hold.

## How it works

| | |
|---|---|
| Continue watching | A `NotificationListenerService` (no notifications are read) gets access to media sessions. When YouTube has played a video for 10 s, it saves the title, channel and artwork. YouTube doesn't expose the video ID, so the app looks it up once with a YouTube search for the title and channel. When playback pauses or stops, the position is saved so the card can resume with `&t=`. Everything stays on the TV, in the app's private storage. |
| Weather | Open-Meteo forecast API, refreshed every 30 minutes. |
| Tide | Open-Meteo marine API (`sea_level_height_msl`), hourly. Highs and lows are refined with a parabola fit. It's a model, not official tide tables: times are good, heights are smoothed, so the card shows the state and times rather than metres. |
| Moon | Calculated locally from the synodic month (reference new moon 2000-01-06). |
| Photos | Bing's daily image archive (about 15 photos), cached on disk. The photo changes on each quarter hour. |
| Screensaver | A `DreamService` that crossfades the cached photos every minute with a slow zoom and pan, dims the backlight (`setScreenBright(false)`) and shows the time, date and temperature. |

## Privacy and security

- Every network call is HTTPS, with no cookies, accounts or identifiers. Open-Meteo receives the
  weather and tide coordinates; YouTube receives the title and channel of a video you watched (once,
  to find its ID); Bing receives the photo market.
- Watch history, positions and thumbnails stay in the app's private storage and are never uploaded.
  `allowBackup` is off, so they aren't copied into cloud backups either.
- The notification listener is only used to reach media sessions, and only YouTube's session is
  read. Notifications themselves are ignored.
- The optional Home button service (`--replace-stock`) only looks at the Home key and at which
  screen comes to the front. It can't read screen content (`canRetrieveWindowContent="false"`).
- Settings can only come from the adb shell (`android.permission.DUMP`, see above). Other
  components are protected by system-only permissions (`BIND_NOTIFICATION_LISTENER_SERVICE`,
  `BIND_DREAM_SERVICE`).
- Release APKs are signed with this certificate (SHA-256):
  `fc0046c77dc6d0545e72197b5b1fb5cc92e9fef409cfd221ed91f5fe3bc75fc5`.
  Check with `apksigner verify --print-certs tverk.apk`.

## Build from source

Needs the Android SDK (build-tools and any platform ≥ 30) and JDK 11+. No Gradle:

```sh
./build.sh            # -> build/tverk.apk
./build.sh install    # build and adb install
```

Each machine signs with its own local key (`tverk.keystore`, with a random password in
`tverk.keystore.pass`; both git-ignored). To switch between the release APK and your own build,
`adb uninstall nz.home.tv` first.

Code: `src/nz/home/tv/`. `MainActivity` is the UI, `History` and `MediaListener` handle continue
watching, `PhotoDream` is the screensaver, `HomeKeeper` is the Home button service, and `Tide`, `Wallpapers`, `Config` and `ConfigReceiver`
do what their names say. Strings live
in `res/values*/strings.xml`; to add a language, add `res/values-xx/strings.xml`.

## Optional: debloat the TV

[extras/DEBLOAT.md](extras/DEBLOAT.md) describes how I removed ads, telemetry and vendor services
from a JVC/Skyworth Google TV without root, which packages I kept and why, and how to undo it.

## Uninstall

```sh
./install.sh <tv-ip> --restore-stock
adb uninstall nz.home.tv
```

## License

MIT
