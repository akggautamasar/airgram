# Chats & Messaging

AirGram talks to Telegram through TDLib on the phone. There is no backend, no bot token and no
second session: the Chats feature reuses the **same authenticated TDLib client** that already
powers login, file lists and streaming (`tg/Tg.kt`). Nothing in this document is simulated — if
Telegram does not provide a value, the UI says "unknown" or leaves the control out.

## Layering

```
tg/Messages.kt      pure model: Msg, ChatItem, ChatStruct, previews, ticks, calendar helpers
tg/MsgMapper.kt     TdApi.Message -> Msg (every content type in the pinned schema)
tg/ChatRepo.kt      per-conversation cache + live update application (LRU-bounded)
tg/Tg.kt            TDLib calls, update routing, user/presence cache, chat-list publishing
ui/ChatsScreen.kt   chat list (Telegram rows, grid option, folders, archive, filters)
ui/ConversationScreen.kt  one conversation: bubbles, composer, reactions, pagination
ui/SearchScreen.kt  global message/media search + matching chats
ui/MediaSectionScreen.kt  global Videos/Audio/Docs/Photos (all chats)
MainActivity.kt     Videos/Audio/Docs/Photos/Chats bar + pushed detail stack
NavCodec.kt         pure codec that persists navigation across rotation/process death
```

The rule that keeps this maintainable: **`Messages.kt` and `NavCodec.kt` have no Android and no
TdApi imports**, so the parts that decide what the user sees are covered by JVM unit tests
(`MessagesTest`, `NavCodecTest`). TDLib-shaped code is confined to `MsgMapper.kt` and `Tg.kt`.

## Chat list

`Tg.publishChats()` turns TDLib's `chatMap` into `ChatRow`s per chat list (`main`, `archive`,
`folder:<id>`), debounced 150 ms through the existing `dirty` channel. Each row carries real
fields: position order, `is_pinned`, unread count, `is_marked_as_unread`, mute state
(`useDefaultMuteFor == false && muteFor > 0`), draft text, last-message date/sender/preview,
`last_read_inbox_message_id` and `last_read_outbox_message_id` (for delivery ticks), presence for
private chats, and `permissions.canSendBasicMessages`.

* **Ordering** is Telegram's own: pinned chats first, then descending `chatPosition.order`.
* **Previews** come from the real last message content via `MsgMapper.preview` — type label plus
  the first line of text. Nothing is invented for content TDLib has not delivered.
* **Sender prefix** (`Alice: …`) is only added for groups/channels, and `You: ` for your own last
  message, matching Telegram.
* **Incremental loading**: `LoadChats(main, 100)` at startup, then `Tg.loadMoreChats()` when the
  list is scrolled near its end (and as an explicit button). TDLib answers `404` when every chat
  is loaded, which flips the footer to "All chats loaded". Archive/folder lists are loaded lazily
  by `ensureList` when their chip is selected.
* **No bulk fetching**: the list only reads chat metadata. Message history and attachments are
  fetched when a conversation or its media library is opened.
* Type labels (`Private chat`, `Group`, `Channel`, `Saved messages`) are unchanged so the existing
  All / Private / Groups / Channels chips keep working; `kind` adds `bot`/`saved` for logic only.

Controls: tap opens the conversation, the folder icon opens that chat's media library
(Videos/Audio/Docs/Photos), long-press (or the grid card) opens Pin / Mute / Mark read-unread /
Media and files. `toggleChatIsPinned`, `setChatNotificationSettings` (mute = one year, Telegram's
own default) and `toggleChatIsMarkedAsUnread` are real TDLib calls; marking read also issues
`viewMessages` with the real last message id, since the flag alone would leave the badge.

## Conversation

`ChatRepo.state(chatId)` holds one `MsgList` (`items` newest-first, `oldestId` cursor, `loading`,
`done`, `error`, `total`). Paging uses `getChatHistory(chatId, fromMessageId = oldestId, offset = 0,
limit = 40)`; a page shorter than 40 or an empty page means the beginning of history was reached.
Only opened chats are materialised, and an access-ordered LRU evicts beyond **6 conversations**, so
a huge account history never costs memory it is not using.

