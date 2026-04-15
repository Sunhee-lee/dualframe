package com.dualframe.util

/** Format seconds into MM:SS for the recording timer display. */
fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
