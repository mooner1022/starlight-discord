package dev.mooner.starlightdiscord.models

import dev.mooner.starlight.plugincore.chat.ChatRoom
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel

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
}
