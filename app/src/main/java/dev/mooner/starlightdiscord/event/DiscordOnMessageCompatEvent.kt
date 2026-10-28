package dev.mooner.starlightdiscord.event

import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent

class DiscordOnMessageCompatEvent: ProjectEvent() {

    override val functionName: String = "onMessage"

    override val id: String = "compat"

    override val name: String = "onMessage 호환 이벤트"

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf("event" typedAs Message::class)
}