package com.brokenfinger.tracker.support.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * What [source]'s logger said at [level] while [action] ran, formatted as it would be written. Logback is
 * what the application logs through. The appender comes off again whether or not [action] throws, and a
 * test fork runs one class at a time, so nothing another test logs is heard here.
 */
fun loggedWhile(source: KClass<*>, level: Level, action: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(source.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    runCatching(action).also { logger.detachAppender(appender) }.getOrThrow()
    return appender.list.filter { it.level == level }.map { it.formattedMessage }
}

/** What [source] warned about while [action] ran. */
fun warningsWhile(source: KClass<*>, action: () -> Unit): List<String> = loggedWhile(source, Level.WARN, action)
