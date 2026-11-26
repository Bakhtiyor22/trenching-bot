package com.trenching.bot.notification.domain

/** A message ready for a channel: plain text, with each link already an absolute [RenderedLink.url]. The channel escapes it. */
data class RenderedNotification(val title: String, val lines: List<String>, val links: List<RenderedLink>)

data class RenderedLink(val label: String, val url: String)
