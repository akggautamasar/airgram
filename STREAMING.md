# AirGram streaming: audit, design, limitations, tests

## 1. Audit of the pre-change implementation (gaps found)

Path traced: `FilesScreen` → `MainActivity` → `PlayerScreen` (ExoPlayer) → `TdDataSource` →
`Tg.download/cachedFile` → TDLib `DownloadFile` / `UpdateFile`.

What already worked and was preserved: windowed TDLib downloads (no full download before
playback), login/chats/search/pagination, per-file resume positions, PDF via `PdfRenderer`,
photos at bounded resolution, theme/settings persistence, applicationId `com.airbooks.app`.

Gaps fixed:

1. `TdDataSource.ensureWindow()` re-issued `DownloadFile` with no range de-duplication; window
   size was a single user slider for every network.
2. `close()` cancelled the TDLib download unconditionally — a second reader of the same file
   (e.g. thumbnail + player) lost its window. **No reference counting existed.**
3. Stall handling: flat 3 s re-kick and a fixed 120 s timeout — request-storm prone, no backoff,
   no distinction between Normal / Low Data behaviour.
4. No throughput estimation, no playback policy, hard-coded `LoadControl` (20 s/90 s/2.5 s/5 s).
5. No streaming modes at all; the "prefetch" slider was the only control.
6. Settings contained decorative rows (player engine, video decoder, community, legal…) that
   changed nothing in playback.
7. Audio used the video screen: no queue, no artist/title/artwork metadata, no background
   playback / media session (README listed this as a known limit).
8. Unknown file size was fatal on first read; expired file references were not refreshed.
9. No automated tests anywhere; CI only assembled the APK.

Key API fact that drives the design: **TDLib has no HTTP-Range equivalent.** `DownloadFile`
fetches one `(offset, limit)` window and `TdApi.LocalFile` exposes a single contiguous prefix
(`downloadOffset + downloadedPrefixSize`). So "range requests" in AirGram mean *moving that one
window*, and a seek necessarily re-requests bytes at the new position. Bytes served are always
the correct bytes for the requested position; only readahead from a previous window position is
not randomly addressable.

## 2. What was implemented

- **`stream/Streaming.kt`** (pure Kotlin, JVM-testable): `StreamingMode`, `StreamingConfig`
  (all thresholds in one place), EWMA `ThroughputEstimator` with clamped samples,
  `RangeTracker` (de-duplicates identical ranges, re-plans on seek, single source of truth for
  the TDLib window), bounded exponential `Backoff`, and a stateful `PlaybackPolicy` with
  throughput bands + hysteresis that outputs buffer durations, prefetch window, retry caps and
  a human-readable explanation. It never claims variant switching: `preferLowerVariant` is only
  true when a lower-quality variant genuinely exists (Telegram messages here have one file).
- **`tg/TdDataSource.kt`** rewritten: policy-sized windows (live mode read on every request, so
  the in-player Normal/Low Data selector changes real network behaviour without restarting),
  de-duplicated ranges, bounded-backoff stall recovery with mode-dependent timeouts, throughput
  sampling into `StreamingRuntime`, one-shot metadata refresh for unknown size/expired refs,
  EOF/out-of-range handling, cancellation via a volatile closed flag, and reference-counted
  download lifetime (`Tg.beginStream/endStream`) so concurrent readers are safe. Requests always
  carry an explicit `limit` — streaming never starts a competing full-file download, and nothing
  is buffered wholesale in RAM (one `RandomAccessFile`, ExoPlayer-sized chunks).
