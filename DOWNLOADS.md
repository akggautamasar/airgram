# AirGram download-speed programme: audit, baseline, design, results

Status: **audit + scheduler core + tests complete; device benchmarks pending (require a phone +
Telegram account, which CI and this sandbox do not have).** Nothing in this document claims a
speed win that has not been measured; measured numbers live in §9 and are filled in from device
runs of the benchmark harness (§6).

## 1. Audit of the existing implementation

### 1.1 TDLib version, configuration, native build
- TDLib is **not vendored**: `.github/workflows/build-apk.yml` resolves `tdlib/td` master at run
  time (`git ls-remote`), builds it with Telegram's official Docker recipe on cache miss
  (~30–60 min), caches by TDLib sha, then copies `libtdjni.so` (arm64-v8a, armeabi-v7a) and
  `TdApi.java` into the app. At the time of this audit master = `26766da7` = **TDLib 1.8.68**
  (`project(TDLib VERSION 1.8.68)` in `CMakeLists.txt`).
- `Tg.init` → `Client.execute(SetLogVerbosityLevel(1))`; `SetTdlibParameters` uses
  `useFileDatabase=true`, `useChatInfoDatabase=true`, `useMessageDatabase=true`,
  `useSecretChats=false`, empty DB encryption key, `filesDirectory = cacheDir/tdfiles`.
  Consequence: **partial downloads persist across process death** and TDLib resumes from the
  stored prefix — free resume support that the old code never exploited for user downloads.
- **No `SetOption` calls at all**, and **no `setNetworkType` call anywhere** (see §3, B2).

### 1.2 Auth and file references
- Login flow: `SetAuthenticationPhoneNumber` / `CheckAuthenticationCode` /
  `CheckAuthenticationPassword`; `AuthorizationStateReady` → `GetMe` + `LoadChats` loop.
- File references: `fileMap: ConcurrentHashMap<Int, TdApi.File>` fed by `UpdateFile`;
  `cachedFile()` falls back to a blocking `GetFile`; `refreshFileBlocking()` recovers expired
  references. Media rows carry `fileId/hdFileId/thumbId` (`tg/Media.kt`).

### 1.3 Every existing download path (there is no download manager)
| Path | Trigger | Priority | Mechanism |
|---|---|---|---|
| `TdDataSource` streaming windows | player reads | **32** | `DownloadFile(id, 32, offset, limit)` per policy window |
| PDF / "Other" viewer | opening a doc | **32** | `awaitDownload` whole file |
| Full image | opening photo | 16 | `awaitDownload` whole file |
| Thumbnails / minithumbs | lists, player art | 1 | `awaitDownload` (Components.kt:75) |
`awaitDownload` (Tg.kt) **polls `fileMap` every 250 ms** and blindly re-issues `DownloadFile`
every 6 s while `!isDownloadingActive` — polling overhead plus duplicate-request churn, no
backoff, no cancellation token, no queue, no persistence, no per-file speed/ETA.

### 1.4 Streaming + cache behaviour
`tg/TdDataSource.kt` (audited in STREAMING.md): one policy-sized TDLib window per read position,
de-duplicated ranges, bounded backoff, refcounted lifetime (`Tg.beginStream/endStream`), reads
through a single `RandomAccessFile`. Cache cap enforced best-effort 10 s after start
(`Tg.enforceCacheLimit`), user-settable `maxCacheMb`.

### 1.5 Player service vs background work
`PlaybackService` (MediaSessionService) owns the single ExoPlayer; `PlayerScreen` connects a
`MediaController`. Streaming uses TDLib priority **32** — the same top band the PDF viewer uses,
and higher than any future download could use without starving playback.

### 1.6 Destinations, resume, cancellation
Completed files live in `cacheDir/tdfiles` (TDLib-owned). Viewers expose them via
`FileProvider` (`PdfScreen.kt:61`) — **no re-copy on open/share**. Cancellation:
`cancelDownload` only through stream refcount; `awaitDownload` cannot be cancelled at all.
There is no "save to device storage" path yet (Downloads screen adds one, §7).

