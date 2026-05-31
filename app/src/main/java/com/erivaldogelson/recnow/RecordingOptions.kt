package com.erivaldogelson.recnow

data class RecordingOptions(
    val qualityIndex: Int = 1,
    val audioMode: AudioMode = AudioMode.NONE
) {
    val quality: RecordingQuality
        get() = RecordingQualities.getOrElse(qualityIndex) { RecordingQualities[1] }
}

enum class AudioMode {
    NONE,
    MICROPHONE,
    MEDIA,
    MEDIA_AND_MICROPHONE
}
