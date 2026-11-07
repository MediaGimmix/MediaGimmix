package com.example.mediaplayer

import java.text.DateFormat
import java.util.Date

/** 754000 -> "12:34", 4000000 -> "1:06:40". */
fun songLengthText(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = (total % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
}

/** A short, locale-formatted date; "Unknown" for 0. */
fun shortDateText(epochMs: Long): String =
    if (epochMs <= 0L) "Unknown" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))
