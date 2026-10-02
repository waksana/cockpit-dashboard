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
| Left | Cancel the current recording segment, or discard a completed draft; never cancel an already accepted task. |
| Right | Send an available draft once to its original destination. |
| Up / down | Select options in the current question at the bottom of chat, or scroll chat. Moving above the first option returns to chat scrolling; at the top, another up press loads older history. |
| Short confirm on a selected option | Preview the exact option as a draft, without sending. Hold instead to record. |
| Short confirm in message content, without a draft | Enter link/table reading. Up/down selects controls, short confirm opens the selected link, left/right scrolls its table, and Back leaves reading. Holding confirm still records instead of activating a link. |
| Back / menu | Return, reconnect, choose a session, advanced settings or exit. |

The same input answers the session's current native `ask` when one was present
at recording start. The original request identity is retained through preview.
Stale questions cannot silently turn into prompts. Native choice-only questions
accept an exact offered option selected with the remote/touch, or spoken verbatim;
the app does not guess or relax the backend's free-text policy. Native plan/elicitation requests and multiple
simultaneous native decisions must be handled in Cockpit; the app never
automatically approves them. Ordinary conversational clarifications require no
special handling.

Recording stops on focus loss, leaving the foreground or cancellation.
Holding confirm again with an existing draft appends a new transcription segment
with a newline, preserving manually edited text and the original answer target.
Tap the draft to edit it. Cancellation, failures and empty results retain the
previous draft; repeat/final/late callbacks cannot append a segment twice.
Maximum capture length is 120 seconds. Audio exists only in memory,
not in files or backups. Transcription happens after capture, not live while
speaking. A transcription failure requires a new recording. Empty text cannot
be sent. Left cancellation cannot undo already incurred Azure processing/cost.

The chat footer only shows essential state and failures, without expandable
tool/thinking details. Permanent key instructions and routine update messages
live under **Settings > Operation instructions and diagnostics** instead of chat.
There is no visibility toggle. Update diagnostics retain at most 16 entries of
600 characters in the existing encrypted store, never audio or message bodies.

## Requirements and connection

* Android 6.0 / API 23 or newer with working Android USB audio input routing,
  mono PCM16 capture at 24 kHz, microphone permission and network access.
* A reachable **authenticated HTTPS gateway** in front of Cockpit, with a
  normally trusted certificate. HTTP and certificate-verification bypasses
  are deliberately unsupported.
* An existing session chosen from the session directory using the remote.
  The app neither creates nor manages sessions.
* The existing `cockpit-speech` module configured by its operator for Azure
  OpenAI transcription. No Google recognition service is required.

