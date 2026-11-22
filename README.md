# Music Companion for Android

Music Companion is a standalone phone app for the **Music Controller** app on the CMF Watch Pro 2.

It owns its BLE connection, keeps it in an Android foreground service, and discovers the phone's active Android media session. The phone UI can display the current track locally and provide playback and music-volume controls.

## Privacy boundary

The watch-facing `M1` protocol carries:

- whether a media session is active
- whether playback is playing
- the current and maximum `STREAM_MUSIC` volume
- command success/error status
- the current track title and artist
- a low-resolution copy of the matching notification artwork

Album names, notification text, and other notification contents stay on the phone. The transferred title, artist, and artwork are not logged, uploaded, or persisted by Music Companion. The watch keeps received artwork and track text in memory only for the running app session.

Artwork comes only from the media notification whose `MediaSession.Token` exactly matches the selected active session; Music Companion never substitutes `MediaMetadata` artwork or artwork from a different notification. The artwork is normalized to a 64×64 RGB332 image before transfer. Title and artist come from the selected active media session, with the exact-token notification used as a local fallback when session metadata is absent.

The watch channel is **not private**: its pairing key is readable by nearby Bluetooth clients. A client that obtains that key can observe the transferred title, artist, and artwork as well as the playback state, and can inject messages into the watch-facing channel.

Notification-listener access gives Android's notification service permission to expose notifications to this app. Music Companion retains only the currently selected media session's metadata and matching artwork in memory, uses the matching notification for the local UI and the dedicated watch fields, and discards it when the UI refreshes. It never stores notification contents.

## Install

Download the current APK from the [GitHub releases page](https://github.com/boxmol71-gif/music-companion/releases/latest). The `0.2.0` APK is debug-signed for sideloading; verify its SHA-256 before installation:

```text
101c7acd77360b3025445334fb260dc8d4ce01802b4c63875d7dc7058e613ee2  MusicCompanion-0.2.0-debug.apk
```

Version 0.2.0 adds the title, artist, and matching notification artwork transfer used by Music Controller 0.2.0. Version 0.1.1 remains a compatibility fallback for the watch's playback and volume controls, but cannot show track text or artwork.

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

- `0x60`: one state record (`kind`, flags, volume, and max volume)
- `0x61`: play, pause, toggle, previous, next, volume up, volume down, or set volume
- `0x62`: current artwork; a 25-byte manifest followed by 27 bounded payload records
- `0x63`: current title and artist in two bounded text records

Method `0x62` uses fixed 64×64 RGB332 artwork. Its manifest contains a content-derived generation and CRC-32; the watch assembles the image in a separate buffer, hides the stale cover during replacement, and swaps in the new image only after the complete payload passes validation. An unchanged generation returns an empty `OK` record.

Method `0x63` returns a title record and an artist record. Each record contains a kind byte, a byte length, and at most 60 printable ASCII bytes. Longer values are truncated on the phone.

`OK`, `BAD_REQUEST`, `DENIED`, `UNAVAILABLE`, and `ASK` are reported separately. Missing notification-listener access is `ASK` until it has been granted once, then `DENIED` if access is revoked. Playback commands are refused when the selected media session does not advertise the corresponding transport action. The app is pull-based: requests stop while Music Controller is closed on the watch.

Each `M1A` header is 7 bytes, so a record carries at most 121 payload bytes. The watch pairing key is encrypted with AES-GCM under an Android Keystore key and is never stored in plaintext.

## Build

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```
