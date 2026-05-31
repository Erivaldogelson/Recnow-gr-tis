package com.erivaldogelson.recnow

data class RecordingQuality(
    val label: String,
    val landscapeWidth: Int,
    val landscapeHeight: Int,
    val frameRate: Int,
    val bitrate: Int
) {
    val shortLabel: String = when (landscapeHeight) {
        1080 -> "Full HD ${frameRate}Hz"
        1440 -> "2K ${frameRate}Hz"
        2160 -> "4K ${frameRate}Hz"
        else -> "${landscapeHeight}p ${frameRate}Hz"
    } + " HDR"
}

val RecordingQualities = listOf(
    RecordingQuality("720p leve", 1280, 720, 60, 8_000_000),
    RecordingQuality("Full HD", 1920, 1080, 60, 16_000_000),
    RecordingQuality("1060p fluido", 1884, 1060, 60, 16_000_000),
    RecordingQuality("2K fluido", 2560, 1440, 60, 28_000_000),
    RecordingQuality("1060p ultra", 1884, 1060, 120, 28_000_000),
    RecordingQuality("2K ultra", 2560, 1440, 120, 50_000_000),
    RecordingQuality("4K cinema", 3840, 2160, 30, 45_000_000),
    RecordingQuality("4K ultra", 3840, 2160, 120, 120_000_000)
)
