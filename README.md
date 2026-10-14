# AirGram (Android)

Native Android client for your Telegram chats: log in with your phone number, see every chat,
and **stream** videos / music / PDFs / photos straight from Telegram — no backend, no server
deployment, nothing downloaded up front.

## How it works

* **TDLib** (Telegram's official client library) runs inside the app and talks MTProto directly to Telegram.
  Login, chats, search and file access all happen on the phone. Your session never leaves the device.
* **Streaming**: `TdDataSource` plugs TDLib into ExoPlayer. It requests only a window of bytes around the
  playhead (4 / 16 / 48 MB, your choice in Settings), reads from TDLib's partial file, and slides the window
  forward. Seeking just moves the window; anything already fetched is reused.
* **Slow-network behaviour**
  * tiny (~300 byte) inline previews show instantly, real thumbnails load only for visible items (can be switched off);
  * ExoPlayer keeps up to 90 s buffered and waits 5 s after a stall before resuming;
  * file lists are paged (40 at a time) and cached in memory;
  * playback position is remembered per file;
  * photos open at ≤1280 px, with a "Load full quality" button;
  * the download stops when you leave the player, so no data is wasted.
* PDFs are downloaded (with a progress bar) and rendered page by page with Android's `PdfRenderer`
  (a PDF cannot be rendered before the whole file is present). Other file types download then open in another app.

## Build (no Android Studio needed)

1. Push this folder to a GitHub repo (public repos get free Actions minutes).
2. Repo → Settings → Secrets and variables → Actions → add:
   * `TG_API_ID`, `TG_API_HASH` — from https://my.telegram.org (same ones your Pyrogram backend uses)
   * *(recommended)* `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — a fixed signing key,
     so new builds install over old ones **without losing your login**. Create one with:
     `keytool -genkeypair -v -keystore release.keystore -alias airbooks -keyalg RSA -keysize 2048 -validity 10000`
     then `base64 -w0 release.keystore` for the secret.
3. Actions → **Build APK** → Run workflow. The first run compiles TDLib from Telegram's official source
   (~30–60 min); it is cached afterwards and later builds take a few minutes.
4. Download the `AirGram-apk` artifact, unzip, install the APK.

Local build: put `TG_API_ID=...` and `TG_API_HASH=...` in `local.properties`, copy TDLib's `libtdjni.so` into
`app/src/main/jniLibs/<abi>/` and its Java files into `app/src/main/java/org/drinkless/tdlib/`
(see https://github.com/tdlib/td/tree/master/example/android), then `gradle :app:assembleRelease`.

## Layout

```
app/src/main/java/com/airbooks/app/
  tg/Tg.kt            TDLib wrapper: auth, chats, file search, downloads
  tg/TdDataSource.kt  ExoPlayer ↔ TDLib windowed streaming
  tg/Media.kt         models + in-memory file-list cache
  ui/                 Login, Chats, Files (tabs/grid/search), Player, Image, PDF, Settings
```

## Known limits / ideas

* Audio keeps playing in the background via a media-session service; videos/audio stream in
  Normal or Low Data mode (player + Settings → Streaming). See STREAMING.md for the design,
  honest limitations (no adaptive bitrate — Telegram files are single-quality) and test status.
* Playback depends on the phone's codecs (e.g. some MKV/HEVC/AC3 files); no transcoding.
* Archived chats and chat folders are not listed yet (main list only). EPUB reader not included.
* Image viewer has no pinch-zoom yet.
