# Cockpit Dashboard

A standalone, remote-first Android large-screen client connected to **one
ordinary Copilot session** through Cockpit's public APIs. This is an Android
application, not a Cockpit module and not an Assistant client.

## Interaction

The landscape screen contains the session's conversation, current question,
draft and essential connection/recording status. No topic picker or separate
answer mode is involved.

| Remote key | Action |
| --- | --- |
| Hold confirm (`DPAD_CENTER`, `ENTER`, `NUMPAD_ENTER`) | Capture from a detected USB audio input. Wait for the actual recording indicator. Repeats do not restart capture. |
| Release confirm | Stop capture and transcribe; **never send automatically**. |
| Left | Send an available draft once to the configured session. |
| Right | Discard this draft/recording, not an already accepted task. |
| Up / down | Scroll chat. At the top, another up press loads older history. |
| Back / menu | Return, reconnect, connection settings or exit. |

The same input answers the session's current native `ask` when one was present
at recording start. The original request identity is retained through preview.
Stale questions cannot silently turn into prompts. Native choice-only questions
require speaking an exact offered answer; the app does not guess or relax the
backend's free-text policy. Native plan/elicitation requests and multiple
simultaneous native decisions must be handled in Cockpit; the app never
automatically approves them. Ordinary conversational clarifications require no
special handling.

Recording stops and is discarded on focus loss, leaving the foreground or
cancellation. Maximum capture length is 120 seconds. Audio exists only in memory,
not in files or backups. Transcription happens after capture, not live while
speaking. A transcription failure requires a new recording. Empty text cannot
be sent. Right cancellation cannot undo already incurred Azure processing/cost.

## Requirements and connection

* Android 6.0 / API 23 or newer with working Android USB audio input routing,
  mono PCM16 capture at 24 kHz, microphone permission and network access.
* A reachable **authenticated HTTPS gateway** in front of Cockpit, with a
  normally trusted certificate. HTTP and certificate-verification bypasses
  are deliberately unsupported.
* One existing ordinary session. Configure its ID from the Cockpit session URL.
  The app neither creates nor manages sessions.
* The existing `cockpit-speech` module configured by its operator for Azure
  OpenAI transcription. No Google recognition service is required.

On first launch, enter the HTTPS root URL, session ID and, if applicable, the
gateway's `Authorization` value (`Basic ...` or `Bearer ...`). This is **not**
the GitHub/Copilot token. The app does not implement a browser passkey/cookie
login flow. If the existing gateway only supports browser authentication,
operator-provided native-client authentication is a prerequisite; do not expose
an unauthenticated Cockpit port to work around it.

Settings and retained draft/uncertain-send state are encrypted with an
Android Keystore AES-GCM key. Android backup and screenshots are disabled.
Speech's Azure credentials are short-lived and kept in memory. No service
URL, real credential, transcript or recording is bundled in the APK.

USB presence alone is insufficient: capture must actually route to the selected
USB input. The app must not silently use a built-in or remote microphone.
The intended device is an XGIMI Z7X with a USB DJI receiver, but **this is not a
hardware compatibility claim**. GMUI firmware labels do not establish Android
API level. USB routing, 24 kHz support, remote key events, runtime permissions
and sideloading require acceptance on the real device.

## Public API contract

The client checks `/capabilities?name=...` for `session/get`, `session/chat`,
`prompt` and `respondAsk`. It uses their corresponding `/intent/...` JSON
transports, not private stores or databases.

History bootstraps with one bounded backward native event page. A loaded session
provides a `liveCursor`; subsequent forward reads preserve source, primary-agent
scope and event filters, with ephemeral deltas enabled. The app polls this
incremental API while visible, rather than inventing an SSE contract. Event IDs
deduplicate overlap; native message IDs let complete assistant messages replace
deltas. Older history uses its separate backward cursor. Unloaded history is
read passively; explicitly sending a prompt lets the host resume that existing
session. Cursor expiry/disconnection requires explicit reconnect, never resend.
Transient deltas missed during disconnect are not guaranteed to be recovered.

Only user/assistant messages and session errors are shown. Tool traces and
subagent messages are not a second conversation. Attachments are noted, not
downloaded. Markdown and URLs remain readable; this is not the full Cockpit
workbench. Native history, not local optimistic bubbles, determines chat content.

`prompt` uses `mode:"enqueue"`. An accepted or queued receipt is not completion.
The client persists a sending marker **before** mutation and never automatically
retries a mutation. Connection loss, missing receipt or process death can leave
an **unknown** result: inspect the original session in Cockpit before discarding
the local draft. Reconnect does not resend. Clearing a local uncertain draft
cannot retract the original operation. No host-side idempotency layer is added.

Speech is discovered through `/_modules`: its digest-bound `apiBase` is validated
as same-origin and used for `POST /session` with `{}`. No digest is hardcoded.
Audio is then sent directly to the approved Azure endpoint using its short-lived
credential; the Cockpit gateway credential is never forwarded to Azure.

## Build and sideload

Use JDK 17 and Android SDK platform 35. The pinned Gradle wrapper verifies its
distribution checksum:

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Version `0.1.0` / version code `1`, application ID
`io.github.waksana.cockpitdashboard`. The output is a **debug-signed sideload
APK**, not a store release or a user-signed production build. It appears in
both ordinary and TV launchers. A different debug signing key may require
uninstalling an older build, which removes local configuration/drafts.
CI uploads a debug APK artifact, without publishing a tag or GitHub Release.

Tests use synthetic messages and credentials only. They cover remote
down/repeat/up, explicit send/discard, empty transcripts, unknown results,
native ask identity, discovery, incremental history and audio/transcription
boundaries. Activity tests use Robolectric, not a booted Android emulator.
They do not establish real projector/receiver compatibility or a successful
connection to a user's authenticated gateway and Azure deployment.

Before relying on the app on hardware, confirm: the displayed Android API;
microphone permission; USB detection and real routing; ordinary/long confirm
key behavior; release does not send; left sends once; right cancels; background
and USB unplug stop capture; reconnect/process recreation preserve drafts and
do not resend. Real-device installation is a separate operator action.

Implementation is tracked in [issue #1](https://github.com/waksana/cockpit-dashboard/issues/1).
