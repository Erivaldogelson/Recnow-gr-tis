package com.erivaldogelson.recnow

import android.os.Bundle

fun RecordingState.toBundle(): Bundle = Bundle().apply {
    putBoolean("isRecording", isRecording)
    putLong("elapsedMs", elapsedMs)
    putString("qualityLabel", qualityLabel)
    putString("lastFilePath", lastFilePath)
}

fun Bundle.toRecordingState(): RecordingState = RecordingState(
    isRecording = getBoolean("isRecording"),
    elapsedMs = getLong("elapsedMs"),
    qualityLabel = getString("qualityLabel") ?: RecordingQualities.first().shortLabel,
    lastFilePath = getString("lastFilePath")
)
