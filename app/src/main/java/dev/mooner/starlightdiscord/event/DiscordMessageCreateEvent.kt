package dev.mooner.starlightdiscord.event

import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate
import net.dv8tion.jda.api.events.message.MessageReceivedEvent

class DiscordMessageCreateEvent: ProjectEvent() {

    override val functionName: String = "onMessageCreate"

    override val id: String = "create"

    override val name: String = "디스코드 메세지 생성"

    override val functionComment: String = translate {
        Locale.ENGLISH {
            """
                |Called when a discord message is received
                |Visit URL below for event structure:
                |$EVENT_DOCS
            """.trimMargin()
        }
        Locale.KOREAN {
            """
                |디스코드 메세지가 수신 되었을 때 이 함수가 호출됩니다.
                |이벤트 객체의 구조는 아래 링크를 참조하세요:
                |$EVENT_DOCS
            """.trimMargin()
        }
    }

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf("event" typedAs MessageReceivedEvent::class)

    private companion object {
        const val EVENT_DOCS = "https://docs.jda.wiki/net/dv8tion/jda/api/events/message/MessageReceivedEvent.html"
    }
}