- **Modes** (all three selectable in the player and in Settings → Default video mode, persisted
  by enum name, unknown values fall back to Normal):
  - **Normal** = up to 32 MB window, 15–25 s min buffer / 90–120 s max, 1.5–2.5 s to start,
    bounded pause-prebuffer of up to 24 MB (or the user's read-ahead cap, whichever is smaller).
  - **Low Data** = 2–8 MB window, smaller start buffer, longer stall tolerance, fewer retries with
    longer backoff, background prefetch and (while playing) thumbnail work suspended, bounded
    rebuffer protection that stops growing after 4 rebuffers, small 6 MB pause-prebuffer target.
  - **Extreme Low Data** (continuity over startup, for very slow/unstable/high-latency links) =
    12–48 MB windows, 30–50 s min buffer / 180–240 s max, playback **starts only with 6–10 s
    already buffered** (service-level start gate), most patient recovery (6 retries, 1.5 s base
    backoff, 150 s stall timeout), thumbnails/artwork/speculative requests suspended, and the
    largest pause-prebuffer target (up to 96 MB / 300 s of media, adapted down by measured
    throughput and file size). Pause-prebuffering is ALWAYS allowed in this mode — that is the
    point of it.
- **`playback/PlaybackService.kt`**: a `MediaSessionService` owning the single ExoPlayer;
  the UI uses a `MediaController`. Audio now plays in the background with a media notification,
  queue (previous/next), title/artist/album-cover (from Telegram audio metadata +
  minithumbnail), and resume. Video leaving the screen stops playback; audio does not. A ~1 Hz
  service ticker runs the two behaviours `LoadControl` cannot express live: the **Extreme Low
  Data start gate** (internal hold — see §2.1) and the **pause-to-buffer pump** dispatch. It
  distinguishes an intentional user pause (`playWhenReady=false` not applied by the gate) from
  an internal buffering hold, and never auto-resumes a user pause.
- **Player UX**: immersive video, custom Compose controls for video AND audio with the
  three-track YouTube-style timeline (§2.1), play/pause + prev/next + elapsed/total,
  tap-to-toggle controls for video (audio keeps them visible); background playback, the media
  notification, headset controls and queue navigation remain on the MediaSession.
  Playback-speed menu (persisted), three mode selector chips, subtle "· mode" + buffered-seconds
  + estimated-kbps diagnostics, live "Prebuffered X MB ahead" / "Building buffer… +Ns" status,
  audio/subtitle/video track selection menu (real `TrackSelectionOverride`s), rotate + PiP
  buttons, sleep timer, mobile-data warning with one-tap switch to Low Data, error view that
  retries from the last position.
- **Settings → Streaming**: default mode, remember-last-mode, speed, read-ahead cap, Wi-Fi-only
  prefetch (blocks thumbnail/preview prefetch on metered links; playback still streams),
  mobile-data warning, suspend-thumbnails-in-Low-Data, max temporary cache with enforcement
  (at startup, on demand, after clear), clear-temporary-cache via TDLib `DeleteFile` (not
  deleting files under TDLib's feet), and a diagnostics dialog (mode, throughput estimate,
  rebuffers, last error, metered state, cache usage, paths, version). Decorative settings rows
  were removed; theme and thumbnail settings kept and working.
- **Media browser**: Videos/Audio/Photos/Documents tabs unchanged in data flow (real Telegram
  search + pagination), now with a grid/list toggle for every section, thumbnails, filename,
  size, duration, date and caption. Non-streamable files say so explicitly and only download
  after the user taps Download. PDFs still download-then-render (see limitations).

## 2.1 Pause-to-buffer, the start gate, and the YouTube-style loaded track

**Pause-to-buffer (all three modes).** ExoPlayer stops calling `read()` when the user pauses and
its own buffer is full, which used to freeze the TDLib window exactly where it stood. Now:

1. `TdDataSource` publishes TDLib's real contiguous prefix (`local.downloadOffset +
   downloadedPrefixSize`, whole file when complete) into `StreamingRuntime.loadedPrefix` on
   every read, every wait-loop iteration and every pump tick.
2. `PlaybackService` ticks ~1 Hz, classifies the pause (user vs. internal hold), and calls
   `TdDataSourceFactory.current?.prebufferTick(userPaused)`.
3. While the user has paused, the pump advances the window **from the bytes already downloaded**
   (`start = aligned max(readPos, prefixEnd)` — never backwards, never restarting the transfer)
   in limit-bounded `DownloadFile` chunks until the mode's `Prebuffer.targetBytes` is reached,
   then stops. If the buffer falls below the target (e.g. after a seek), it resumes fetching.
4. Requests are de-duplicated through the same `RangeTracker` the read path uses
   (`adoptOrPlan`), run at TDLib priority 20 — below active playback (32), interleaved with the
   download scheduler's 8–30 range — so queued user downloads keep their fair share of TDLib's
   byte budget while the player is paused. No competing full-file download is ever started:
   every request carries an explicit limit.
5. Targets are bounded and adapt: byte cap per mode (Low 6 MB < Normal 24 MB < Extreme 96 MB),
   converted to a time cap when the average media bitrate is known (player computes it from real
   size/duration), further cut to what the measured EWMA throughput can actually deliver, and
   clamped to the file size. Extreme prebuffers the most; Low Data the least; Normal stays
   responsive and honours the user's read-ahead cap.
6. The pump never touches playback state: a user-paused video is never auto-resumed.

**Extreme Low Data start gate.** `LoadControl` buffer durations are fixed when the service is
created, so live mode switches cannot change them. Instead, pure `StartGate` predicates
evaluated by the service ticker HOLD playback (internal pause, flagged as such) while the
buffer ahead of the playhead is below the mode's start target (6–10 s in Extreme), and release
it at the resume target (10–15 s+), at end-of-stream, when the user leaves Extreme mode, or
when the user explicitly presses play — the user always wins, and after an override the gate
stands down until the buffer has recovered to the resume target. Internal holds are tracked
separately from user pauses; a user pause always cancels the hold and hands over to the
pause-prebuffer pump.

**YouTube-style timeline (video).** The custom seek bar renders exactly three tracks from real
data: played (player position), **loaded** = `max(Media3 bufferedPosition, TDLib contiguous
prefix end mapped linearly to time via file size)`, and unloaded (the rest). Because the loaded
track reads `StreamingRuntime.loadedPrefix` at 4 Hz, it visibly grows while the video is paused
and the pump runs. It never fabricates ranges: TDLib exposes one contiguous prefix per file and
that is precisely what is drawn. The bar is tap- and drag-seekable (scrub locally, seek on
release), sits on the always-black player background, so its played/loaded/unloaded colours are
legible in both light and dark themes. **Audio uses the same custom controls and three-track
bar** (permanently visible under the artwork), so the loaded track grows during audio
pause-prebuffering too; background playback, the media notification, headset controls and queue
navigation stay on the MediaSession and are untouched by this.

**Session measurements (acceptance §6).** `PlaybackMetrics` (pure, JVM-tested) aggregates per
session: startup time (session start → first READY), cumulative BUFFERING duration, rebuffer
events (`StreamingRuntime.rebufferCount`), prebuffered seconds ahead (bytes × 8 ÷ average
bitrate; honestly −1 when the bitrate is unknown) and fetched bytes — the latter counted only
as genuine forward growth of the TDLib prefix, so it is a lower bound (window moves after seeks
can re-fetch small overlaps that are never double-counted). All five are shown in
Settings → Streaming → Diagnostics. What cannot be measured here: real-world startup/buffering
under throttled networks needs a device (see §5 manual script); and no mode promises zero
buffering when throughput is below the media bitrate.

**Audio-sized targets.** The time-cap adaptation makes pause-prebuffer targets
audio-appropriate automatically: at a measured 128 kbps the Extreme target is ~4.8 MB (300 s of
audio), Normal ~1.4 MB, Low Data ~0.5 MB — far below the video byte ceilings. The byte floors
(0.5/8/12 MB) only apply while neither bitrate nor throughput has been measured yet.

## 3. Limitations (honest list)

- TDLib's single-prefix window: seeking re-fetches at the new offset; previously fetched bytes
  at other offsets are kept by TDLib on disk but are not exposed as random-access ranges.
- No adaptive resolution/bitrate: a Telegram message contains one fixed-quality file, and the
  app does not pretend otherwise. `preferLowerVariant` activates only if variants ever exist.
- PDFs cannot be rendered by Android `PdfRenderer` from a partial file, so PDF preview still
  downloads the file (with progress) into the app cache — never into Downloads.
- LoadControl buffer durations are chosen when the playback service is created; the data-source
  window/backoff/stall behaviour follows live mode switches immediately, buffer durations take
  the new mode on the next service start. The Extreme start gate (service ticker) approximates
  the start/resume thresholds live for the held-start behaviour.
- The loaded track maps the downloaded BYTE prefix to time linearly (average bitrate). For
  variable-bitrate files the shaded region can be slightly ahead of/behind the true playable
  duration, but it never overstates available bytes.
- Pause-prebuffering extends the single TDLib window forward from the prefix end. On resume,
  ExoPlayer reads at the playhead; if the window has moved beyond it, the read path re-plans a
  window at the playhead and TDLib re-fetches the (bounded, ≤ one window) overlap — already
  fetched bytes ahead stay on disk but are not randomly addressable (single-prefix limitation).
- None of the three modes can promise zero buffering when network throughput is below the media
  bitrate; Extreme only maximises the runway before and between stalls.
- Codec support is the device's (Media3/ExoPlayer); some MKV/HEVC/AC3 files may not play.
  No transcoding, no VLC (per requirements).
- Background playback keeps audio after leaving the player; stop it from the notification.

## 4. Crash hardening and on-device diagnostics

A hard crash while opening a video was captured on-device by the crash handler described at
the end of this section, and traced to three failure classes:

- **Root cause (from the on-device stack trace):** `SessionToken(context, componentName)`
  threw `IllegalArgumentException` on the main thread every time the player opened, because
  `SessionToken` resolves the service with
  `queryIntentServices("androidx.media3.session.MediaSessionService")` while the manifest
  declared `androidx.media.session.MediaSessionService` (missing the `3`) plus a bogus
  `android.media.session.MediaSession` action. The manifest now declares the exact media3
  action, and `PlayerScreen` wraps token/controller creation in `runCatching` so any future
  resolution failure renders as an in-app error instead of killing the process.
- `DataSource.open/read` run on ExoPlayer's loader thread, where Media3 converts only
  `IOException` into a retryable `PlaybackException`; any other `Throwable` (e.g. a TDLib
  `TdException` from `GetFile`/`DownloadFile`, or an unexpected runtime failure) escapes as an
  uncaught exception and kills the whole process. `TdDataSource` now routes `open`/`read`
  through a `guard {}` that records the failure in `StreamingRuntime.lastError` and rethrows
  non-IO failures as `IOException`, so the player shows its error + Retry UI instead of dying.
  `close()` swallows teardown failures for the same reason.
- `DefaultLoadControl` with only duration caps can reserve unbounded heap while the media
  bitrate is unknown (Telegram reports none before extraction), which can OOM-kill the process
  while buffering a large high-bitrate file. The service now sets an explicit 48 MB
  `setTargetBufferBytes` ceiling and falls back to sane buffer durations if the policy input
  ever fails, so `onCreate` cannot take the service (and process) down.

Because Samsung/Android surfaces process death only as "this app has a bug", the app also
installs a `Thread.setDefaultUncaughtExceptionHandler` that writes the full stack trace to
`cacheDir/last_crash.txt`; on the next launch a dialog shows the trace (selectable, so it can be
screenshotted) with Dismiss/Keep. Any future crash is therefore diagnosable from a screenshot,
without logcat or a backend.

## 5. Tests

Automated (run in CI on every push, `:app:testReleaseUnitTest`, all passing):
`StreamingPolicyTest` covers estimator convergence/clamping, range de-duplication and
seek re-planning, bounded monotonic backoff, Low Data < Normal prefetch, Normal keeping the
largest window on fast links without faking variants, hysteresis (no band flapping), bounded
rebuffer protection with unknown metadata, and the user's window cap.
`ExtremeModeTest` covers the three-mode system: mode enum round-trip (persistence by name),
pause-prebuffer ordering (Low < Normal < Extreme) and boundedness, per-mode user-cap handling,
bitrate/throughput/file-size adaptation of targets, Extreme's continuity-over-startup policy
(deepest start/resume buffers, widest windows, background prefetch always on, even in the LOW
band), Extreme's most-patient-but-bounded backoff, `StartGate` hold/release semantics (holds
only in Extreme, releases on target/EOF/mode change), pump de-duplication (`adoptOrPlan`),
positive pause targets in every decision, and `LoadedPrefix` fraction clamping for the
seek-bar rendering.
`PlaybackMetricsTest` covers the acceptance §6 measurements: one-shot startup timing,
accumulated buffering across cycles without double-counting, session reset, forward-only byte
accounting, prebuffered-seconds conversion (honest −1 when the bitrate is unknown), and
audio-sized pause-prebuffer targets in all three modes (128 kbps → 0.5/1.4/4.8 MB).

Acceptance criteria that need a device + real Telegram account (cannot be reproduced in CI):
items 1, 2, 7, 10 (real network streaming/seek/recovery/audio), 12 (auth/search against a live
account), 13 (visual theme check), 14 (process-restart persistence is standard
SharedPreferences, exercised by the same code paths). Suggested manual script: play a large
video on throttled Wi-Fi, seek to 50%/10%, toggle Low Data mid-playback, enable airplane mode
for 10 s and recover, background an audio track, kill and relaunch to confirm resume + prefs.

Build verification: GitHub Actions "Build APK" on the latest commit of this branch — unit tests
+ `:app:assembleRelease` green, artifact `AirGram-apk` (~32 MB). Launcher label is `AirGram`
(manifest unchanged), applicationId unchanged (`com.airbooks.app`).

## 6. Player controls, gestures and lock

Everything that already worked is still there: the three streaming modes as chips, the
YouTube-style played/loaded/unloaded timeline fed by real Media3 `bufferedPosition` **and** the
real TDLib contiguous prefix (`StreamingRuntime.loadedPrefix`), pause-to-buffer status lines,
background audio through the media session, position restore and the Continue rail.

### Gesture layer

Pointer handling is split from playback logic on purpose:

* `ui/GestureMath.kt` (pure, no Compose/Android/player) decides *what a gesture means* — screen
  zones, drag→brightness/volume/seek mapping, seek clamping, option cycles, buffered percentage.
  `GestureAxisLock` is the state machine: the first movement past 1.5× the touch slop locks the
  gesture to the dominant axis and it cannot flip mid-drag.
* `GestureLayer` (in `PlayerScreen.kt`) only runs the pointer input and emits a sealed
  `PlayerGesture` intent; `handleGesture` in the screen executes it against the player.

Two detectors share one node: the drag machine runs first and **consumes** movement as soon as it
locks, which cancels the tap detector — so a swipe never also counts as a tap, and a tap never
triggers a seek.

| Gesture | Effect |
| --- | --- |
| Single tap, controls hidden | Reveals the controls — the first interaction never toggles playback |
| Single tap, centre (controls visible) | Play / pause, with a transient centred icon that fades out |
| Single tap, edge bands (controls visible) | Hides the controls again |
| Double tap, left / right band (outer 35%) | −10 s / +10 s |
| Double tap, centre | Play / pause |
| Vertical swipe, left band | Window brightness (never the persistent system value) |
| Vertical swipe, right band | Media stream volume only |
| Vertical swipe, centre | Nothing — no accidental effect |
| Horizontal swipe | Live seek preview (`» 12:34`), committed on release |
| Lock enabled | Gesture layer is not composed at all; a visible unlock button remains |

Swipe and seek read-outs are a transient overlay with a real icon (brightness, volume — including
a muted state, ±10 s, seek target), text and, for brightness/volume, a level bar; nothing is
reported that the gesture did not actually change.

Brightness is `window.attributes.screenBrightness`, so it applies to this activity only and is
restored to `-1` (follow the system) in `onDispose`. Volume uses `AudioManager.STREAM_MUSIC`
exclusively. **Audio playback gets no gesture layer**: there is no picture to dim, its controls are
always visible, and a swipe there would compete with them — the required "no brightness gesture for
audio" is met by not installing the layer rather than by special-casing it.

### Controls

* **Top row**: back, truncated title (tap → full-name dialog), live info line (buffered seconds,
  item size, measured throughput, mode), lock, and a More menu.
* **Scrollable action row**: the three streaming modes, rotate, PiP, aspect ratio, sleep, speed,
  audio tracks, subtitles. One horizontally scrollable row keeps every control reachable on a
  narrow phone instead of squeezing the title.
* **More menu** offers only options that mean something for the current item: aspect ratio
  (Fit → Crop → Stretch, applied through `PlayerView.resizeMode`), rotate, picture-in-picture,
  subtitles on/off when the source has a text track, lock controls, full name, playback info.
* **Bottom panel**: play/pause, −10 s, +10 s, previous/next (when the queue has more than one
  item), position / total, the three-track timeline, pause-prebuffer status, a speed shortcut,
  rotate and lock.
* **Track pickers are split by real track type** — `C.TRACK_TYPE_AUDIO` for audio and
  `C.TRACK_TYPE_TEXT` for subtitles — instead of one mixed list, and subtitle off uses
  `setTrackTypeDisabled`, so it genuinely disables rendering.
* **Sleep timer** keeps the delay behaviour and adds real presets plus "End of this item", which
  pauses on `onMediaItemTransition` rather than letting the queue continue.
* **Playback info** reports only real values: player path, streaming mode, `videoSize` when the
  player reports one, bitrate derived from real message size/duration, measured throughput from the
  estimator, buffered seconds and share, the TDLib downloaded window, rebuffer count, position.
  Anything unavailable is shown as `unknown`, never estimated.
* **Non-seekable sources**: when the player reports no duration, ±10 s and the seek bar are
  disabled, the total reads `--:--`, a line explains why, and `GestureMath.clampSeek` returns the
  current position instead of guessing a range.

`GestureMathTest` covers the zones and their edges, brightness/volume direction and clamping,
proportional and capped swipe seeking, double-tap zones, unknown-duration seeks, axis locking
(including that a locked gesture cannot flip and that an exactly diagonal drag stays undecided),
the speed and aspect cycles, and the buffered-percentage/unknown rules. Gesture *feel* on a real
screen — slop, overscroll, lock ergonomics — needs a device and is not verified in CI.
