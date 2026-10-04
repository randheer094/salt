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

## Install and run

```sh
./install.sh          # build, install to ~/.local/share/salt, link ~/.local/bin/salt
salt                  # start the server
./install.sh --dev    # build and run from this checkout, data in ~/.salt_dev
```

Open **http://127.0.0.1:8080**.

The server binds to loopback only. It can run arbitrary adb commands, so don't expose it.

| Environment variable | Default | Meaning |
|---|---|---|
| `SALT_PORT` | `8080` | Port of the web UI and API |
| `SALT_HOME` | `~/.salt` | Where settings and data live |

`./install.sh --dev` runs `./gradlew :server:run` with `SALT_HOME=~/.salt_dev`, so it never touches your installed
data. The UI is rebuilt and bundled on every server build, so UI changes need a restart.

## Using it

**Android**
- **Devices**: pick a device (a lone online device is picked for you). Pair and connect over Wi-Fi. **Virtual devices**
  (collapsed): create from a profile, start, stop, delete emulators.
- **Apps**: install an APK, launch, stop, clear data, uninstall. **Open a link** (collapsed): open any URI on the
  device, optionally restricted to one package.
- **Screen**: screenshot (or live view), **Save** a PNG to `~/.salt/android/screenshots/`, navigation keys, type text.
  **Interact** turns clicks into taps, long presses and drags into swipes on the device. **Open scrcpy** launches the
  scrcpy window for the device (install scrcpy; see Android → Settings for the path it resolved).
  **Inspect layout** is a layout inspector: it reads the view hierarchy (`uiautomator dump`), outlines the view you
  click on the screen, and shows the tree plus the selected view's bounds, id, text, class and flags.
- **Logcat**: filter and colour-coded levels.
- **Controls**: developer switches that show the device's current state (Wi-Fi, dark mode, animations, show taps, …),
  font scale, reboot. **Diagnostics** (collapsed):
  one-tap battery, memory, foreground activity, network, storage reports.
- **SDK**: update, remove, search and install packages.

**Network**
1. Check the proxy port and **Decrypt HTTPS** in Network → Settings (default port 9090).
2. Network → Traffic → **Start proxy**.
3. Select a device (Android → Devices), then **Point device at proxy**. This uses `adb reverse`, so it works on
   emulators and USB devices. **Reset device proxy** undoes it.
4. For HTTPS, press **Push CA to device**, then on the device install `salt-ca.crt` from Settings → Security →
   Install a certificate → CA certificate. Apps targeting Android 7+ ignore user CAs unless their debug build
   opts in (`networkSecurityConfig` trusting `user` certs). Pinned apps will show a TLS error in the capture list.
   GraphQL is recognised automatically (JSON POST, batches, `application/graphql`, GET `?query=`, persisted queries):
   the list shows the operation name and type instead of the shared URL, flags responses whose `errors` came back
   with HTTP 200, and the detail pane shows the formatted query, variables, and each operation's data and errors.
   Filter by operation name, or tick **GraphQL only**. Subscriptions over WebSocket are not captured.
5. Click a capture to see its response. The same pane is an editor for that request: change the method, URL,
   headers or body and press **Send**. A resend is logged as a new entry and selected, so you can compare it with
   the original. **New request** starts from a blank one. **Copy as cURL** puts the request on the clipboard,
   **Import cURL** (or pasting a `curl` command into the URL box) fills the editor from one, and **Mock this** turns
   the response into a rule.

Traffic is a log of the last 24 hours. It is stored on disk, so it survives restarts, and **Clear log** deletes it.

The editor works like Postman's request builder:
- **Environments**: named sets of variables; use `{{name}}` in the URL, headers or body. Undefined ones are sent as
  typed and flagged.
- **Pre-request script** and **Tests**: JavaScript with a `pm` object (`pm.environment`, `pm.variables`, `pm.request`,
  `pm.response`, `pm.test`, `pm.expect`, `console.log`). Scripts run in your browser tab, so they can change the
  request and variables before sending, and assert on the response afterwards. Snippet buttons insert common ones.
  Not supported: `pm.sendRequest`, async tests, `require`. Globals and collection variables are the active environment.
- Saved requests keep their scripts. Selecting another capture keeps the current scripts, so one set of tests can
  check a series of requests.

**Mocks** are proxy rules, checked top to bottom, first enabled match wins, applied to the running proxy at once:
- **Mock the response**: answer locally with your status, headers and body, optionally after a delay.
- **Send to another host**: reroute to staging or localhost; a path in the target replaces the original path.
- **Block**: answer 403 without contacting the server.
- Match on method, a URL with `*` wildcards, a **GraphQL operation name** (so one `/graphql` URL can be mocked per
  operation), and text in the request body. Rule hits show a *mocked* badge in Traffic. The check box under the
  list tells you which rule catches a URL. HTTPS traffic needs Decrypt HTTPS.

Limits: the proxy handles HTTP/1.1 request/response only (no WebSocket, no SSE streaming), and bodies are kept as
text up to 256 KB (gzip is decoded; brotli and binary show as sizes). The list holds a small row per request and
loads the full request and response only when you select one, so a day of traffic stays responsive.

## Data

Everything is plain files under `~/.salt` (`~/.salt_dev` with `install.sh --dev`), with one directory per section:

```
~/.salt/<section>/settings.json   user settings (edited in the UI)
~/.salt/<section>/sdata/          system data; network keeps its CA (ca.p12, ca.pem) and saved requests here
~/.salt/network/sdata/traffic/    the 24 hour traffic log (JSON lines, one file per hour; older files are deleted)
~/.salt/android/screenshots/      output you asked for: saved screenshots (output lives beside sdata/, not inside it)
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
