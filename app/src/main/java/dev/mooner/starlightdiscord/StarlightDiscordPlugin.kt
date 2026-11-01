package dev.mooner.starlightdiscord

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.InputType
import dev.mooner.configdsl.ConfigActionScope
import dev.mooner.configdsl.ConfigDSL
import dev.mooner.configdsl.ConfigStructure
import dev.mooner.configdsl.Icon
import dev.mooner.configdsl.config
import dev.mooner.configdsl.options.*
import dev.mooner.configdsl.utils.PeekType
import dev.mooner.starlight.plugincore.chat.ChatSender
import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.config.data.ConfigData
import dev.mooner.starlight.plugincore.config.data.MutableConfig
import dev.mooner.starlight.plugincore.logger.LoggerFactory
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate
import dev.mooner.starlightdiscord.event.DiscordMessageCreateEvent
import dev.mooner.starlightdiscord.event.DiscordOnMessageCompatEvent
import dev.mooner.starlightdiscord.event.DiscordResponseCompatEvent
import dev.mooner.starlightdiscord.models.DiscordChatRoomImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.JDAInfo
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Activity.ActivityType
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.update.GuildUpdateNameEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.CloseCode
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.internal.utils.JDALogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import dev.mooner.starlightdiscord.api.DiscordApi as DiscordStarlightApi

private val LOG = LoggerFactory.logger("DiscordPlugin")

@Suppress("unused")
class StarlightDiscordPlugin: StarlightPlugin() {

    init {
        initJdaLogger()
    }

    @Volatile
    private var _jda: JDA? = null
    private val isRunning
        get() = _jda != null