### 1.7 Permissions / services / CI
Manifest: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
POST_NOTIFICATIONS. **No `dataSync` foreground service type** (needed for screen-off downloads).
CI: `build-apk.yml` runs `:app:testReleaseUnitTest` then `:app:assembleRelease` on every push to
`main`/`arena/**`; unit tests are JVM-only (`StreamingPolicyTest`, now `SchedulerTest`).

## 2. Ground truth from the bundled TDLib (1.8.68) source

| Fact | Evidence (tdlib/td @ 26766da7) |
|---|---|
| One download window per file; a new offset **moves** it | `FileManager.cpp:609-620` (`FileNode::set_download_offset`, single `download_offset_`), `:3103` (`downloadFile` handler sets it) |
| Part size ≤ 512 KiB, part count capped | `PartsManager.h:58-59` (`MAX_PART_SIZE = 512 << 10`) |
| A file may fetch several parts **in parallel, bounded by its resource share** | `FileDownloader.cpp:461-467` (`max_parts = max_resource_limit / part_size`) |
| Byte budget is fair-shared across active tasks **by priority** (priority heap) | `files/ResourceManager.{h,cpp}` (`update_priority`, `register_worker`, heap nodes) |
| Equal priority ⇒ last-requested first | `td_api.tl` `downloadFile` doc |
| `limit=0` = whole file; progress via `updateFile`; partial prefix persists | `td_api.tl` `downloadFile`/`cancelDownloadFile` docs |
| `setNetworkType` **must** be called on every network change (reopens connections) | `td_api.tl` `setNetworkType` doc |
| **No documented option tunes download throughput** | `OptionManager.cpp` option list contains only account/UI/notification keys (plus `connection_parameters`) |

## 3. Bottleneck identification (ranked, each measurable on-device)

- **B1 — Contention for TDLib's byte budget.** ResourceManager splits in-flight bytes across all
  priority>0 tasks. Today thumbnails (prio 1), viewers (32) and streaming (32) all compete; a
  user "download" would join the same pool with no coordination. *Measure: single-file
  completion time with/without concurrent thumbnail+stream load.*
- **B2 — `setNetworkType` never called.** Documented as mandatory on every change; without it
  TDLib keeps stale sockets across Wi-Fi↔data or AP changes → stalls/reconnects.
  *Measure: time-to-first-byte and stall count across a forced network switch.*
- **B3 — Polling + blind re-kicks in `awaitDownload`.** 4 Hz polling of `fileMap` and 6 s
  duplicate `DownloadFile` churn (each new offset/limit **restarts** the window per §2!).
  *Measure: redundant bytes = prefix regressions; CPU %.*
- **B4 — Same-fileId conflicts.** Stream window vs viewer vs thumb of one file move each other's
  window. *Measure: prefix regression count per file.*
- **B5 — No queue/persistence/retry policy.** Process death loses intent (not bytes); no backoff.
- **B6 — No aggregate scheduling.** Nothing controls how many files run at once, so "multi-file"
  today is accidental (list scrolling) and unmeasured.

**Conclusion:** the binding constraint is not raw MTProto speed but *how the app schedules work
against TDLib's priority-weighted byte budget*, plus B2/B3 stalls. That is exactly what an
app-level scheduler can fix — and what §4–§7 implement.

## 4. Single-file optimizations implemented (mapped to the brief §3)

1. Reuse cached/partial data: jobs reconcile with TDLib's persisted prefix on restore; a
   COMPLETED job with a local path is never re-enqueued (`Scheduler.enqueue`).
2. No duplicate/competing downloads: one job per fileId; stream/viewer/thumb conflicts handled by
   refcounts + priority bands (streaming keeps 30–32 headroom, downloads ≤ 30).
