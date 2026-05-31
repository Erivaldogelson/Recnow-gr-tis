package com.erivaldogelson.recnow

import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

data class RecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val qualityLabel: String = RecordingQualities.first().shortLabel,
    val lastFilePath: String? = null
) {
    val formattedElapsed: String
        get() {
            val duration = elapsedMs.milliseconds
            val hours = duration.inWholeHours
            val minutes = duration.inWholeMinutes % 60
            val seconds = duration.inWholeSeconds % 60
            return if (hours > 0) {
                String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
            }
        }
}
