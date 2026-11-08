package dev.mooner.starlightdiscord.event

import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate

/**
 * The Discord bot received a message, given as StarLight's message so scripts written for messengers handle it too.
 *
 * Called after `onMessageCreate`, only while the plugin's "onMessage 이벤트 사용" setting is on. It calls the same
 * `onMessage` function as StarLight's message event, so one function handles both; `event.packageName` is
 * `"discord"` for Discord messages, and `event.room` sends to the Discord channel.
 *
 * @param event The message.
 */
class DiscordOnMessageCompatEvent: ProjectEvent() {

    override val functionName: String = "onMessage"

    override val id: String = "compat"

    override val name: String = "onMessage 호환 이벤트"

    override val description: String = translate {
        Locale.ENGLISH { "When the Discord bot receives a message, with the onMessage setting on" }
        Locale.KOREAN  { "onMessage 이벤트 사용을 켰을 때, 디스코드 봇이 메시지를 받으면" }
    }

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf("event" typedAs Message::class)
}