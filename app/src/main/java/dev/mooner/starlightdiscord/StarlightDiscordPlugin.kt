package dev.mooner.starlightdiscord

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updateMargins
import dev.mooner.configdsl.ConfigStructure
import dev.mooner.configdsl.Icon
import dev.mooner.configdsl.config
import dev.mooner.configdsl.options.*
import dev.mooner.starlight.plugincore.chat.ChatSender
import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.config.data.ConfigData
import dev.mooner.starlight.plugincore.logger.LogData
import dev.mooner.starlight.plugincore.logger.LoggerFactory
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate
import dev.mooner.starlightdiscord.event.DiscordMessageCreateEvent
import dev.mooner.starlightdiscord.event.DiscordOnMessageCompatEvent
import dev.mooner.starlightdiscord.event.DiscordResponseCompatEvent
import dev.mooner.starlightdiscord.models.DiscordChatRoomImpl
import dev.mooner.starlightdiscord.utils.dp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import dev.mooner.starlightdiscord.api.DiscordApi as DiscordStarlightApi

private val LOG = LoggerFactory.logger("DiscordPlugin")

@Suppress("unused")
class StarlightDiscordPlugin: StarlightPlugin() {

    @Volatile
    private var _jda: JDA? = null
    private val isRunning
        get() = _jda != null