3. Correct priority use: `SchedulePlan.priorityFor` spreads TDLib 8..30 over active jobs, boosted
   job on top; parking = priority 0.
4. No cancel/restart cycles: preemption parks (priority 0) and resumes from prefix; `awaitDownload`
   re-kick churn replaced by `updateFile`-driven progress (DownloadManager, §7).
5. No redundant copy/hash on the critical path: open/share stays FileProvider; export uses
   `FileChannel.transferTo`; SHA-256 only on completion validation, off the read path.
6. Efficient progress: `updateFile` deltas → EWMA per job + aggregate; UI throttled to 4 Hz.
7. Resume without re-transfer: TDLib prefix + persisted queue (`Scheduler.toPersist`).
8. TDLib tuning: **none applied, because §2 proves no supported throughput option exists in
   1.8.68.** `setNetworkType` wired to a `ConnectivityManager` callback (B2).
9. Network-change stalls: B2 fix + network-aware policies (Wi-Fi-only parks while metered).
10. Profiling: harness records per-trial TTFF, sustained/peak MB/s, retries, prefix regressions
    (redundant bytes), CPU and battery deltas (§6).

### 4.1 Parallel-range experiment — verdict: **INFEASIBLE, not shipped, documented**
Hypothesis: N concurrent `DownloadFile(id, off_i, lim_i)` = N independent connections.
Result: **false.** TDLib holds a single `download_offset_`/ready-prefix per file
(`FileManager.cpp:609-620`); a second request *moves* the window and cancels the previous
synchronous transfer (`td_api.tl`). Ranges are therefore neither independent nor simultaneously
in flight; assembly "without gaps" is impossible because only one prefix exists at a time.
A single file *does* get internal parallelism (multiple 512 KiB parts) sized by its ResourceManager
share (`FileDownloader.cpp:466`) — so the supported way to make one file faster is to **give it the
dominant share** (boost priority, park competitors), which is what the scheduler does. No fake
ranges, no undocumented storage, no auth bypass.

## 5. Multi-file scheduler (brief §4) — `download/Scheduler.kt` (+ tests)

Profiles HIGH_SPEED 2–6 (base 4), BALANCED 1–4 (base 2), LOW_DATA 1–2 (base 1, ≤2, 1 on metered);
adaptive concurrency with +5 %/−5 % hysteresis and 5 s cooldown, bounded by profile and by
playback state; boosted user priority; starvation guard (60 s) + shortest-remaining fairness;
duplicate-job prevention; TSV persistence with ACTIVE→QUEUED restore; bounded backoff
(1 s×2^n, cap 60 s); Wi-Fi-only and metered policies; `SchedulePlan` emits run/park sets and
TDLib priorities. Playback-awareness (§5 of brief): `playbackActive` lowers the concurrency
ceiling and rebuffer pressure shrinks it further; streaming's refcount is untouched by download
cancellation (`Tg.beginStream/endStream` vs download refcount).

## 6. Benchmark harness (to be run on-device) — `bench/` + protocol

Scenarios: S1 single file {50, 200, 400 MB}; S2 concurrency sweep {1,2,3,4,6}; S3 playback
contention (video streaming during download); S4 network switch mid-transfer; S5 kill/restart
resume. Each trial records: TTFF, sustained MB/s (EWMA over ≥10 s), peak MB/s (1 s max),
completion time, retries, prefix regressions (redundant bytes), aggregate MB/s, CPU % (delta of
/proc/self/stat), battery % (BatteryManager), network type, TDLib sha, app version. Export: CSV
in `files/bench/` + share sheet + on-screen table.
Controls: same device/account/files; alternating AirGram↔Telegram order; cache states
{cold = `clearStreamingCache`+reboot TDLib client, warm = prefix kept}; ≥5 trials/scenario;
median reported; trials during server-load spikes (speed variance > 25 % across alternation)
flagged and excluded. Official-Telegram comparison is manual: Telegram's own download of the same
message file, timed by the harness stopwatch from tap to "done", same controls. **These runs need
the phone; CI cannot produce them.**

