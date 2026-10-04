# Salt

A local development tool for Android developers that runs in your browser: devices, apps, deep links,
screen, logcat, SDK and emulator management, plus a Proxyman/Postman-style network inspector and request composer.
No Android Studio needed.

Kotlin Multiplatform: the UI (Compose Multiplatform for the web), the API contract, the adb command
builders and the output parsers are written once in `shared`. The JVM server only runs processes and stores files.

```
shared/    commonMain: models, API interfaces, adb/android-CLI action builders + parsers, HTTP client, design system, all screens
webApp/    wasmJs entry point: mounts the shared UI in the browser
server/    JVM (Ktor): runs adb / android CLI, the intercepting proxy, file storage; serves the web UI
```

## Requirements

- JDK 21 or newer
- Android SDK with `platform-tools` (adb); the [`android` CLI](https://developer.android.com/tools/agents) for SDK and emulator tools
- A browser with WebAssembly GC support (current Chrome, Edge, Firefox or Safari)
- macOS or Linux (the server runs processes with `/dev/null`; Windows is untested)

Salt finds adb via the Android section's settings, then `$ANDROID_HOME/platform-tools`, then `PATH`.

## Run

```sh
./gradlew :server:installDist        # builds the web UI and bundles it into the server
server/build/install/server/bin/server
```

Open **http://127.0.0.1:8080**.

The server binds to loopback only. It can run arbitrary adb commands, so don't expose it.

| Environment variable | Default | Meaning |
|---|---|---|
| `SALT_PORT` | `8080` | Port of the web UI and API |
| `SALT_HOME` | `~/.salt` | Where settings and data live |

For development, `./gradlew :server:run` starts the server without installing it. The UI is rebuilt and bundled
on every server build, so UI changes need a rebuild and restart.

## Using it

**Android**
- **Devices**: pick a device (a lone online device is picked for you). Pair and connect over Wi-Fi.
- **Apps**: install an APK, launch, stop, clear data, uninstall.
- **Deep links**: open any URI on the device, optionally restricted to one package.
- **Screen**: screenshot (or live view), navigation keys, type text.
- **Logcat**: filter and colour-coded levels.
- **Device**: developer toggles (animations, dark mode, show taps, …), font scale, reboot.
- **Diagnostics**: one-tap battery, memory, foreground activity, network, storage reports.
- **Emulators**: create from a profile, start, stop, delete.
- **SDK**: update, remove, search and install packages.

**Network**
1. Check the proxy port and **Decrypt HTTPS** in Network → Settings (default port 9090).
2. Network → Traffic → **Start proxy**.
3. Select a device (Android → Devices), then **Point device at proxy**. This uses `adb reverse`, so it works on
   emulators and USB devices. **Reset device proxy** undoes it.
4. For HTTPS, press **Push CA to device**, then on the device install `salt-ca.crt` from Settings → Security →
   Install a certificate → CA certificate. Apps targeting Android 7+ ignore user CAs unless their debug build
   opts in (`networkSecurityConfig` trusting `user` certs). Pinned apps will show a TLS error in the capture list.
5. Click a capture and **Edit & resend** to modify it in the Compose tab. Requests can be saved there.

Limits: the proxy handles HTTP/1.1 request/response only (no WebSocket, no SSE streaming), keeps the last 500
captures in memory, and shows bodies as text up to 256 KB (gzip is decoded; brotli and binary show as sizes).

## Data

Everything is plain files under `~/.salt`, with one directory per section:

```
~/.salt/<section>/settings.json   user settings (edited in the UI)
~/.salt/<section>/sdata/          system data; network keeps its CA (ca.p12, ca.pem) and saved requests here
```

The CA private key never leaves this machine. Delete `~/.salt/network/sdata/ca.*` to regenerate it
(then re-install it on your devices).

## Develop

```sh
./gradlew :shared:jvmTest :server:test     # unit tests
```

- Add an Android feature: add the adb arguments and parser to `shared/.../AndroidActions.kt` (with a test),
  then a screen in `ui/AndroidSection.kt`. The server needs no change.
- Add a section: declare it in `Sections.kt` (id, title, settings schema). It gets a Settings tab and its
  `~/.salt/<id>/` directory automatically.
- UI code uses only `salt.ui.design` (theme, tokens, components), never Material directly.