    // The client is started and stopped on this thread only. Logging in does network I/O, which Android forbids
    // on the main thread (config buttons are clicked there), and JDA looks up its SLF4J provider through the
    // context class loader, which has to be this plugin's for StarlightServiceProvider to be found.
    private val clientExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StarlightDiscord-Client").apply {
            isDaemon = true
            contextClassLoader = StarlightDiscordPlugin::class.java.classLoader
        }
    }

    private var token              : String? = null
    private var excludeSelf        : Boolean = true
    private var excludeBot         : Boolean = true
    private var logReceivedMessage : Boolean = false
    private var useCompatEvent     : Boolean = false
    private var useLegacyEvent     : Boolean = false

    private val avatarBitmapCache  : MutableMap<Long, Bitmap> = ConcurrentHashMap()

    private val listener = object : ListenerAdapter() {

        override fun onReady(event: ReadyEvent) {
            LOG.info {
                translate {
                    Locale.ENGLISH { "Discord client ready! Logged in as ${event.jda.selfUser.name}" }
                    Locale.KOREAN  { "디스코드 클라이언트 시작 성공! ${event.jda.selfUser.name} 로 로그인했어요." }
                }
            }
        }

        override fun onShutdown(event: ShutdownEvent) {
            // Clients stopped by destroyClient() are already detached.
            if (event.jda !== _jda)
                return
            _jda = null
            DiscordStarlightApi.api = null
            val reason = event.closeCode?.meaning ?: "code ${event.code}"
            LOG.error {
                translate {
                    Locale.ENGLISH { "Discord client stopped: $reason" }
                    Locale.KOREAN  { "디스코드 클라이언트가 종료되었어요: $reason" }
                }
            }
        }

        override fun onMessageReceived(event: MessageReceivedEvent) =
            handleMessage(event)
    }

    override fun onEnable() {
        updateConfig(getPluginConfig())
        addApi(DiscordStarlightApi())

        registerProjectEvents {
            category("message") {
                add<DiscordMessageCreateEvent>()
                add<DiscordOnMessageCompatEvent>()
                add<DiscordResponseCompatEvent>()
            }
        }

        clientExecutor.execute(::startClient)
    }

    override fun onDisable() {
        clientExecutor.execute(::destroyClient)
    }

    override fun onConfigUpdated(config: ConfigData, updated: Map<String, Set<String>>) {
        super.onConfigUpdated(config, updated)
        updateConfig(config)
    }

    private fun updateConfig(config: ConfigData) {
        token = config.category("general").getString("token")
            ?.takeIf(String::isNotBlank)

        logReceivedMessage = config
            .category("dev_mode")
            .getBoolean("log_received_message", false)
        useCompatEvent = config
            .category("event")
            .getBoolean("use_compat_event", false)
        useLegacyEvent = config
            .category("event")
            .getBoolean("use_legacy_event", false)

        with(config.categoryOrNull("message") ?: return) {
            excludeSelf = getBoolean("exclude_self", true)
            excludeBot = getBoolean("exclude_bot", true)
        }
    }

    private fun handleMessage(event: MessageReceivedEvent) {
        val author = event.author
        if (logReceivedMessage)
            LOG.verbose { "[${author.name}]: ${event.message.contentDisplay}" }

        if (excludeSelf && author.idLong == event.jda.selfUser.idLong)
            return
        if (excludeBot && author.isBot)
            return

        fireProjectEvent<DiscordMessageCreateEvent>(event, onFailure = ::onEventFailure)

        if (!useCompatEvent)
            return
        val message = event.toCompatMessage()
        fireProjectEvent<DiscordOnMessageCompatEvent>(message, onFailure = ::onEventFailure)

        if (useLegacyEvent) {
            val imageDB = DiscordResponseCompatEvent.ImageDB(message.sender.profileBitmap)
            val replier = DiscordResponseCompatEvent.Replier { room, msgContent, _ ->
                if (room != null) {
                    val channel = event.jda.findChannelByName(room)
                        ?: return@Replier false
                    channel.sendMessage(msgContent).queue()
                    true
                } else
                    message.room.send(msgContent)
            }
            fireProjectEvent<DiscordResponseCompatEvent>(
                message.room.name,
                message.message,
                message.sender.name,
                message.room.isGroupChat,
                replier,
                imageDB,
                onFailure = ::onEventFailure
            )
        }
    }

    private fun onEventFailure(project: Project, e: Throwable) {
        LOG.error {
            translate {
                Locale.ENGLISH { "Error while calling event on project ${project.info.name}: ${e.message}" }
                Locale.KOREAN  { "프로젝트 ${project.info.name}에서 이벤트 호출 실패, 에러: ${e.message}" }
            }
        }
    }

    /** Runs on [clientExecutor]. */
    private fun startClient() {
        if (_jda != null)
            return
        val token = token ?: run {
            LOG.error {
                translate {
                    Locale.ENGLISH { "Failed to init discord client: token is null" }
                    Locale.KOREAN  { "디스코드 서버 시작 실패: token이 설정되지 않았어요." }
                }
            }
            return
        }

        val intents = getIntents()
        LOG.debug { "Starting discord client with additional intents: ${intents.joinToString { it.name }}" }
        val enabledIntents = GatewayIntent.getIntents(GatewayIntent.DEFAULT) + intents
        try {
            val jda = JDABuilder.createDefault(token)
                .enableIntents(intents)
                // JDA disables these by itself with a warning when their intent is missing.
                .disableCache(CacheFlag.entries.filter { flag ->
                    flag.requiredIntent.let { it != null && it !in enabledIntents }
                })
                .addEventListeners(listener)
                .build()
            _jda = jda
            DiscordStarlightApi.api = jda
        } catch (e: Exception) {
            LOG.error {
                translate {
                    Locale.ENGLISH { "Failed to init discord client: $e" }
                    Locale.KOREAN  { "디스코드 서버 시작 실패: $e" }
                }
            }
        }
    }

    /** Runs on [clientExecutor]. */
    private fun destroyClient() {
        val jda = _jda ?: return
        LOG.debug {
            translate {
                Locale.ENGLISH { "Destroying Discord client..." }
                Locale.KOREAN  { "디스코드 클라이언트 로그아웃중..." }
            }
        }
        _jda = null
        DiscordStarlightApi.api = null
        jda.shutdown()
        if (!jda.awaitShutdown(SHUTDOWN_TIMEOUT))
            jda.shutdownNow()
    }

    private fun restartClient() {
        destroyClient()
        startClient()
    }

    private fun getIntents(): List<GatewayIntent> {
        val config = getPluginConfig()
        val intents = GatewayIntent.entries
        return config
            .category("general")
            .getList(CONF_INTENTS)
            ?.map<_, IntentData>(Json::decodeFromJsonElement)
            ?.mapNotNull { intents.getOrNull(it.intentIdx) }
            ?: DEFAULT_INTENTS.toList()
    }

    private fun JDA.findChannelByName(name: String): MessageChannel? =
        getTextChannelsByName(name, false).firstOrNull()
            ?: getThreadChannelsByName(name, false).firstOrNull()
            ?: privateChannelCache.find { it.name == name }

    private fun MessageReceivedEvent.toCompatMessage(): Message =
        Message(
            chatLogId = messageIdLong,
            hasMention = message.mentions.users.isNotEmpty(),
            message = message.contentRaw,
            packageName = "discord",
            room = DiscordChatRoomImpl(channel),
            image = message.attachments
                .find(Attachment::isImage)
                ?.proxy
                ?.download()
                ?.get()
                ?.decodeBitmap(),
            sender = ChatSender(
                id = author.id,
                name = author.name,
                profileBitmap = avatarBitmapCache.getOrPut(author.idLong) {
                    author.effectiveAvatar.download(AVATAR_SIZE).get().decodeBitmap()
                        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
            )
        )

    private fun InputStream.decodeBitmap(): Bitmap? =
        use(BitmapFactory::decodeStream)

    @SuppressLint("SetTextI18n")
    override fun getConfigStructure(): ConfigStructure = config {
        category {
            id = "general"
            title = translate {
                Locale.ENGLISH { "General" }
                Locale.KOREAN  { "일반" }
            }
            textColor = color { "#ab9df2" }
            items {
                string {
                    id = "token"
                    title = translate {
                        Locale.ENGLISH { "Bot Token" }
                        Locale.KOREAN { "봇 토큰" }
                    }
                    hint = translate {
                        Locale.ENGLISH { "Enter token for bot to log in..." }
                        Locale.KOREAN  { "로그인할 봇의 토큰을 입력하세요..." }
                    }
                    icon = Icon.CLOUD
                    iconTintColor = color { "#ab9df2" }
                }
                list {
                    val intents = GatewayIntent.entries
                    val tvId = View.generateViewId()
                    id = CONF_INTENTS
                    title = "Gateway Intents"
                    icon = Icon.BOOKMARK
                    structure {
                        spinner {
                            id = "intent"
                            title = "Intent"
                            icon = Icon.ADD
                            items = intents.map(GatewayIntent::name)
                        }
                    }
                    default(*DEFAULT_INTENTS.map(IntentData::fromIntent).toTypedArray())
                    onInflate { view ->
                        view as FrameLayout
                        val context = view.context

                        val params = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply {
                            updateMargins(top = dp(4), bottom = dp(4))
                        }
                        val nLayout = LinearLayout(context).apply {
                            layoutParams = params
                            TextView(context).apply {
                                id = tvId
                                textSize = 16f
                                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                                    marginStart = dp(12)
                                    weight = 1f
                                    gravity = Gravity.CENTER
                                }
                            }.also(this::addView)
                        }

                        view.addView(nLayout)
                    }
                    onDraw<IntentData> { view, data ->
                        val intent = intents.getOrNull(data.intentIdx) ?: return@onDraw
                        view.findViewById<TextView>(tvId).text = "${intent.name} (${intent.offset})"
                    }
                }
            }
        }
        category {
            id = "control"
            title = translate {
                Locale.ENGLISH { "Control" }
                Locale.KOREAN  { "제어" }
            }
            textColor = color { "#706EB9" }
            items {
                button {
                    id = "state_indicator"
                    title = translate {
                        Locale.ENGLISH { "Client State : ${if (isRunning) "Online" else "Offline"}" }
                        Locale.KOREAN  { "클라이언트 상태 : ${if (isRunning) "작동 중" else "종료됨"}" }
                    }
                    icon = Icon.POWER
                    iconTintColor = color {
                        if (isRunning) "#4ADE80" else "#FF6188"
                    }
                }
                button {
                    val toggleState = if (isRunning) "종료" else "시작"
                    id = "toggle_client"
                    title = "봇 클라이언트 $toggleState"
                    description = "클라이언트를 ${toggleState}합니다."
                    icon = Icon.POWER
                    iconTintColor = color { "#FF6188" }
                    setOnClickListener { _ ->
                        LOG.info(LogData.FLAG_NOTIFY) { "클라이언트 $toggleState 중..." }
                        clientExecutor.execute {
                            if (isRunning)
                                destroyClient()
                            else
                                startClient()
                        }
                    }
                }
                button {
                    id = "restart_client"
                    title = "봇 클라이언트 재시작"
                    description = "클라이언트를 종료 후 다시 시작합니다.\n모든 동적으로 등록된 리스너는 제거됩니다."
                    icon = Icon.REFRESH
                    iconTintColor = color { "#FF6188" }
                    setOnClickListener { _ ->
                        LOG.info(LogData.FLAG_NOTIFY) { "클라이언트 재시작 중..." }
                        clientExecutor.execute(::restartClient)
                    }
                }
            }
        }
        category {
            id = "message"
            title = "메세지"
            textColor = color { "#706EB9" }
            items {
                toggle {
                    id = "exclude_self"
                    title = "본인 메세지 무시"
                    description = "스스로가 보낸 메세지를 무시합니다."
                    defaultValue = true
                    icon = Icon.SEND
                    iconTintColor = color { "#ab9df2" }
                }
                toggle {
                    id = "exclude_bot"
                    title = "봇 메세지 무시"
                    description = "스스로를 포함한 봇이 보낸 메세지를 무시합니다."
                    defaultValue = true
                    icon = Icon.LAYERS_CLEAR
                    iconTintColor = color { "#ab9df2" }
                    dependency = "exclude_self"
                }
            }
        }
        category {
            id = "event"
            title = "이벤트"
            textColor = color { "#706EB9" }
            items {
                toggle {
                    id = "use_compat_event"
                    title = "onMessage 이벤트 사용"
                    description = "카카오톡 이벤트에 사용되는 onMessage 함수를 디스코드 메세지 수신시에 호출합니다."
                    icon = Icon.MARK_CHAT_READ
                    iconTintColor = color { "#ab9df2" }
                }
                toggle {
                    id = "use_legacy_event"
                    title = "레거시(response) 이벤트 사용"
                    description = "카카오톡 이벤트에 사용되는 response 함수를 디스코드 메세지 수신시에 호출합니다."
                    icon = Icon.MARK_CHAT_READ
                    iconTintColor = color { "#ab9df2" }
                    dependency = "use_compat_event"
                }
            }
        }
        category {
            id = "dev_mode"
            title = "개발자 모드"
            flags = CategoryConfigOption.FLAG_DEV_MODE_ONLY
            textColor = color { "#706EB9" }
            items {
                toggle {
                    id = "log_received_message"
                    title = "수신 메세지 로그 표시"
                    description = "수신된 메세지를 로그에 표시합니다.\n(내부 로그 표시 활성화 필요)"
                    defaultValue = false
                    icon = Icon.MARK_CHAT_UNREAD
                    iconTintColor = color { "#ab9df2" }
                }
            }
        }
    }

    companion object {
        // Javacord's Intent indices were stored under "intents"; a new key keeps them from being read as JDA's.
        private const val CONF_INTENTS = "gateway_intents"
        // Added to GatewayIntent.DEFAULT, which already has the unprivileged message intents.
        private val DEFAULT_INTENTS = arrayOf(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES)
        private const val AVATAR_SIZE = 128
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(10)
    }

    /** An index into [GatewayIntent.entries], as stored by the intent spinner. */
    @Serializable
    data class IntentData(
        @SerialName("intent")
        val intentIdx: Int
    ) {
        companion object {
            fun fromIntent(intent: GatewayIntent) =
                IntentData(GatewayIntent.entries.indexOf(intent))
        }
    }
}
