package com.brokenfinger.tracker.support.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * What [source]'s logger said at [level] while [action] ran, each event as a layout writes it: the
 * formatted message, then the text of the throwable attached to it, if any. That text is written too,
 * and its first line is the exception's message, so a test that only read the message would miss
 * whatever an exception passed as the last argument carries. Logback is what the application logs
 * through.
 *
 * Fails when the logger is not enabled for [level], or a test asserting silence would pass because
 * nothing could have been said. The appender comes off again whether or not [action] throws, and a
 * test fork runs one class at a time, so nothing another test logs is heard here.
 */
fun loggedWhile(source: KClass<*>, level: Level, action: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(source.java) as Logger
    check(logger.isEnabledFor(level)) { "${source.simpleName} does not log at $level here, so silence proves nothing" }
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    runCatching(action).also { logger.detachAppender(appender) }.getOrThrow()
    return appender.list.filter { it.level == level }.map(::written)
}

/** What [source] warned about while [action] ran. */
fun warningsWhile(source: KClass<*>, action: () -> Unit): List<String> = loggedWhile(source, Level.WARN, action)

private fun written(event: ILoggingEvent): String =
    listOfNotNull(event.formattedMessage, event.throwableProxy?.let(ThrowableProxyUtil::asString)).joinToString("\n")