    // The client is started and stopped on this thread only. Logging in does network I/O, which Android forbids
    // on the main thread (config buttons are clicked there), and JDA looks up services through the context class
    // loader, which has to be this plugin's.
    private val clientExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StarlightDiscord-Client").apply {
            isDaemon = true
            contextClassLoader = StarlightDiscordPlugin::class.java.classLoader
        }
    }
    private val clientDispatcher = clientExecutor.asCoroutineDispatcher()

    // Shown by the settings screen while it is open.
    private val clientState = MutableStateFlow<ClientState>(ClientState.Offline)
    private val guildNames  = MutableStateFlow<List<String>>(emptyList())
    // Kept in its own file and saved as soon as it is picked: config buttons can't store values.
    private val selectedIntents = MutableStateFlow(DEFAULT_INTENTS)

    // Kept in the app's internal storage, not in config-plugin.json on shared storage: whoever holds it controls the bot.
    @Volatile
    private var token              : String? = null
    // Set when Discord rejects the saved token, e.g. after it was reset in the Developer Portal.
    @Volatile
    private var isTokenRejected    : Boolean = false
    private var excludeSelf        : Boolean = true
    private var excludeBot         : Boolean = true
    private var logReceivedMessage : Boolean = false
    private var useCompatEvent     : Boolean = false
    private var useLegacyEvent     : Boolean = false
    private var onlineStatus       : OnlineStatus = OnlineStatus.ONLINE
    private var activity           : Activity? = null

    private val avatarBitmapCache  : MutableMap<Long, Bitmap> = ConcurrentHashMap()

    // Only checks tokens entered in the setup; the client makes its own.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(TOKEN_CHECK_TIMEOUT)
            .build()
    }

    private val listener = object : ListenerAdapter() {

        override fun onReady(event: ReadyEvent) {
            LOG.info {
                translate {
                    Locale.ENGLISH { "Discord client ready! Logged in as ${event.jda.selfUser.name}" }
                    Locale.KOREAN  { "디스코드 클라이언트 시작 성공! ${event.jda.selfUser.name} 로 로그인했어요." }
                }
            }
            refreshGuilds(event.jda)
        }

        override fun onStatusChange(event: StatusChangeEvent) {
            clientState.value = when (event.newStatus) {
                JDA.Status.CONNECTED -> ClientState.Online(event.jda.selfUser.name)
                // Reported by onShutdown(), destroyClient() and startClient().
                JDA.Status.SHUTTING_DOWN, JDA.Status.SHUTDOWN, JDA.Status.FAILED_TO_LOGIN -> return
                else -> ClientState.Connecting
            }
        }

        override fun onShutdown(event: ShutdownEvent) {
            // destroyClient() detaches this listener first, so this is a shutdown nobody asked for.
            _jda = null
            DiscordStarlightApi.api = null
            guildNames.value = emptyList()
            val reason = event.closeCode?.meaning ?: "code ${event.code}"
            clientState.value = ClientState.Failed(reason)
            if (event.closeCode == CloseCode.AUTHENTICATION_FAILED)
                onTokenRejected()
            LOG.error {
                translate {
                    Locale.ENGLISH { "Discord client stopped: $reason" }
                    Locale.KOREAN  { "디스코드 클라이언트가 종료되었어요: $reason" }
                }
            }
        }

        override fun onGuildJoin(event: GuildJoinEvent) =
            refreshGuilds(event.jda)

        override fun onGuildLeave(event: GuildLeaveEvent) =
            refreshGuilds(event.jda)

        override fun onGuildUpdateName(event: GuildUpdateNameEvent) =
            refreshGuilds(event.jda)

        override fun onMessageReceived(event: MessageReceivedEvent) =
            handleMessage(event)
    }

    override fun onEnable() {
        updateConfig(getPluginConfig())
        token = loadToken()
        selectedIntents.value = loadIntents()
        addApi(DiscordStarlightApi())

        registerProjectEvents {
            category("message") {
                add<DiscordMessageCreateEvent>()
                add<DiscordOnMessageCompatEvent>()
                add<DiscordResponseCompatEvent>()
            }
        }

        if (token != null)
            clientExecutor.execute(::startClient)
        else
            LOG.info {
                translate {
                    Locale.ENGLISH { "No bot token yet. The client starts once the initial setup is done." }
                    Locale.KOREAN  { "봇 토큰이 없어요. 초기 설정을 마치면 클라이언트를 시작해요." }
                }
            }
    }

    override fun isSetupRequired(): Boolean =
        token == null || isTokenRejected

    override fun getSetupReason(): String? =
        if (isTokenRejected)
            translate {
                Locale.ENGLISH { "Discord rejected the saved bot token. Enter a new one." }
                Locale.KOREAN  { "저장된 봇 토큰을 디스코드가 거부했어요. 새 토큰을 입력하세요." }
            }
        else
            null

    /**
     * Asks for the setup again. Only for a token Discord refused: network or Discord outages leave the
     * token alone, since asking for it then would have the user re-enter a token that still works.
     */
    private fun onTokenRejected() {
        if (isTokenRejected)
            return
        isTokenRejected = true
        LOG.warn {
            translate {
                Locale.ENGLISH { "Discord rejected the saved bot token. Asking for the initial setup again." }
                Locale.KOREAN  { "저장된 봇 토큰을 디스코드가 거부했어요. 초기 설정을 다시 요청해요." }
            }
        }
        // StarLight builds from before the setup API lack requestSetup(); uncaught, this would end the app.
        try {
            requestSetup()
        } catch (e: LinkageError) {
            LOG.debug { "This StarLight build can't ask for the setup again: $e" }
        }
    }

    override fun getSetupStructure(): ConfigStructure = config {
        category {
            id = CATEGORY_SETUP
            title = translate {
                Locale.ENGLISH { "Bot" }
                Locale.KOREAN  { "봇" }
            }
            textColor = color { "#ab9df2" }
            items {
                string {
                    id = KEY_TOKEN
                    title = translate {
                        Locale.ENGLISH { "Bot Token" }
                        Locale.KOREAN  { "봇 토큰" }
                    }
                    description = translate {
                        Locale.ENGLISH { "Copy it from Bot > Token in the Discord Developer Portal." }
                        Locale.KOREAN  { "디스코드 개발자 포털의 Bot > Token에서 복사하세요." }
                    }
                    hint = translate {
                        Locale.ENGLISH { "Enter token for bot to log in..." }
                        Locale.KOREAN  { "로그인할 봇의 토큰을 입력하세요..." }
                    }
                    // Anyone holding the token controls the bot, so it is masked like a password.
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    require = ::tokenFormatError
                    icon = Icon.KEY
                    iconTintColor = color { "#ab9df2" }
                }
            }
        }
    }

    /** Keeps the token only once Discord accepts it, then (re)starts the client with it. */
    override suspend fun onSetup(values: ConfigData): String? {
        val newToken = values.category(CATEGORY_SETUP).getString(KEY_TOKEN)?.trim().orEmpty()
        tokenFormatError(newToken)?.let { return it }
        // On the client thread: it is allowed network I/O and has this plugin's context class loader.
        withContext(clientDispatcher) { verifyToken(newToken) }?.let { return it }
        withContext(Dispatchers.IO) { saveToken(newToken) }
        token = newToken
        isTokenRejected = false
        clientExecutor.execute(::restartClient)
        return null
    }

    private fun tokenFormatError(token: String): String? =
        when {
            token.isBlank() -> translate {
                Locale.ENGLISH { "Enter the bot token." }
                Locale.KOREAN  { "봇 토큰을 입력하세요." }
            }
            token.any(Char::isWhitespace) -> translate {
                Locale.ENGLISH { "Token can't contain whitespace." }
                Locale.KOREAN  { "토큰에는 공백이 들어갈 수 없어요." }
            }
            else -> null
        }

    /** Runs on [clientExecutor]. Returns why Discord didn't accept [token], or null when it did. */
    private fun verifyToken(token: String): String? {
        val request = Request.Builder()
            .url(TOKEN_CHECK_URL)
            .header("Authorization", "Bot $token")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200  -> null
                    401  -> translate {
                        Locale.ENGLISH { "Discord rejected this token. Check it and try again." }
                        Locale.KOREAN  { "디스코드가 이 토큰을 거부했어요. 토큰을 확인하고 다시 시도하세요." }
                    }
                    else -> translate {
                        Locale.ENGLISH { "Couldn't check the token (HTTP ${response.code}). Try again later." }
                        Locale.KOREAN  { "토큰을 확인하지 못했어요. (HTTP ${response.code}) 잠시 후 다시 시도하세요." }
                    }
                }
            }
        } catch (e: IOException) {
            translate {
                Locale.ENGLISH { "Couldn't reach Discord: ${e.message ?: e}" }
                Locale.KOREAN  { "디스코드에 연결하지 못했어요: ${e.message ?: e}" }
            }
        }
    }

    private val tokenFile: File
        get() = File(getInternalDataDirectory(), TOKEN_FILE_NAME)

    /** Reads the saved token, moving one left in config-plugin.json by plugin 0.2.0 and earlier. */
    private fun loadToken(): String? {
        val file = tokenFile
        if (file.isFile)
            return file.readText().trim().takeIf(String::isNotEmpty)

        val config = getPluginConfig() as? MutableConfig ?: return null
        val legacy = config.categoryOrNull(CATEGORY_GENERAL)
            ?.getString(KEY_TOKEN)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        try {
            saveToken(legacy)
        } catch (e: IOException) {
            LOG.warn { "Failed to move the bot token out of config-plugin.json: $e" }
            return legacy
        }
        config.edit { category(CATEGORY_GENERAL).remove(KEY_TOKEN) }
        return legacy
    }

    private fun saveToken(token: String) {
        val file = tokenFile
        file.parentFile?.mkdirs()
        file.writeText(token)
    }

    override fun onDisable() {
        clientExecutor.execute(::destroyClient)
    }

    override fun onConfigUpdated(config: ConfigData, updated: Map<String, Set<String>>) {
        super.onConfigUpdated(config, updated)
        updateConfig(config)
        if ("presence" in updated)
            _jda?.presence?.setPresence(onlineStatus, activity)
    }

    private fun updateConfig(config: ConfigData) {
        logReceivedMessage = config
            .category("dev_mode")
            .getBoolean("log_received_message", false)
        useCompatEvent = config
            .category("event")
            .getBoolean("use_compat_event", false)
        useLegacyEvent = config
            .category("event")
            .getBoolean("use_legacy_event", false)

        with(config.category("presence")) {
            onlineStatus = OnlineStatus.fromKey(getString("online_status", OnlineStatus.ONLINE.key))
                .takeIf { it != OnlineStatus.UNKNOWN }
                ?: OnlineStatus.ONLINE
            val activityType = ActivityType.entries.find { it.name == getString("activity_type") }
            val activityText = getString("activity_text")?.takeIf(String::isNotBlank)
            // Rejects text Discord would not accept, so a bad value can't stop the plugin from enabling.
            activity = if (activityType != null && activityText != null)
                runCatching { Activity.of(activityType, activityText) }.getOrNull()
            else
                null
        }

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
            val reason = translate {
                Locale.ENGLISH { "token is null" }
                Locale.KOREAN  { "token이 설정되지 않았어요." }
            }
            clientState.value = ClientState.Failed(reason)
            LOG.error {
                translate {
                    Locale.ENGLISH { "Failed to init discord client: $reason" }
                    Locale.KOREAN  { "디스코드 서버 시작 실패: $reason" }
                }
            }
            return
        }

        val intents = selectedIntents.value
        LOG.debug { "Starting discord client with additional intents: ${intents.joinToString { it.name }}" }
        val enabledIntents = GatewayIntent.getIntents(GatewayIntent.DEFAULT) + intents
        clientState.value = ClientState.Connecting
        try {
            val jda = JDABuilder.createDefault(token)
                .enableIntents(intents)
                // JDA disables these by itself with a warning when their intent is missing.
                .disableCache(CacheFlag.entries.filter { flag ->
                    flag.requiredIntent.let { it != null && it !in enabledIntents }
                })
                .setStatus(onlineStatus)
                .setActivity(activity)
                .addEventListeners(listener)
                .build()
            _jda = jda
            DiscordStarlightApi.api = jda
        } catch (e: Exception) {
            clientState.value = ClientState.Failed(e.message ?: e.toString())
            LOG.error {
                translate {
                    Locale.ENGLISH { "Failed to init discord client: $e" }
                    Locale.KOREAN  { "디스코드 서버 시작 실패: $e" }
                }
            }
            if (e is InvalidTokenException)
                onTokenRejected()
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
        jda.removeEventListener(listener)
        clientState.value = ClientState.Offline
        guildNames.value = emptyList()
        jda.shutdown()
        if (!jda.awaitShutdown(SHUTDOWN_TIMEOUT))
            jda.shutdownNow()
    }

    private fun restartClient() {
        destroyClient()
        startClient()
    }

    private val intentsFile: File
        get() = File(getExternalDataDirectory(), INTENTS_FILE_NAME)

    private fun loadIntents(): List<GatewayIntent> {
        val file = intentsFile
        if (!file.exists())
            return DEFAULT_INTENTS
        val names = try {
            Json.parseToJsonElement(file.readText())
                .jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } catch (e: Exception) {
            LOG.warn { "Failed to read ${file.name}, using the default intents: $e" }
            return DEFAULT_INTENTS
        }
        return OPTIONAL_INTENTS.filter { it.name in names }
    }

    private fun saveIntents(intents: List<GatewayIntent>) {
        intentsFile.writeText(JsonArray(intents.map { JsonPrimitive(it.name) }).toString())
    }

    private suspend fun ConfigActionScope.multiSelect(title: String, items: List<String>, selected: Set<Int>): Set<Int>? =
        suspendCancellableCoroutine { cont ->
            ConfigDSL.dialogProvider.multiSelect(context, title, items, selected) { if (cont.isActive) cont.resume(it) }
        }

    /**
     * JDALogger decides once, when its class initializes, whether to use SLF4J, by looking for a provider through
     * the context class loader of whichever thread reaches it first. Reading the config already touches JDA
     * (Activity.of) on StarLight's threads, so it is initialized here with this plugin's loader. Otherwise JDA
     * logs to stderr instead of StarLight.
     */
    private fun initJdaLogger() {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = StarlightDiscordPlugin::class.java.classLoader
        try {
            JDALogger.SLF4J_ENABLED
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun refreshGuilds(jda: JDA) {
        guildNames.value = jda.guilds.map { it.name }
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

    private fun ConfigActionScope.peekClientState() {
        when (val state = clientState.value) {
            is ClientState.Failed -> peek(state.reason, PeekType.ERROR)
            ClientState.Offline   -> peek("클라이언트를 종료했어요.", PeekType.SUCCESS)
            else                  -> peek("클라이언트를 시작했어요.", PeekType.SUCCESS)
        }
    }

    private fun toggleTitle(isActive: Boolean): String =
        "봇 클라이언트 ${if (isActive) "종료" else "시작"}"

    override fun getConfigStructure(): ConfigStructure = config {
        category {
            id = CATEGORY_GENERAL
            title = translate {
                Locale.ENGLISH { "General" }
                Locale.KOREAN  { "일반" }
            }
            textColor = color { "#ab9df2" }
            items {
                info {
                    id = "token_state"
                    title = translate {
                        Locale.ENGLISH { "Bot Token" }
                        Locale.KOREAN  { "봇 토큰" }
                    }
                    value = when {
                        isTokenRejected -> translate {
                            Locale.ENGLISH { "Rejected" }
                            Locale.KOREAN  { "거부됨" }
                        }
                        token != null -> translate {
                            Locale.ENGLISH { "Set" }
                            Locale.KOREAN  { "설정됨" }
                        }
                        else -> translate {
                            Locale.ENGLISH { "Not set" }
                            Locale.KOREAN  { "설정 안 됨" }
                        }
                    }
                    description = translate {
                        Locale.ENGLISH { "Change it from Setup in the plugin list or the plugin info screen." }
                        Locale.KOREAN  { "플러그인 목록이나 플러그인 정보 화면의 '초기 설정'에서 바꿀 수 있어요." }
                    }
                    icon = Icon.KEY
                    iconTintColor = color { "#ab9df2" }
                }
                textList {
                    id = "intents"
                    title = "Gateway Intents"
                    icon = Icon.BOOKMARK
                    iconTintColor = color { "#ab9df2" }
                    emptyText = translate {
                        Locale.ENGLISH { "JDA's default intents only" }
                        Locale.KOREAN  { "JDA 기본 인텐트만 사용해요." }
                    }
                    lines = selectedIntents.value.map(::intentLabel)
                    bind(selectedIntents.map { intents -> intents.map(::intentLabel) })
                }
                button {
                    id = "select_intents"
                    title = translate {
                        Locale.ENGLISH { "Select Intents" }
                        Locale.KOREAN  { "인텐트 선택" }
                    }
                    description = translate {
                        Locale.ENGLISH { "Added to JDA's defaults. ★ needs the Developer Portal too. Saved as soon as you confirm." }
                        Locale.KOREAN  { "JDA 기본 인텐트에 더해 켜요. ★는 개발자 포털에서도 켜야 해요. 확인하면 바로 저장돼요." }
                    }
                    icon = Icon.EDIT
                    iconTintColor = color { "#ab9df2" }
                    action {
                        val current = selectedIntents.value
                        val picked = multiSelect(
                            title = "Gateway Intents",
                            items = OPTIONAL_INTENTS.map(::intentLabel),
                            selected = OPTIONAL_INTENTS.indices.filterTo(hashSetOf()) { OPTIONAL_INTENTS[it] in current },
                        ) ?: return@action
                        val intents = OPTIONAL_INTENTS.filterIndexed { index, _ -> index in picked }
                        if (intents == current)
                            return@action
                        withContext(Dispatchers.IO) { saveIntents(intents) }
                        selectedIntents.value = intents

                        val restart = isRunning && confirm(
                            translate {
                                Locale.ENGLISH { "Apply Intents" }
                                Locale.KOREAN  { "인텐트 적용" }
                            },
                            translate {
                                Locale.ENGLISH { "Saved. Restart the client now to apply them?" }
                                Locale.KOREAN  { "저장했어요. 지금 클라이언트를 다시 시작해서 적용할까요?" }
                            },
                        )
                        if (restart) {
                            withContext(clientDispatcher) { restartClient() }
                            peekClientState()
                        } else
                            peek(translate {
                                Locale.ENGLISH { "Saved. Applied when the client restarts." }
                                Locale.KOREAN  { "저장했어요. 클라이언트를 다시 시작하면 적용돼요." }
                            }, PeekType.SUCCESS)
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
                info {
                    id = "state"
                    title = translate {
                        Locale.ENGLISH { "Client State" }
                        Locale.KOREAN  { "클라이언트 상태" }
                    }
                    icon = Icon.POWER
                    copyable = true
                    bind(clientState.map(ClientState::toInfoState))
                }
                info {
                    id = "gateway_ping"
                    title = "게이트웨이 핑"
                    // Read once when the screen opens. Redrawing rows while a text field has focus scrolls the
                    // screen back to that field, so this is not refreshed periodically.
                    value = _jda?.gatewayPing?.takeIf { it >= 0 }?.let { "${it}ms" }
                    icon = Icon.TIMER
                    iconTintColor = color { "#ab9df2" }
                }
                textList {
                    id = "guilds"
                    title = "참여 중인 서버"
                    icon = Icon.LIST_BULLETED
                    iconTintColor = color { "#ab9df2" }
                    emptyText = "참여 중인 서버가 없어요."
                    copyable = true
                    bind(guildNames)
                }
                button {
                    id = "toggle_client"
                    title = toggleTitle(clientState.value.isActive)
                    description = "클라이언트를 시작하거나 종료합니다."
                    icon = Icon.POWER
                    iconTintColor = color { "#FF6188" }
                    bindTitle(clientState.map { toggleTitle(it.isActive) })
                    action {
                        val stop = isRunning
                        if (stop && !confirm(toggleTitle(true), "클라이언트를 종료할까요?"))
                            return@action
                        withContext(clientDispatcher) {
                            if (stop)
                                destroyClient()
                            else
                                startClient()
                        }
                        peekClientState()
                    }
                }
                button {
                    id = "restart_client"
                    title = "봇 클라이언트 재시작"
                    description = "클라이언트를 종료 후 다시 시작합니다.\n모든 동적으로 등록된 리스너는 제거됩니다."
                    icon = Icon.REFRESH
                    style = ButtonStyle.DANGER
                    confirm("클라이언트를 다시 시작할까요?\n모든 동적으로 등록된 리스너는 제거됩니다.")
                    action {
                        withContext(clientDispatcher) { restartClient() }
                        peekClientState()
                    }
                }
                button {
                    id = "invite"
                    title = "봇 초대 링크 열기"
                    description = "봇을 서버에 초대하는 링크를 엽니다. 클라이언트가 작동 중일 때만 쓸 수 있어요."
                    icon = Icon.LINK
                    iconTintColor = color { "#ab9df2" }
                    action {
                        val url = _jda?.getInviteUrl(INVITE_PERMISSIONS)
                        if (url == null) {
                            peek("클라이언트가 작동 중이 아니에요.", PeekType.ERROR)
                            return@action
                        }
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
        category {
            id = "presence"
            title = "상태 표시"
            textColor = color { "#706EB9" }
            items {
                choice {
                    id = "online_status"
                    title = "온라인 상태"
                    description = "봇 프로필에 보이는 상태입니다."
                    icon = Icon.NIGHTS_STAY
                    iconTintColor = color { "#ab9df2" }
                    option(OnlineStatus.ONLINE.key, "온라인")
                    option(OnlineStatus.IDLE.key, "자리 비움")
                    option(OnlineStatus.DO_NOT_DISTURB.key, "방해 금지")
                    option(OnlineStatus.INVISIBLE.key, "오프라인 표시")
                }
                choice {
                    id = "activity_type"
                    title = "활동"
                    icon = Icon.STAR
                    iconTintColor = color { "#ab9df2" }
                    option(ACTIVITY_NONE, "없음")
                    option(ActivityType.PLAYING.name, "플레이 중")
                    option(ActivityType.LISTENING.name, "듣는 중")
                    option(ActivityType.WATCHING.name, "시청 중")
                    option(ActivityType.COMPETING.name, "참가 중")
                    option(ActivityType.CUSTOM_STATUS.name, "사용자 지정 상태")
                }
                string {
                    id = "activity_text"
                    title = "활동 내용"
                    hint = "예: !ping 으로 핑 확인"
                    icon = Icon.EDIT
                    iconTintColor = color { "#ab9df2" }
                    require = { text ->
                        if (text.length > Activity.MAX_ACTIVITY_NAME_LENGTH)
                            "최대 ${Activity.MAX_ACTIVITY_NAME_LENGTH}자까지 쓸 수 있어요."
                        else
                            null
                    }
                    visibleWhen("activity_type") { (it as? JsonPrimitive)?.contentOrNull != ACTIVITY_NONE }
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
                    visibleWhen("use_compat_event", true)
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
                info {
                    id = "jda_version"
                    title = "JDA 버전"
                    value = JDAInfo.VERSION
                    copyable = true
                    icon = Icon.BRANCH
                    iconTintColor = color { "#ab9df2" }
                }
            }
        }
    }

    private sealed interface ClientState {

        val isActive: Boolean
            get() = this is Connecting || this is Online

        fun toInfoState(): InfoState = when (this) {
            Offline       -> InfoState(translate {
                Locale.ENGLISH { "Offline" }
                Locale.KOREAN  { "종료됨" }
            })
            Connecting    -> InfoState(null, OptionStatus.LOADING)
            is Online     -> InfoState(userName, OptionStatus.OK)
            is Failed     -> InfoState(reason, OptionStatus.ERROR)
        }

        data object Offline : ClientState
        data object Connecting : ClientState
        data class Online(val userName: String) : ClientState
        data class Failed(val reason: String) : ClientState
    }

    companion object {
        // Javacord stored intent indices under "intents" and plugin 0.1.0 stored JDA's under "gateway_intents" in
        // config-plugin.json. Names are kept in a file of their own, so neither is misread.
        private const val INTENTS_FILE_NAME = "gateway-intents.json"
        // In getInternalDataDirectory(): kept when the plugin is removed with its data kept, deleted otherwise.
        private const val TOKEN_FILE_NAME = "bot-token"
        private const val TOKEN_CHECK_URL = "https://discord.com/api/v10/users/@me"
        private const val CATEGORY_GENERAL = "general"
        private const val CATEGORY_SETUP = "setup"
        private const val KEY_TOKEN = "token"
        private const val ACTIVITY_NONE = "none"
        // The intents GatewayIntent.DEFAULT leaves out. The client always enables the others.
        private val OPTIONAL_INTENTS = GatewayIntent.entries - GatewayIntent.getIntents(GatewayIntent.DEFAULT)
        private val PRIVILEGED_INTENTS = EnumSet.of(
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_PRESENCES,
            GatewayIntent.MESSAGE_CONTENT,
        )
        private val DEFAULT_INTENTS = listOf(GatewayIntent.MESSAGE_CONTENT)
        private val INVITE_PERMISSIONS = EnumSet.of(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_ADD_REACTION,
        )
        private const val AVATAR_SIZE = 128
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val TOKEN_CHECK_TIMEOUT: Duration = Duration.ofSeconds(15)

        private fun intentLabel(intent: GatewayIntent): String =
            if (intent in PRIVILEGED_INTENTS) "★ ${intent.name}" else intent.name
    }
}