Rendering uses `reverseLayout = true`: index 0 is the bottom, so appending an older page at the end
**cannot move what the user is reading** — scroll position stays stable during pagination.

`ChatStruct.build()` produces the rows: date separators, adjacent same-`mediaAlbumId` messages
grouped into one album grid, and a single unread divider. Because a reversed list renders
bottom-up, each day's separator is emitted *after* that day's messages so it appears above them.
For ids `[5,4,3,2,1]` with 4–5 unread the result is
`[m5, m4, unread, m3, m2, m1, day]`, which displays as Telegram does:

```
── Today ──
1  2  3
── Unread messages ──
4  5
```

`MessagesTest` pins this ordering, the timezone behaviour of `dayBucket`, and the pure civil
calendar (`civilFromDays`) used for labels — `minSdk 24` has no `java.time`, and desugaring was not
added just to format a date.

### Live updates

`Tg.onUpdate` routes into the same model the UI reads, so a conversation updates in place:

| Update | Effect |
| --- | --- |
| `updateNewMessage` | prepend (or replace by id) in the open conversation, refresh chat list |
| `updateDeleteMessages` | remove from the open conversation |
| `updateMessageContent` | re-fetch with `getMessage` and replace (content changed remotely) |
| `updateMessageEdited` | set `editDate` → "edited" marker |
| `updateMessageReactions` | replace the reaction list |
| `updateMessageSendSucceeded` / `SendFailed` | swap the temporary id for the real message / mark FAILED |
| `updateChatLastMessage` / `ChatPosition` / `ChatReadInbox` / `ChatReadOutbox` | chat-list preview, order, badges, ticks |
| `updateChatDraftMessage` / `NotificationSettings` / `IsMarkedAsUnread` | draft preview, mute icon, unread dot |
| `updateUser` / `updateUserStatus` | names and presence lines |

Sender names are resolved through a user cache; an unknown id triggers one `getUser` (deduplicated
by a `ConcurrentHashMap.newKeySet`) and the row re-renders when it lands. Until then it shows
empty rather than a fabricated name.

### Message types

Handled explicitly: text (with link-preview cards), photo, video, audio, voice note, video note,
document, animation/GIF, sticker, location, venue, contact, poll/quiz (options, percentages,
voter count, closed state), dice, call, and service messages (members added, group/channel
created, pinned message, screenshot, theme change, join by request).

Everything else — including `messageUnsupported` and any type a future TDLib adds — degrades to a
labelled row (`Unsupported (ClassName)`) instead of crashing, because `MsgMapper.map` wraps content
mapping in `runCatching`. Media that Telegram has restricted or expired simply arrives with
`fileId == 0` and renders as its caption/text only.

### Sending, editing, deleting, reacting

* **Text**: `sendMessage` with `inputMessageText` and `clearDraft = true`. Replies pass
  `inputMessageReplyToMessage`. There is deliberately **no hand-made optimistic echo**: TDLib emits
  the outgoing message itself with a temporary id and PENDING state, then `updateMessageSendSucceeded`
  replaces it with the real id. One source of truth means a bubble can never appear twice.
* **Attachments**: the system picker returns a `content://` URI, which native TDLib cannot read, so
  the file is copied into `cacheDir/uploads` on `Dispatchers.IO` and sent as `inputFileLocal`. The
  MIME type chooses the Telegram content type — photo, video (`supportsStreaming = true`), GIF as
  animation, audio, otherwise document — so the recipient sees it correctly.
* **Edit**: own text messages via `editMessageText`. **Delete**: own messages via
  `deleteMessages(revoke = true)`. Telegram rejects what the account is not allowed to do; the
  error surfaces in the snackbar rather than being hidden.
* **Reactions**: `addMessageReaction` with `reactionTypeEmoji`; the chip row shows real
  `messageReactions` counts and which one you chose.
* **Read state**: `viewMessages(..., source = messageSourceChatHistory, forceRead = true)` for
  whatever is on screen. Ticks come from `ticksFor(lastMsgId, lastReadOutboxId, outgoing)` →
  none / sent / read — no guessing.

### Attachments reuse the existing pipeline

