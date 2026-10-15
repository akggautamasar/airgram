package com.airbooks.app.tg

import org.drinkless.tdlib.TdApi

/** Resolves display names/presence without the mapper needing TDLib session state. */
interface NameResolver {
    fun sender(s: TdApi.MessageSender?): String
    fun origin(o: TdApi.MessageOrigin?): String
}

/**
 * Maps TDLib messages onto the pure [Msg] model.
 *
 * Every content type the pinned TDLib schema exposes for a message is handled, and anything
 * unexpected degrades to [MsgKind.UNSUPPORTED] with its class name — an unknown message can
 * never crash a conversation. Mapping is defensive throughout: TDLib may deliver null
 * sub-objects (expired media, restricted content), and those render as text-only rows.
 */
object MsgMapper {

    fun map(
        m: TdApi.Message,
        names: NameResolver,
        track: (TdApi.File?) -> Int,
    ): Msg {
        val base = Msg(
            id = m.id,
            chatId = m.chatId,
            date = m.date,
            editDate = m.editDate,
            outgoing = m.isOutgoing,
            senderId = senderIdOf(m.senderId),
            senderName = names.sender(m.senderId),
            albumId = m.mediaAlbumId,
            reply = replyOf(m.replyTo, names),
            forwardFrom = m.forwardInfo?.let { names.origin(it.origin) }.orEmpty(),
            reactions = reactionsOf(m.interactionInfo),
            views = m.interactionInfo?.viewCount ?: 0,
            state = when (m.sendingState) {
                is TdApi.MessageSendingStatePending -> SendState.PENDING
                is TdApi.MessageSendingStateFailed -> SendState.FAILED
                else -> SendState.SENT
            },
        )
        return runCatching { base.merge(contentOf(m.content, track)) }.getOrDefault(
            base.copy(kind = MsgKind.UNSUPPORTED, serviceText = typeName(m.content)),
        )
    }

    /** Short label for a reply preview / chat list when only the content is known. */
    fun preview(c: TdApi.MessageContent?): String = when (c) {
        is TdApi.MessageText -> c.text?.text.orEmpty()
        is TdApi.MessagePhoto -> c.caption?.text?.ifBlank { "Photo" } ?: "Photo"
        is TdApi.MessageVideo -> c.caption?.text?.ifBlank { "Video" } ?: "Video"
        is TdApi.MessageAudio -> c.caption?.text?.ifBlank { "Audio" } ?: "Audio"
        is TdApi.MessageVoiceNote -> "Voice message"
        is TdApi.MessageVideoNote -> "Video message"
        is TdApi.MessageDocument -> c.document?.fileName?.ifBlank { "File" } ?: "File"
        is TdApi.MessageAnimation -> "GIF"
        is TdApi.MessageSticker -> "Sticker ${c.sticker?.emoji.orEmpty()}".trim()
        is TdApi.MessageLocation -> "Location"
        is TdApi.MessageVenue -> c.venue?.title?.ifBlank { "Venue" } ?: "Venue"
        is TdApi.MessageContact -> "Contact"
        is TdApi.MessagePoll -> c.poll?.question?.text?.ifBlank { "Poll" } ?: "Poll"
        is TdApi.MessageDice -> "Dice"
        is TdApi.MessageCall -> "Call"
        null -> ""
        else -> typeName(c)
    }

    private fun typeName(c: TdApi.MessageContent?): String =
        c?.javaClass?.simpleName?.removePrefix("MessageContent")?.let { "Unsupported ($it)" } ?: "Unsupported message"

    private fun senderIdOf(s: TdApi.MessageSender?): Long = when (s) {
        is TdApi.MessageSenderUser -> s.userId
        is TdApi.MessageSenderChat -> s.chatId
        else -> 0L
    }

    private fun replyOf(r: TdApi.MessageReplyTo?, names: NameResolver): ReplyRef? {
        val rr = r as? TdApi.MessageReplyToMessage ?: return null
        val sender = rr.origin?.let { names.origin(it) }.orEmpty()
        return ReplyRef(rr.messageId, sender, preview(rr.content).take(90))
    }

