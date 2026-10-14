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
- **Modes**: Normal = up to 32 MB window, 15–25 s min buffer / 90–120 s max, 1.5–2.5 s to start.
  Low Data = 2–8 MB window, smaller start buffer, longer stall tolerance, fewer retries with
  longer backoff, background prefetch and (while playing) thumbnail work suspended, bounded
  rebuffer protection that stops growing after 4 rebuffers.
- **`playback/PlaybackService.kt`**: a `MediaSessionService` owning the single ExoPlayer;
  the UI uses a `MediaController`. Audio now plays in the background with a media notification,
  queue (previous/next), title/artist/album-cover (from Telegram audio metadata +
  minithumbnail), and resume. Video leaving the screen stops playback; audio does not.
- **Player UX**: immersive video, `PlayerView` controller (play/pause, seek, elapsed/remaining,
  prev/next), playback-speed menu (persisted), Normal/Low Data selector chips, subtle
  "· Low Data" + buffered-seconds + estimated-kbps diagnostics, audio/subtitle/video track
  selection menu (real `TrackSelectionOverride`s), rotate button, mobile-data warning with
  one-tap switch to Low Data, error view that retries from the last position.
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

## 3. Limitations (honest list)

- TDLib's single-prefix window: seeking re-fetches at the new offset; previously fetched bytes
  at other offsets are kept by TDLib on disk but are not exposed as random-access ranges.
- No adaptive resolution/bitrate: a Telegram message contains one fixed-quality file, and the
  app does not pretend otherwise. `preferLowerVariant` activates only if variants ever exist.
- PDFs cannot be rendered by Android `PdfRenderer` from a partial file, so PDF preview still
  downloads the file (with progress) into the app cache — never into Downloads.
- LoadControl buffer durations are chosen when the playback service is created; the data-source
  window/backoff/stall behaviour follows live mode switches immediately, buffer durations take
  the new mode on the next service start.
- Codec support is the device's (Media3/ExoPlayer); some MKV/HEVC/AC3 files may not play.
  No transcoding, no VLC (per requirements).
- Background playback keeps audio after leaving the player; stop it from the notification.

## 4. Crash hardening and on-device diagnostics

A hard crash while opening a video traced to two failure classes that Media3 does not contain:

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

Acceptance criteria that need a device + real Telegram account (cannot be reproduced in CI):
items 1, 2, 7, 10 (real network streaming/seek/recovery/audio), 12 (auth/search against a live
account), 13 (visual theme check), 14 (process-restart persistence is standard
SharedPreferences, exercised by the same code paths). Suggested manual script: play a large
video on throttled Wi-Fi, seek to 50%/10%, toggle Low Data mid-playback, enable airplane mode
for 10 s and recover, background an audio track, kill and relaunch to confirm resume + prefs.

Build verification: GitHub Actions "Build APK" on the latest commit of this branch — unit tests
+ `:app:assembleRelease` green, artifact `AirGram-apk` (~32 MB). Launcher label is `AirGram`
(manifest unchanged), applicationId unchanged (`com.airbooks.app`).
