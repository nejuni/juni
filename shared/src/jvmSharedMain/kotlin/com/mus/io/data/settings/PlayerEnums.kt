package com.nejuni.juni.data.settings

/**
 * Stream bitrate ceiling on the YouTube path, capping the Opus ladder the
 * resolver is allowed to pick.
 *
 * - [LOW] — the smallest download, ~64 kbps.
 * - [MEDIUM], [HIGH] and [LOSSLESS] — whatever the best available Opus format
 *   is.
 */
enum class AudioQuality(
    val label: String,
    val detail: String,
) {
    LOW("Low", "~64 kbps · smallest download"),
    MEDIUM("Medium", "Best available · ~171 kbps Opus"),
    HIGH("High", "Best available · ~171 kbps Opus"),
    LOSSLESS("Lossless", "Best available · ~171 kbps Opus"),
    ;
}

/** What the phone's main player stands on, below the artwork. */
enum class PlayerTheme {
    /** The artwork's own colours hung off its bottom edge, as a mesh. */
    DEFAULT,

    /** v1.5's four quantised blobs, drifting behind the whole screen. */
    LEGACY,

    /** The cover blurred across the whole screen, as the lyrics and queue are. */
    SIMPLE,
}

/** The surface that was last open inside the expanded player. */
enum class LastPlayerScreen {
    MAIN,
    LYRICS,
    QUEUE,
}