    private fun reactionsOf(info: TdApi.MessageInteractionInfo?): List<Reaction> =
        info?.reactions?.reactions?.mapNotNull { r ->
            val emoji = (r.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@mapNotNull null
            Reaction(emoji, r.totalCount, r.isChosen)
        }?.sortedByDescending { it.count } ?: emptyList()

    // ─────────────────────────── content mapping ───────────────────────────

    private fun contentOf(c: TdApi.MessageContent?, track: (TdApi.File?) -> Int): Msg = when (c) {
        is TdApi.MessageText -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.TEXT, text = c.text?.text.orEmpty(),
            link = c.linkPreview?.let {
                LinkCard(
                    url = it.url.orEmpty(),
                    site = it.siteName.orEmpty(),
                    title = it.title.orEmpty(),
                    desc = it.description?.text.orEmpty(),
                )
            },
        )

        is TdApi.MessagePhoto -> {
            val sizes = c.photo?.sizes?.sortedBy { maxOf(it.width, it.height) }.orEmpty()
            val thumb = sizes.firstOrNull { maxOf(it.width, it.height) >= 300 } ?: sizes.lastOrNull()
            val main = sizes.lastOrNull { maxOf(it.width, it.height) <= 1280 } ?: sizes.firstOrNull()
            val hd = sizes.lastOrNull()
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.PHOTO, caption = c.caption?.text.orEmpty(),
                fileId = track(main?.photo), hdFileId = track(hd?.photo), thumbId = track(thumb?.photo),
                mini = c.photo?.minithumbnail?.data,
                size = maxOf(main?.photo?.size ?: 0, main?.photo?.expectedSize ?: 0),
                width = main?.width ?: 0, height = main?.height ?: 0,
            )
        }

