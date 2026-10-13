# AirGram (Android)

Native Android client for your Telegram chats: log in with your phone number, see every chat, and stream videos / music / PDFs / photos straight from Telegram.

## Build

1. Add `TG_API_ID` and `TG_API_HASH` as repository Actions secrets from https://my.telegram.org.
2. Open Actions → Build APK → Run workflow.
3. Download the `AirGram-apk` artifact. First build compiles TDLib and can take a while.

## App branding

- Android launcher icon is wired through adaptive icon resources in `app/src/main/res/mipmap-anydpi-v26/` with vector foreground/background drawables.
- Master logo files are in `assets/branding/airgram_icon.svg` and `assets/branding/airgram_icon.png`.
- APK builds use this icon automatically; no manual icon replacement is needed.

## Layout

- `app/src/main/java/com/airbooks/app/tg/` — TDLib wrapper and streaming data source.
- `app/src/main/java/com/airbooks/app/ui/` — app screens.
- `.github/workflows/build-apk.yml` — APK build workflow.