## 7. Downloads UI + service (brief §6–§7) — implemented files

- `download/Scheduler.kt` — pure core (profiles, adaptive concurrency, fairness, boost, backoff,
  persistence, `SchedulePlan` with TDLib priorities 8..30, park = priority 0).
- `download/DownloadManager.kt` — TDLib glue: 500 ms control loop applying plans via
  `downloadFile(id, priority, 0, 0)`; progress from `updateFile` (no polling); TTFF/peak/
  redundant-byte detection from prefix deltas; validation-gated completion; queue persisted to
  `files/download_queue.tsv`; `NetworkMonitor` pushes `setNetworkType` on every connectivity
  change and drives metered/Wi-Fi-only policy; playback-aware concurrency via
  `StreamingRuntime` + `playbackPressure()`; separate download vs stream refcounts so cancelling
  one never cancels the other.
- `download/DownloadService.kt` — `dataSync` foreground service, 1 Hz notification with aggregate
  speed; started/stopped with activeCount.
- `download/Validation.kt` — pure integrity rules (exists/size, SHA-256, same-content).
- `bench/Trial.kt` + `bench/Bench.kt` — trial model + on-device recorder → `files/bench/trials.csv`
  (TTFF, completion, sustained vs peak MB/s, retries, redundant bytes, process CPU ms from
  /proc/self/stat, battery delta, network, concurrency, profile); export/share from Downloads.
- `ui/DownloadsScreen.kt` — active/pending/completed/failed, per-file speed/%/bytes/size/ETA,
  aggregate speed, profile chips, scenario tag chips, CSV export, pause/resume/cancel/retry/
  priority, open/share/save/delete via FileProvider, storage usage + duplicate detection.
- Nav: bottom-bar "Downloads" item (`ChatsScreen`), per-file download buttons (`FilesScreen`),
  Settings → Downloads (profile, Wi-Fi-only). Manifest: `FOREGROUND_SERVICE_DATA_SYNC` +
  `DownloadService` (`foregroundServiceType="dataSync"`); FileProvider paths extended.
- A file is shown COMPLETED only after `FileValidation.isValid` on the final path; SHA-256 runs
  only on finished files, off the download path.

## 8. Tests & build
JVM: `SchedulerTest` (dedupe, caps, fairness, starvation, boost, backoff, adaptivity bounds,
progress/ETA, pause/resume/cancel, persistence), `DownloadFlowTest` (interruption→resume without
re-transfer, cancel, process-death restore, completed-file integrity incl. SHA-256 vector,
streaming contention shrink/restore, no double-active job, trial CSV layout) + existing
`StreamingPolicyTest`. CI runs all of them plus `:app:assembleRelease` on every push.
Device-only acceptance (real transfers, screen-off, official-Telegram comparison) follows the
protocol in §6 and cannot be executed in CI.

## 9. Results (device runs — to be filled)

| Scenario | n | AirGram base median | AirGram opt median | Telegram median | Δ vs base | Δ vs Telegram |
|---|---|---|---|---|---|---|
| S1 400 MB Wi-Fi | – | – | – | – | – | – |
| S1 400 MB data | – | – | – | – | – | – |
| S2 ×4 Wi-Fi | – | – | – | n/a | – | – |
| S3 contention | – | – | – | n/a | – | – |

## 10. Limitations / next experiment
Device numbers pending; TDLib exposes no per-connection or part-size knob, so the ceiling for
single-file gains is the DC's bandwidth × our budget share. Next experiment if targets miss:
measure whether keeping 2 files active at unequal priorities beats 1 file at top priority on
saturable links (i.e. is TDLib's ResourceManager leaving headroom idle), using S2 sweep data.