On first launch enter your Cockpit hostname once, such as `cockpit.example.com`.
The app defaults to HTTPS and saves the address locally; no personal host is
bundled in the source or APK. Select **Save and sign in with QR**, scan with a
phone and approve with its existing Passkey, then use
up/down and confirm on the remote to select a session. No session ID typing is
required. The chooser only displays sessions whose configured `roles` include
`moduleId: "assistant", roleId: "coordinator"` (the catalog's **Assistant** role),
regardless of loaded state or status. Other roles from the assistant module,
such as organizer or legacy memory, do not qualify.
Missing roles, `appliedRoles` alone, display names and assistant-authored messages
do not qualify. There is no show-all option or automatic session selection.
Titles, working directories and IDs distinguish similarly named entries.
Next/previous controls traverse matching directory pages. Unmatched raw pages are
skipped automatically, up to 20 requests of 50 entries per search action; if that
budget is reached before a match or the end, **Continue searching** explicitly
resumes from the returned cursor without claiming no matches exist.
Cancelling or leaving the foreground prevents further page requests and ignores
the in-flight result. Listing
does not load sessions or read their chats. A changed catalog invalidates its
cursor; explicitly refresh rather than silently skipping entries.

The selected session and login are saved encrypted and reused after restarting.
Use **Choose session** in the menu to change it. Resolve/discard a draft or an
uncertain send before switching; old text must never move to another destination.
The filter does not replace a saved selection or confer session access permission.
**Advanced connection settings** optionally changes the HTTPS root and gateway
`Authorization` (`Basic ...` or `Bearer ...`, not the GitHub/Copilot token).
Changing the site clears the old selection and all old credentials (device tokens,
cookies and Authorization). For a new site's manual Authorization, save its hostname
first and then reopen advanced settings. Do not expose an
unauthenticated Cockpit port to bypass missing provider or gateway setup.

Settings and retained draft/uncertain-send state are encrypted with an
Android Keystore AES-GCM key. Android backup and screenshots are disabled.
Speech's Azure credentials are short-lived and kept in memory. No personal service
URL, real credential, transcript or recording is bundled in the APK.

USB presence alone is insufficient: capture must actually route to the selected
USB input. The app must not silently use a built-in or remote microphone.
The intended device is an XGIMI Z7X with a USB DJI receiver, but **this is not a
hardware compatibility claim**. GMUI firmware labels do not establish Android
API level. USB routing, 24 kHz support, remote key events, runtime permissions
and sideloading require acceptance on the real device.

## Device QR authorization

The default sign-in uses RFC 8628 device authorization. The TV does not need
Credential Manager, a passkey provider, Google Play services or a WebView.
After an explicit sign-in action, the app posts form-encoded `client_id=cockpit-dashboard`
and `scope=host-access` to the entered HTTPS origin's
`/_gate/oauth/device_authorization`. The gateway returns a short-lived device
code, user code and absolute HTTPS verification URLs on its configured Auth site.
ZXing renders `verification_uri_complete` locally; no public QR service receives
the URL. The QR contains the user code, never the device code or tokens. If the
optional complete URI is absent, it opens the verification page and the user
enters the displayed code instead.
The screen also shows `user_code`, `verification_uri`, a countdown and cancellation.
On the phone, check the site and matching code before approving with Passkey.

Polling uses `/_gate/oauth/token` on the original Cockpit origin, never an
endpoint derived from the QR. Requests use `client_id` and
`grant_type=urn:ietf:params:oauth:grant-type:device_code` with `device_code`.
The default interval is five seconds; `slow_down` permanently adds five seconds,
and connection timeouts exponentially reduce polling frequency. Pending responses
do not constitute login. Only a validated Bearer token response grants access.
Denial, expiry, invalid/unknown errors and other network/protocol failures stop
the attempt. Leaving the foreground or cancelling also abandons the attempt;
returning does not automatically issue another device code. Late callbacks cannot
restore a cancelled attempt. If approval occurred but its result was lost, inspect
or revoke the device in the Auth page before starting another attempt.
Gateway rate limits (HTTP 429, normally `Retry-After: 60`) and temporary capacity
failures (HTTP 503) stop explicitly rather than silently retrying issuance or
rotation. The sign-in screen displays a bounded retry-after hint when supplied;
starting another grant still requires a new user action.

Device tokens are stored in a separate payload of the same Keystore-encrypted
`PrivateStore`, so refresh cannot overwrite drafts or uncertain-send markers.
Cockpit requests carry an exact-origin-bound Bearer access token. Refresh begins
when at most 60 seconds remain, with one in-process request shared across callers
and activity recreation. A durable refresh-in-progress marker is committed before
the rotating refresh token is sent. Lost responses, process death or storage
uncertainty require a new QR login; the old refresh token is never blindly replayed.
Refresh does not retry `prompt` or `respondAsk`, discard a draft, change the original
ask target, or clear an UNKNOWN result. A pending send blocks login changes;
drafts/UNKNOWN allow reauthentication only, not a host/session switch.

The v1 gateway defaults are a 300-second device code, 900-second access token
and a device-family absolute maximum of 30 days; activity/rotation cannot extend
that server maximum. This is **host-access authorization**, not per-session
permission isolation. The chooser filters configured assistant roles locally. A successful
login opens selection when there is no draft, otherwise retains the original target.
Existing manually configured Authorization/native cookies are replaced only after
successful device login; a failed attempt leaves them intact.

**Close app (keep login)** retains valid credentials. **Sign out (this device only)**
removes local credentials but does not claim remote revocation. **Revoke this device**
requires confirmation and posts `token`, `token_type_hint=refresh_token` and
`client_id` to `/_gate/oauth/revoke`. HTTP 200 acknowledges the RFC 7009 request
even for an unknown token. Failure is reported as unconfirmed, not remote success;
local sign-out and the Auth page's remote revocation remain available. These actions
retain drafts and do not retract messages. Neither GitHub nor Azure requests receive
device credentials. There is no client secret in the APK.

The synthetic contract fixture is `app/src/test/resources/device-oauth-v1.json`.
`app/src/test/resources/gate-device-wire-v1.json` is copied without modification
from [Passkey Gate's actual wire fixture at ae049b3](https://github.com/waksana/passkey-gate/blob/ae049b319da8bac212c99ec66b943ff77d55c5b3/testdata/device-wire-v1.json);
its provenance remains that repository. App tests consume its real response and
request shapes through TLS MockWebServer, including remaining-family lifetimes,
terminal errors and 429/503. These are public synthetic examples, not credentials.
Local/JVM coverage does not establish real gateway deployment, phone approval,
projector display/scan compatibility or successful authentication.

## Native Passkey compatibility trial

The advanced menu retains Android Credential Manager as an optional compatibility
path, separate from the default device QR flow. It requests an existing passkey
from the configured gateway; it never registers one. The system may offer
"another phone or tablet" and a QR code, but **QR availability is not guaranteed**.
Native passkeys require Android 9/API 28+ and a compatible credential provider.
Google Play services is a supported provider path, not evidence that GMUI ships
it. The app reports unavailable providers, cancellation and authentication errors
without silently changing authentication methods or retrying assertions.

The operator must first deploy a compatible
[Passkey Gate](https://github.com/waksana/passkey-gate#android-login), enable this
APK's exact Android signing origin **only for the configured Cockpit host**, and
publish Digital Asset Links at the gateway's RP ID domain. The association uses
the application ID and signing **certificate** fingerprint, not the APK hash.
Browser-only gateway configuration is insufficient. The trial does not deploy
those changes. Use a trusted HTTPS root on port 443.

The app obtains options from `/_gate/auth/options`, passes `publicKey` unchanged
to Credential Manager, and posts the returned assertion to `/_gate/auth/finish`.
Client/flow cookies are kept only for that attempt. The resulting host-only
`__Host-pg_session` cookie and absolute expiry are saved in the existing
Android Keystore-encrypted state. No cookie is forwarded to Azure or another
host, scheme or port. Successful Passkey login replaces local Authorization.

**Closing the app, reopening it, or rebooting the projector retains a valid
login.** Expiration or server-side revocation prompts explicit login again;
activity does not extend the server's fixed lifetime. The menu distinguishes
closing the app from **Sign out (this device only)**. Sign-out clears the saved
cookie and Authorization but retains settings, drafts and uncertain-send state.
It does not claim server-side revocation: use the gateway's management site to
revoke sessions. Changing the site clears the old cookie. Cancellation and
process destruction abandon the login attempt, never a chat mutation.

The intended acceptance device is XGIMI Z7X / GMUI 6
`6.11.101.01_499`. Installation, the native prompt/QR, phone approval, actual
gateway login, restart persistence and revocation must be confirmed there.
JVM tests do not prove any of those real-device outcomes.

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

User/assistant messages, user-facing questions/answers and session errors are
shown; generic tool traces, logs, thinking and child-agent messages are hidden.
Attachments are noted, not downloaded. Markwon renders the same Markdown path
for live/replayed messages and questions: headings, lists, quotes, emphasis,
code, safe explicit web links and horizontally scrollable tables. Long code
wraps; HTML stays inert and images never trigger automatic downloads.
This is not the full Cockpit workbench. Native history, not local optimistic
bubbles, determines chat content.

Historical `ask_user` questions and choices come from attributed
`tool.execution_start`, embedded assistant `toolRequests`, or native request
metadata. Actual answers come from the matching durable `tool.execution_complete`
and its affirmative `User selected:` / `User responded:` result, with failure,
dismissal and outcome guards. Ephemeral `user_input.completed`, local submit
receipts and disappearing pending controls are not saved-answer evidence.
Earlier pages can repair a question whose completion arrived first; event IDs
deduplicate overlap and exact invocation identities prevent cross-question
matching. Missing answers stay unconfirmed, never synthesized or retried.
This follows the public Web reducer and synthetic tests at
[Cockpit 627270a](https://github.com/waksana/cockpit/blob/627270a248f634e977dd96fd8ee2d8c70d1288fc/packages/protocol/src/chat.ts).

History is read-only. Only current metadata supplies an actionable question;
an explicit `decisions` array supersedes the legacy singular `ask`, including
when empty. Host callback request IDs are not native tool-call IDs, so the app
does not match historical and pending cards by text, time or assumed ID aliases.
Web-origin answers use the same durable result path. Old records without
attributable results cannot be recovered by inventing an answer.

`prompt` uses `mode:"enqueue"`. An accepted or queued receipt is not completion.
The client persists a sending marker **before** mutation and never automatically
retries a mutation. Connection loss, missing receipt or process death can leave
an **unknown** result: inspect the original session in Cockpit before discarding
the local draft. Reconnect does not resend. Clearing a local uncertain draft
cannot retract the original operation. No host-side idempotency layer is added.

Speech is discovered through `/_modules`: its digest-bound `apiBase` is validated
as same-origin and used for `POST /session` with `{}`. No digest is hardcoded.
Speech credential requests bind the discovered `digest` to its exact `apiBase`
and send `X-Cockpit-Module-Digest` on that module POST only. A module HTTP 409
is reported as a module request conflict, not as expired gateway authentication.
The next explicit recording attempt discovers the module again; requests and
recordings are never automatically replayed on a conflict.

Audio is then sent directly to the approved Azure endpoint using its short-lived
credential; the Cockpit gateway credential is never forwarded to Azure.

## Build and sideload

Use JDK 17 and Android SDK platform 35. The pinned Gradle wrapper verifies its
distribution checksum:

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The local default is version `0.3.2` / version code `10002`, application ID
`io.github.waksana.cockpitdashboard`. It appears in ordinary and TV launchers.
The command above produces a **debug** APK for development, not an update for
the release-signed app. The first switch from an earlier debug trial to the
independent release signer requires uninstalling the old app. This deletes its
local login, settings and drafts; inspect uncertain sends in Cockpit first.
Subsequent same-signer updates preserve app data, subject to server login expiry.

## Signed updates and release operations

The app checks the repository's latest stable GitHub Release once per activity
launch; **Check app updates** in the menu explicitly retries. New-version prompts
wait until the app is foreground and not recording, sending, logging in or showing
another dialog. Downloads are private and cancelled on leaving the activity.
The updater accepts only the canonical `cockpit-dashboard.apk` and `update.json`
assets. It validates bounded sizes, SHA-256, package name, increasing integer
version code, version name, minimum SDK and the exact installed single signing
certificate. Signing rotation and downgrades are not supported. Public GitHub
traffic has a separate client and carries no Cockpit credentials.

Failures remain visible as a short chat notice. **Operation instructions and
diagnostics** shows the fixed stage/reason code and relevant numeric facts,
and the updater writes the same sanitized diagnostic under the Android log tag
`DashboardUpdater`. Stages distinguish release/manifest requests, APK download
and storage, file hash/size, package parsing, version/minimum API, installed/APK
signers, and launching system settings or the installer. The diagnostic includes
the device's Android API and target version code when known; HTTP failures include
the status code. Photograph the settings diagnostic for troubleshooting; ADB is not required.
Logs omit response bodies, URLs, credentials, exception text and stack traces.
Cancellation does not emit a late failure log. These diagnostics do not establish
the cause of a previously observed device failure.

Confirm downloading, then confirm opening Android's installer. Android 8+ may
first require allowing this app to install unknown apps; returning from settings
still requires confirmation. This is not a silent installer and an installer
launch is not proof that installation succeeded. Firmware without a usable
installer needs manual sideloading. No APK Release exists merely because this
workflow source is present.

`.github/workflows/android.yml` tests/lints/builds PRs without signing secrets.
Future pushes to `main` additionally build a signed release after those checks.
The version is `0.3.<github.run_number>`, code `10000 + github.run_number`; do not
reset this workflow's numbering or manually publish conflicting versions.
Publication is serialized, refuses obsolete main commits and never overwrites
an existing release. It first uploads both assets to a draft and then publishes
it as latest. A failed draft or existing tag requires operator inspection, not
blind rerunning or overwriting assets. A newer main push produces a new version.

Before enabling publication, the operator must back up the **existing release
keystore** and password securely, and configure repository Actions Secrets:

| Secret | Value |
| --- | --- |
| `DASHBOARD_KEYSTORE_BASE64` | Base64 bytes of the existing release keystore |
| `DASHBOARD_STORE_PASSWORD` | Its store password |
| `DASHBOARD_KEY_ALIAS` | Its signing alias (`dashboard` for the initial signer) |
| `DASHBOARD_KEY_PASSWORD` | Its private-key password |

Never generate a replacement key for each build. `release-signing.sha256` pins
the public certificate hash; `scripts/release-metadata.mjs` independently verifies
the signed APK before producing release assets. Missing secrets or a different
certificate fail publishing, rather than falling back to unsigned/debug output.
Private keys/passwords must never enter commits, logs, artifacts or Releases.
This delivery does not configure Secrets, merge, create a Release or deploy.

For a local release, set `DASHBOARD_KEYSTORE` to the protected keystore path and
the three password/alias environment variables above, then run:

```sh
./gradlew --no-daemon assembleRelease
mkdir -p release-output
DASHBOARD_VERSION_CODE=10002 DASHBOARD_VERSION_NAME=0.3.2 \
  node scripts/release-metadata.mjs app/build/outputs/apk/release/app-release.apk \
  "$ANDROID_HOME/build-tools/35.0.0/aapt" "$ANDROID_HOME/build-tools/35.0.0/apksigner" release-output
```

The initial release signing certificate SHA-256 is:
`4B:EF:7D:18:2C:F4:62:39:10:E7:18:5B:B3:F5:D7:39:DF:03:00:08:14:E3:E8:67:D2:63:A7:2C:9F:39:73:AA`.
Its Android origin is
`android:apk-key-hash:S-99GCz0YjkQ5xhbs_XXOd8DAAgU4-hn0mOnLJ85c6o`.
Update both the gateway's per-host Android allowlist and RP-domain Digital Asset
Links to this identity before real login. These are public certificate facts,
not the private signing key or a personal gateway address.

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