`Msg.toMedia()` bridges a message into the app's existing `Media` model, so opening an attachment
goes through the same code as the media library: video/audio → the streaming player, PDF →
`PdfScreen`, image → `ImageScreen` (with the whole chat's photos as the swipe queue), anything
else → `OtherScreen`, and the download button → `DownloadManager.enqueue` (saved downloads stay
separate from the streaming cache, as before). Thumbnails render through `TgImage`, which shows the
~300-byte inline preview instantly and fades in the real file, and honours the existing
Auto-thumbnails / Low-Data / Wi-Fi-only-prefetch gates.

## Navigation

Five destinations — **Videos, Audio, Docs, Photos, Chats** — with a `NavigationBar` on phones and a
`NavigationRail` at `screenWidthDp >= 600` (tablets, foldables, desktop). The four media sections
are the original file browser's sections promoted to first-class navigation; Chats sits beside
them. Global search, Downloads and Settings are pushed screens reachable from every media
section's overflow menu and the Chats account button, so nothing that used to be one tap away is
lost. Conversations, per-chat file browsers and viewers are pushed onto the same detail stack, so
the bottom bar hides while you read a chat and Back returns through the stack exactly as before.

`NavCodec` serialises destination + stack to one delimited string for `rememberSaveable`, so
rotation and process death restore the same tab and the same open conversation. Two honest limits,
both encoded in the codec and covered by `NavCodecTest`:

* `Media.mini` (the ~300-byte inline preview) is not persisted; it is re-fetched by file id.
* Saved-state transactions are limited to ~1 MB, so a payload over 90 000 characters is re-encoded
  with single-item queues. The open item still restores exactly; only its next/previous queue is
  shortened. This is a deliberate trade instead of a `TransactionTooLargeException` crash.

Switching destination clears the pushed stack (standard bottom-nav behaviour); state within a
destination is preserved while you are in it.

## Offline, errors and permissions

`ConnectionBanner` shows the real TDLib connection state at the top of the chat list, conversation
and search. History failures set `MsgList.error` and render a Retry button — the screen never goes
blank or spins forever. Send failures surface the TDLib error text. On reconnect TDLib re-syncs on
its own and the update handlers above apply whatever changed.

The composer is hidden when `permissions.canSendBasicMessages` is explicitly false (channels you
cannot post in), with a line saying so; it is *not* hidden merely because permissions have not
arrived yet. Secret chats are not offered (TDLib is configured without them, unchanged). Archived
chats are reachable from the Archive entry and their own chip.

## Verified / not verified

* **Verified by unit tests (JVM, no device)**: day buckets and timezone handling, civil calendar,
  separator labels, chat-list timestamps, conversation row ordering incl. unread divider and album
  grouping, stable keys, previews, delivery ticks, and the whole navigation codec (round trips,
  trimming, malformed and hostile input).
* **Verified by build**: TDLib schema usage compiles against the pinned `td_api.tl`
  (`canSendBasicMessages`, `messageInteractionInfo.reactions.reactions`, `inputMessageReplyTo*`,
  `updateChatReadOutbox`, `userTypeBot`).
* **Not verified here**: end-to-end sending/receiving against a live Telegram account needs a real
  phone number, code and a device — that cannot be done from CI. Everything is wired to real TDLib
  calls with no stubs, but the interactive path is untested by me and should be exercised on a
  device with a real login.

## Deliberately out of scope (not implemented)

Stated plainly so nothing is assumed to work that does not:

* **Forwarding** — a forwarded message *shows* "Forwarded from …", but there is no forward action;
  you cannot re-send someone else's message to another chat.
* **Recording** — no voice-note or video-note capture. Sending is limited to files picked through
  the system document picker, one at a time; received voice/video notes do play.
* **Secret chats** — not offered (TDLib is configured without them, unchanged from before).
* **In-conversation search** — only global search exists; there is no "find in this chat".
* **Multi-account / account switching** — one session, as before.
* **Rich content creation** — polls, quizzes, locations, contacts, dice and stickers can be
  *received* (rendered as their real text/preview, or an explicit unsupported line); none can be
  *composed* in-app.
* **Message editing history, scheduled/silent sending, send-as** — not exposed.
* **Group/channel administration** — beyond pin, mute, mark read/unread and archive there are no
  admin tools (member management, invites, slow mode).
