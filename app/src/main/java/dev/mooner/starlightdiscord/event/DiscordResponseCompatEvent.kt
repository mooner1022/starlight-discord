package dev.mooner.starlightdiscord.event

import android.graphics.Bitmap
import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate
import dev.mooner.starlight.plugincore.utils.toBase64

/**
 * The Discord bot received a message, given in the arguments of the legacy `response` function.
 *
 * Called after `onMessage`, only while both the plugin's "onMessage 이벤트 사용" and "레거시(response) 이벤트 사용"
 * settings are on. It calls the same `response` function as StarLight's legacy message event.
 *
 * @param room The channel's name; for a direct message, the other user's name.
 * @param msg The message's text.
 * @param sender The author's name.
 * @param isGroupChat `false` for a direct message, `true` for any other channel.
 * @param replier Sends replies.
 * @param imageDB The author's profile image.
 */
class DiscordResponseCompatEvent: ProjectEvent() {

    override val functionName: String = "response"

    override val id: String = "legacy"

    override val name: String = "response 호환 이벤트"

    override val description: String = translate {
        Locale.ENGLISH { "When the Discord bot receives a message, with the legacy response setting on" }
        Locale.KOREAN  { "레거시(response) 이벤트 사용을 켰을 때, 디스코드 봇이 메시지를 받으면" }
    }

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf(
            "room"        typedAs String::class,
            "msg"         typedAs String::class,
            "sender"      typedAs String::class,
            "isGroupChat" typedAs Boolean::class,
            "replier"     typedAs Replier::class,
            "imageDB"     typedAs ImageDB::class
        )

    /** Sends replies to a Discord message, as `replier` in `response`. */
    class Replier internal constructor(
        /** @suppress */
        val onSend: (room: String?, message: String, hideToast: Boolean) -> Boolean
    ) {

        /**
         * Sends [message] to the channel named [room]: a text channel, then a thread, then a direct message channel
         * the bot has seen. The message is queued without waiting for Discord.
         *
         * @param room The channel's name.
         * @param hideToast Not used on Discord.
         * @return Whether such a channel was found.
         */
        @JvmOverloads
        fun reply(room: String, message: String, hideToast: Boolean = false): Boolean =
            onSend(room, message, hideToast)

        /**
         * Sends [message] to the channel the message came from, waiting until Discord accepts it.
         *
         * @return Whether it was sent.
         */
        fun reply(message: String) =
            onSend(null, message, false)
    }

    /** The author's profile image, as `imageDB` in `response`. */
    class ImageDB internal constructor(
        private val bitmap: Bitmap
    ) {
        private var base64Cache: String? = null

        /** The profile image as PNG, encoded in Base64. */
        fun getProfileBase64(): String {
            if (base64Cache == null)
                base64Cache = bitmap.toBase64()
            return base64Cache!!
        }

        /** A hash of [getProfileBase64], to tell profile images apart. */
        fun getProfileHash(): Int =
            getProfileBase64().hashCode()

        /** The same as [getProfileBase64]. */
        fun getProfileImage(): String =
            getProfileBase64()

        /** The profile image as an Android `Bitmap`. */
        fun getProfileBitmap(): Bitmap =
            bitmap
    }
}