        is TdApi.MessageVideo -> {
            val v = c.video
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.VIDEO, caption = c.caption?.text.orEmpty(),
                fileName = v?.fileName.orEmpty(), mime = v?.mimeType.orEmpty(),
                size = maxOf(v?.video?.size ?: 0, v?.video?.expectedSize ?: 0),
                duration = v?.duration ?: 0, width = v?.width ?: 0, height = v?.height ?: 0,
                fileId = track(v?.video), hdFileId = track(v?.video), thumbId = track(v?.thumbnail?.file),
                mini = v?.minithumbnail?.data,
            )
        }

        is TdApi.MessageAudio -> {
            val a = c.audio
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.AUDIO, caption = c.caption?.text.orEmpty(),
                fileName = a?.fileName.orEmpty(), mime = a?.mimeType.orEmpty(),
                size = maxOf(a?.audio?.size ?: 0, a?.audio?.expectedSize ?: 0),
                duration = a?.duration ?: 0,
                fileId = track(a?.audio), hdFileId = track(a?.audio),
                thumbId = track(a?.albumCoverThumbnail?.file), mini = a?.albumCoverMinithumbnail?.data,
                text = listOf(a?.performer.orEmpty(), a?.title.orEmpty()).filter { it.isNotBlank() }.joinToString(" – "),
            )
        }

        is TdApi.MessageVoiceNote -> {
            val v = c.voiceNote
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.VOICE, caption = c.caption?.text.orEmpty(),
                mime = v?.mimeType.orEmpty(),
                size = maxOf(v?.voice?.size ?: 0, v?.voice?.expectedSize ?: 0),
                duration = v?.duration ?: 0, viewed = c.isListened,
                fileId = track(v?.voice), hdFileId = track(v?.voice),
            )
        }

        is TdApi.MessageVideoNote -> {
            val v = c.videoNote
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.VIDEO_NOTE,
                size = maxOf(v?.video?.size ?: 0, v?.video?.expectedSize ?: 0),
                duration = v?.duration ?: 0, width = v?.length ?: 0, height = v?.length ?: 0,
                viewed = c.isViewed,
                fileId = track(v?.video), hdFileId = track(v?.video),
                thumbId = track(v?.thumbnail?.file), mini = v?.minithumbnail?.data,
            )
        }

        is TdApi.MessageDocument -> {
            val d = c.document
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.DOCUMENT, caption = c.caption?.text.orEmpty(),
                fileName = d?.fileName.orEmpty(), mime = d?.mimeType.orEmpty(),
                size = maxOf(d?.document?.size ?: 0, d?.document?.expectedSize ?: 0),
                fileId = track(d?.document), hdFileId = track(d?.document),
                thumbId = track(d?.thumbnail?.file), mini = d?.minithumbnail?.data,
            )
        }

        is TdApi.MessageAnimation -> {
            val a = c.animation
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.ANIMATION, caption = c.caption?.text.orEmpty(),
                fileName = a?.fileName.orEmpty(), mime = a?.mimeType.orEmpty(),
                size = maxOf(a?.animation?.size ?: 0, a?.animation?.expectedSize ?: 0),
                duration = a?.duration ?: 0, width = a?.width ?: 0, height = a?.height ?: 0,
                fileId = track(a?.animation), hdFileId = track(a?.animation),
                thumbId = track(a?.thumbnail?.file), mini = a?.minithumbnail?.data,
            )
        }

        is TdApi.MessageSticker -> {
            val s = c.sticker
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.STICKER,
                stickerEmoji = s?.emoji.orEmpty(), width = s?.width ?: 0, height = s?.height ?: 0,
                size = maxOf(s?.sticker?.size ?: 0, s?.sticker?.expectedSize ?: 0),
                mime = when (s?.format) {
                    is TdApi.StickerFormatWebm -> "video/webm"
                    is TdApi.StickerFormatTgs -> "application/x-tgsticker"
                    else -> "image/webp"
                },
                fileId = track(s?.sticker), hdFileId = track(s?.sticker), thumbId = track(s?.thumbnail?.file),
            )
        }

        is TdApi.MessageLocation -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.LOCATION,
            lat = c.location?.latitude ?: 0.0, lon = c.location?.longitude ?: 0.0,
        )

        is TdApi.MessageVenue -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.VENUE,
            placeName = c.venue?.title.orEmpty(), address = c.venue?.address.orEmpty(),
            lat = c.venue?.location?.latitude ?: 0.0, lon = c.venue?.location?.longitude ?: 0.0,
        )

        is TdApi.MessageContact -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.CONTACT,
            contactName = listOf(c.contact?.firstName.orEmpty(), c.contact?.lastName.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" "),
            contactPhone = c.contact?.phoneNumber.orEmpty(),
        )

        is TdApi.MessagePoll -> {
            val p = c.poll
            Msg(
                id = 0, chatId = 0, date = 0, kind = MsgKind.POLL,
                pollQuestion = p?.question?.text.orEmpty(),
                pollVoters = p?.totalVoterCount ?: 0,
                pollClosed = p?.isClosed ?: false,
                pollIsQuiz = p?.type is TdApi.PollTypeQuiz,
                pollOptions = p?.options?.map {
                    PollOpt(it.text?.text.orEmpty(), it.voterCount, it.votePercentage, it.isChosen)
                }.orEmpty(),
            )
        }

        is TdApi.MessageDice -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.DICE,
            diceEmoji = c.emoji.orEmpty(), diceValue = c.value,
        )

        is TdApi.MessageCall -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.CALL, duration = c.duration,
            serviceText = if (c.isVideo) "Video call" else "Call",
        )

        is TdApi.MessageChatAddMembers -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE,
            serviceText = "Added ${c.memberUserIds?.size ?: 0} member(s)",
        )

        is TdApi.MessageChatJoinByRequest -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE, serviceText = "Joined the group",
        )

        is TdApi.MessagePinMessage -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE, serviceText = "Pinned a message",
        )

        is TdApi.MessageBasicGroupChatCreate -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE,
            serviceText = "Created group «${c.title}»",
        )

        is TdApi.MessageSupergroupChatCreate -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE,
            serviceText = "Created channel «${c.title}»",
        )

        is TdApi.MessageScreenshotTaken -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE, serviceText = "Took a screenshot",
        )

        is TdApi.MessageChatSetTheme -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE, serviceText = "Changed the chat theme",
        )

        is TdApi.MessageUnsupported -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.UNSUPPORTED, serviceText = "Unsupported message",
        )

        null -> Msg(id = 0, chatId = 0, date = 0, kind = MsgKind.UNSUPPORTED)
        else -> Msg(
            id = 0, chatId = 0, date = 0, kind = MsgKind.SERVICE,
            serviceText = typeName(c),
        )
    }

    /**
     * Overlays the content-only fields onto the envelope built from [TdApi.Message].
     * Content mapping returns a throwaway Msg (id/chatId/date = 0) carrying only the payload,
     * which keeps the huge `when` above readable and each branch allocation-cheap.
     */
    private fun Msg.merge(p: Msg): Msg = copy(
        kind = p.kind,
        text = p.text.ifBlank { this.text },
        caption = p.caption,
        fileId = p.fileId,
        hdFileId = p.hdFileId,
        thumbId = p.thumbId,
        mini = p.mini ?: this.mini,
        fileName = p.fileName,
        mime = p.mime,
        size = p.size,
        duration = p.duration,
        width = p.width,
        height = p.height,
        link = p.link,
        stickerEmoji = p.stickerEmoji,
        lat = p.lat,
        lon = p.lon,
        placeName = p.placeName,
        address = p.address,
        contactName = p.contactName,
        contactPhone = p.contactPhone,
        pollQuestion = p.pollQuestion,
        pollOptions = p.pollOptions,
        pollVoters = p.pollVoters,
        pollClosed = p.pollClosed,
        pollIsQuiz = p.pollIsQuiz,
        diceEmoji = p.diceEmoji,
        diceValue = p.diceValue,
        serviceText = p.serviceText.ifBlank { this.serviceText },
        viewed = p.viewed,
    )
}
