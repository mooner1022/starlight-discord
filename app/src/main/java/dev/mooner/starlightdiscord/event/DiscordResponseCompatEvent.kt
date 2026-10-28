package dev.mooner.starlightdiscord.event

import android.graphics.Bitmap
import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.utils.toBase64

class DiscordResponseCompatEvent: ProjectEvent() {

    override val functionName: String = "response"

    override val id: String = "legacy"

    override val name: String = "response 호환 이벤트"

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf(
            "room"        typedAs String::class,
            "msg"         typedAs String::class,
            "sender"      typedAs String::class,
            "isGroupChat" typedAs Boolean::class,
            "replier"     typedAs Replier::class,
            "imageDB"     typedAs ImageDB::class
        )

    data class Replier(
        val onSend: (room: String?, message: String, hideToast: Boolean) -> Boolean
    ) {

        @JvmOverloads
        fun reply(room: String, message: String, hideToast: Boolean = false): Boolean =
            onSend(room, message, hideToast)

        fun reply(message: String) =
            onSend(null, message, false)
    }

    data class ImageDB(
        private val bitmap: Bitmap
    ) {
        private var base64Cache: String? = null

        fun getProfileBase64(): String {
            if (base64Cache == null)
                base64Cache = bitmap.toBase64()
            return base64Cache!!
        }

        fun getProfileHash(): Int =
            getProfileBase64().hashCode()

        fun getProfileImage(): String =
            getProfileBase64()

        fun getProfileBitmap(): Bitmap =
            bitmap
    }
}