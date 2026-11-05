package dev.mooner.starlightdiscord.models

import android.webkit.MimeTypeMap
import dev.mooner.starlight.plugincore.chat.ChatRoom
import dev.mooner.starlight.plugincore.chat.DebugChatRoom
import dev.mooner.starlight.plugincore.chat.MediaFile
import dev.mooner.starlight.plugincore.chat.MediaSendResult
import dev.mooner.starlight.plugincore.chat.MediaSender
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.utils.FileUpload
import java.util.concurrent.CompletableFuture

class DiscordChatRoomImpl(
    private val channel: MessageChannel,
): ChatRoom {

    override val id: String = channel.id

    override val isDebugRoom: Boolean = false

    override val isGroupChat: Boolean =
        channel !is PrivateChannel

    // For private channels this is the name of the other user.
    override val name: String =
        channel.name

    /** How results name this room, as StarLight names rooms in [MediaSendResult.room]. */
    private val label: String
        get() = "$name ($id)"

    override fun markAsRead(): Boolean {
        return true
    }

    override fun send(message: String): Boolean {
        try {
            channel.sendMessage(message).complete()
        } catch (_: Exception) {
            return false
        }
        return true
    }

    /**
     * Uploads [files] to this channel. StarLight reads them (paths, URLs, data URLs, Base64, bytes, bitmaps, arrays
     * and the options) and prepares the files; this only uploads what it hands over.
     *
     * StarLight sends to KakaoTalk unless the room is a [DebugChatRoom] that takes the files itself, so the send goes
     * through one standing in for this channel. That is the only hook PluginCore has for a room with its own upload.
     */
    override fun sendMedia(files: Any?, options: Any?): CompletableFuture<MediaSendResult> {
        val sender = MediaSender.current
            ?: return CompletableFuture.completedFuture(MediaSendResult.failure(label, "Sending files isn't available here"))
        val uploader = DebugChatRoom(
            id = id,
            name = name,
            isGroupChat = isGroupChat,
            isDebugRoom = false,
            onSend = { send(it) },
            onMarkAsRead = {},
            onSendMedia = ::upload,
        )
        return try {
            sender.send(uploader, files, options).thenApply { it.withRoom(label) }
        } catch (e: Throwable) {
            CompletableFuture.completedFuture(MediaSendResult.failure(label, e.message ?: e.toString()))
        }
    }

    /**
     * Uploads [media] before returning: StarLight deletes the files once this returns. Called off the main thread.
     * Throws if Discord refuses a message, which StarLight reports as the send's error.
     */
    private fun upload(media: List<MediaFile>) {
        var uploaded = 0
        for (chunk in media.chunked(Message.MAX_FILE_AMOUNT)) {
            try {
                channel.sendFiles(chunk.map { FileUpload.fromData(it.file, it.uploadName()) }).complete()
            } catch (e: Exception) {
                val reason = e.message ?: e.toString()
                throw IllegalStateException(if (uploaded == 0) reason else "Sent $uploaded of ${media.size} files, then: $reason", e)
            }
            uploaded += chunk.size
        }
    }

    /** Discord shows images and videos inline by their extension, so a name without one gets it from the type. */
    private fun MediaFile.uploadName(): String {
        if (name.substringAfterLast('/').contains('.'))
            return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (ext.isNullOrEmpty()) name else "$name.$ext"
    }

    private fun MediaSendResult.withRoom(room: String): MediaSendResult =
        MediaSendResult(ok, room, sent, failed, error, errors, via)
}
