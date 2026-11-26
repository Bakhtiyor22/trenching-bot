package com.trenching.bot.notification.domain

import java.security.MessageDigest

object NotificationId {
    /** The primary key of a stored message: the lower-case hex SHA-256 of its notification key. */
    fun of(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
