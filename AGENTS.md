# Notes for coding agents

An Android app (Kotlin, Jetpack Compose) for a **Meta Portal 1st gen** (Android 9 / API 28, 1280×800 landscape, density 160 so 1 dp = 1 px). It pretends to be a Muse community gadget, forwards Pebble Index ring notes to Muse, and shows a Game Boy–style home screen and screensaver.

## Build and test

```bash
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` (with `sdk.dir`) is git-ignored. A fresh clone needs one.
- `McpHttpServerTest` binds port **18787** on the host. An `adb forward tcp:18787 …` left running makes those tests talk to the real Portal and fail. Use another port for forwards.
- Test the screensaver without waiting for idle: `adb shell am start -n com.android.systemui/.Somnambulator`.

## Muse protocol rules (mirror the SDK)

The reference is [facebookincubator/muse-gadget-sdk](https://github.com/facebookincubator/muse-gadget-sdk) `linux/src/musegadget`. Match it before inventing anything.

- Connect the Noise link to `wss://<noise_host>/v1/noise?vm_id=…`, where `noise_host` comes from `provision_v2` (default `hatch.metaaivm.com`). **Never use `vm_ws_url`**: its per-VM hostname doesn't resolve.
- A 403 on the `/v1/noise` upgrade is normal for a few minutes after pairing or any app restart. It's not a code bug. If it never clears, the fix is a new SDK token and re-pairing.
- Muse's answers come on `/chat/subscribe` (NDJSON events), not on the `/chat/stream` response (only an ack with `message_id`). `MuseTurn` matches answers to our message (port of hey-muse `turn.go`). Register the turn **before** sending; events can beat the ack. Subscribe with `{"session_id": …}` when notes go to a side chat: the default feed only has the main chat's messages (a side chat sends just `sessions.updated`).
- Commands: announce them in `link.register` → `commands_v2` (SDK executor shape). They arrive as `link.invoke` on `/link-control` (length-prefixed) **or** `client.invoke` on `/chat/subscribe` (for turns this device started); answer both with `link.result`.
- All encrypt-and-send goes through `sendLock`: the Noise nonce order must match the send order.
- Never log decrypted BLE payloads: `provision_v2` carries access and refresh tokens.
- App settings live in SharedPreferences and override `.env` (which is only a build-time default).

## Home screen rules

- **Four shades only.** Every color comes from `GB.Darkest/Dark/Light/Lightest` (`ui/home/Pixel.kt`), which follow the active `PixelTheme`. Don't hard-code colors on the home screen, or themes break.
- The robot (`PixelRobot.kt`) is drawn in code, in "virtual pixels", through `PixelPen`. Keep shapes on the pixel grid. Motion may use fractional offsets.
- Don't change state while drawing or during composition. The robot's blink is derived from time for this reason.
- Fonts: `PixelFont` (Press Start 2P) is ASCII only, so use it for the clock and labels. Any user text, such as notes, must use `TerminalFont` (VT323), which covers Vietnamese.
- Gestalt layout: group related items (robot with its bubble; time with date and weather), give each area its own Game Boy frame, and keep the clock the largest item.
- Gestures: tap the robot to react, tap the bubble to close it, tap empty space to change theme, long-press anywhere for Settings.

## Screensaver

- Needs `adb shell pm grant com.portal.pebblebridge android.permission.WRITE_SECURE_SETTINGS`.
- `ScreensaverGuard` stores the previous `screensaver_components` and restores it when the user turns ours off. It watches `screensaver_components` and `screensaver_enabled`, rate-limited to avoid ping-pong with other apps.
- `HomeDreamService` launches the activity from **`onCreate`**: some launchers wake the device about 20 ms after a dream starts, before `onDreamingStarted`.

## Privacy

This repo is public. Don't commit tokens, Wi-Fi names, device serials, home or Tailscale IPs, or the user's city. Use placeholders (`<portal-ip>`, `100.x.y.z`). Before taking screenshots for docs, set a neutral weather city and avoid the Status tab, which shows IPs.
