package dev.mooner.starlightdiscord.api

import dev.mooner.starlight.plugincore.api.Api
import dev.mooner.starlight.plugincore.api.ApiObject
import dev.mooner.starlight.plugincore.api.InstanceType
import dev.mooner.starlight.plugincore.project.Project
import net.dv8tion.jda.api.JDA

class DiscordApi: Api<DiscordApi.Discord>() {

    override val instanceClass: Class<Discord> =
        Discord::class.java

    override val instanceType: InstanceType =
        InstanceType.CLASS

    override val name: String =
        "Discord"

    override val objects: List<ApiObject> =
        getApiObjects<Discord>()

    override fun getInstance(project: Project): Any =
        Discord::class

    class Discord {

        companion object {

            @JvmStatic
            fun getApi(): JDA {
                return api ?: throw IllegalStateException("Discord client is not running")
            }
        }
    }

    companion object {
        @Volatile
        internal var api: JDA? = null
    }
}
