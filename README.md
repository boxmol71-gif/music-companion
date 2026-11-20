# Music Companion for Android

Music Companion is a standalone phone app for the **Music Controller** app on the CMF Watch Pro 2.

It owns its BLE connection, keeps it in an Android foreground service, and discovers the phone's active Android media session. The phone UI can display the current track locally and provide playback and music-volume controls.

## Privacy boundary

The watch-facing `M1` protocol is deliberately non-personal. It carries only:

- whether a media session is active
- whether playback is playing
- the current and maximum `STREAM_MUSIC` volume
- command success/error status

Track titles, artists, albums, notification text, artwork, and every other track-identifying value stay on the phone. They are never put into an `M1A` reply, logged, uploaded, or persisted. Artwork shown in the phone UI comes only from the media notification whose `MediaSession.Token` exactly matches the selected session, and is not sent over BLE.

Notification-listener access gives Android's notification service permission to expose notifications to this app. Music Companion retains only the currently selected media session's metadata and matching artwork in memory, uses the matching notification only as a local metadata fallback, and discards it when the UI refreshes. It never stores notification contents.

## Install

Download the current APK from the [GitHub releases page](https://github.com/boxmol71-gif/music-companion/releases/latest). The `0.1.1` APK is debug-signed for sideloading; verify its SHA-256 before installation:

```text
8a39384d36e38cb9d5b4e44d3da956c16bf2cc4d35cc096e8da98d7b3aaa603d  MusicCompanion-0.1.1-debug.apk
```

Version 0.1.1 uses a dedicated exported launcher activity, so Android exposes the app in the launcher and the package installer can open it normally. It also invalidates the BLE connection after a timed-out operation so a late GATT callback cannot complete a later request.

## Requirements

- Android 12 or newer
- CMF Watch Pro 2 with the patched firmware and App Loader
- The **Music Controller** watch app installed through fmc.tools
- Bluetooth permission granted
- Restricted settings allowed first on Android 13 and newer when installing the sideloaded APK
- Notification-listener access enabled for Music Companion
- Foreground notifications allowed (recommended on Android 13 and newer)

### Enable notification access on Android 13+ / HyperOS

Because this release is a sideloaded APK, Android may restrict notification-listener access. Allow restricted settings **before** opening notification settings:

1. Open **Settings → Apps → Manage apps → Music Companion → App info**.
2. Tap **⋮ → Allow restricted settings** and confirm.
3. Now open **Settings → Notifications → Notification access**. HyperOS may label this **Device & app notifications**.
4. Enable **Music Companion**. If the toggle was initially greyed out, tapping it once and acknowledging the system warning can make the **Allow restricted settings** menu appear in App info.
5. Return to Music Companion, allow foreground notifications, and enable **Watch bridge**.

Open the app, grant the listed permissions, and enable **Watch bridge**. The screen also shows the current track and provides local playback and music-volume controls.

Music Companion and FMC Companion can be installed at the same time. The watch app uses the dedicated `M1` protocol, so the two phone apps do not answer each other's requests. As required by the watch BLE ABI, only one ordinary central connection can be active at a time; simultaneous generic companion connections can contend for it.

The first time the bridge is enabled, it selects a connected or bonded CMF Watch and then remembers that watch's hashed identity in Android Keystore-backed storage. Later reconnects accept only that watch. The phone also checks that an `M1Q` event came from the installed `music-controller` package slot and sends the `M1A` reply to that exact slot. The watch ABI's global channel key is not a peer-authentication key, so the first pairing should still be done with the intended watch nearby.

## Protocol

The phone listens for serial `M1Q` requests and answers with `M1A` records:

- `0x60`: one non-personal state record (`kind`, flags, volume, and max volume)
- `0x61`: play, pause, toggle, previous, next, volume up, volume down, or set volume

`OK`, `BAD_REQUEST`, `DENIED`, `UNAVAILABLE`, and `ASK` are reported separately. Missing notification-listener access is `ASK` until it has been granted once, then `DENIED` if access is revoked. Playback commands are refused when the selected media session does not advertise the corresponding transport action. The app is pull-based: requests stop while Music Controller is closed on the watch.

Each `M1A` header is 7 bytes, so a record carries at most 121 payload bytes. The watch pairing key is encrypted with AES-GCM under an Android Keystore key and is never stored in plaintext.

## Build

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```
