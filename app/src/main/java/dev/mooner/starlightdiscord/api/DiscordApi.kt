package dev.mooner.starlightdiscord.api

import dev.mooner.starlight.plugincore.api.Api
import dev.mooner.starlight.plugincore.api.ApiObject
import dev.mooner.starlight.plugincore.api.InstanceType
import dev.mooner.starlight.plugincore.project.Project
import net.dv8tion.jda.api.JDA

/** Registers [Discord] as the global `Discord`. */
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

    /**
     * The Discord plugin's bot, as the global `Discord`.
     *
     * @see guide:setup
     */
    // An object rather than a class with a companion: the KDoc of a companion's @JvmStatic function is filed under the
    // companion, while scripts call the static method of this class.
    object Discord {

        /**
         * The bot's JDA client, for anything the events don't cover: `Discord.getApi().getGuilds()`.
         *
         * Avoid JDA's blocking calls, such as `complete()`, in JDA's own event listeners.
         *
         * @return JDA's `JDA` object.
         * @throws IllegalStateException If the bot isn't running, such as before its token is set.
         */
        @JvmStatic
        fun getApi(): JDA {
            return api ?: throw IllegalStateException("Discord client is not running")
        }
    }

    companion object {
        @Volatile
        internal var api: JDA? = null
    }
}
