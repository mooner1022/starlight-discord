package dev.mooner.starlightdiscord.logging

import dev.mooner.starlight.plugincore.logger.LoggerFactory
import dev.mooner.starlight.plugincore.logger.TLogger
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes SLF4J (used by JDA) to StarLight's logger. Registered in META-INF/services.
 *
 * JDA looks the provider up with the thread's context class loader, so JDA has to be initialized on a thread
 * whose context class loader is the plugin's (see StarlightDiscordPlugin.clientExecutor).
 */
class StarlightServiceProvider : SLF4JServiceProvider {

    private val loggerFactory = StarlightLoggerFactory()
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() {}
}

private class StarlightLoggerFactory : ILoggerFactory {

    private val loggers: MutableMap<String, Logger> = ConcurrentHashMap()

    override fun getLogger(name: String): Logger =
        loggers.getOrPut(name) { StarlightLogger(name) }
}

private class StarlightLogger(name: String) : LegacyAbstractLogger() {

    private val logger: TLogger = LoggerFactory.logger("Discord/" + name.substringAfterLast('.'))

    init {
        this.name = name
    }

    override fun isTraceEnabled(): Boolean = false

    override fun isDebugEnabled(): Boolean = true

    override fun isInfoEnabled(): Boolean = true

    override fun isWarnEnabled(): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val message = MessageFormatter.basicArrayFormat(messagePattern, arguments)
        when (level) {
            Level.TRACE -> {}
            Level.DEBUG -> logger.debug { message.withCause(throwable) }
            Level.INFO  -> logger.info { message.withCause(throwable) }
            Level.WARN  -> logger.warn { message.withCause(throwable) }
            Level.ERROR ->
                if (throwable != null)
                    logger.error(throwable) { message }
                else
                    logger.error { message }
        }
    }

    private fun String.withCause(throwable: Throwable?): String =
        if (throwable == null) this else "$this: $throwable"
}
