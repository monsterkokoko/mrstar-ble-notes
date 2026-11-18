# BLE Bridge

A tiny Android app (no root, no libraries, no Gradle) that keeps one Bluetooth LE device connected and exposes it as a
JSON-over-HTTP API on `127.0.0.1:8099`. It knows nothing about the garland: it writes the bytes you give it to the
characteristic you name. Anything that can reach that port drives the device. In our setup that is a server, through a
reverse SSH tunnel from Termux on the phone.

It runs as a foreground service (an icon in the notification shade), starts after boot, and reconnects the saved
device when it comes back.

## API

All `GET`, loopback only. Replies are `{"ok": true|false, "result": …}`.

| Path | |
| --- | --- |
| `/status` | Bluetooth on, connected, address, how long connected, the gap before this connection |
| `/scan?seconds=4&name=` | Devices nearby |
| `/connect?address=AA:BB:…&save=1` | Connect, and remember to keep it connected |
| `/services` | Its GATT services and characteristics |
| `/write?char=fff3&hex=bc01010155[&service=&mode=resp\|noresp]` | Write bytes |
| `/read?char=` | Read |
| `/notify?char=&on=1`, `/notifications` | Subscribe, and drain what arrived |
| `/disconnect[?forget=1]` | Let go of it |
| `/config?idle=<s>&on_return=<char>:<hex>&return_gap=<s>` | `idle`: let go after this long without commands (0 = never). `on_return`: bytes to write by itself the moment the device reconnects after being gone at least `return_gap` s. We use it to switch the garland off when the mains come back at night, before the router is even up. |
| `/bt?on=1\|0` | Bluetooth on or off |
| `/log` | Recent log lines |
| `/camera` | What the phone's cameras can do |
| `/photo?w=1600&wb=daylight&ev=-2[&exposure_ms=&iso=&zoom=]` | One JPEG, white balance fixed and exposure locked (see METHOD.md) |
| `/burst?w=320&ms=4000&ev=-2` | Preview frames as JPEGs back to back, offsets and times in the `X-Shot` header |

## Build

`build.sh` builds the APK with `aapt2`, `javac`, `d8`, `zipalign` and `apksigner`, from JDK 17 and an Android SDK
(build-tools 34, platform 27) in `~/android-build` (or `ANDROID_BUILD=`). It makes a signing key there on first run;
keep it, because updates must be signed with the same one.

To install from Termux: copy the APK to `/sdcard/Download/` first and open it there with
`termux-open --view --content-type application/vnd.android.package-archive /sdcard/Download/ble-bridge.apk`.
MIUI's package installer crashed when the APK was opened from Termux's home.

On the phone, allow location (Android 8 won't scan for BLE without it), no battery restrictions, autostart (MIUI), and
the camera if you want photos.
