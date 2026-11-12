package fr.bonamy.movies

/** Counts active playback, excluding loading, buffering, pauses and seeks. */
internal class PlaybackWatchTime(private val now: () -> Long) {
    private var elapsed = 0L
    private var playingSince: Long? = null
    val canCreateBookmark get() = elapsed + (playingSince?.let { (now() - it).coerceAtLeast(0) } ?: 0) >= MIN_PLAYBACK_MS

    fun setPlaying(playing: Boolean) {
        if (playing) {
            if (playingSince == null) playingSince = now()
        } else {
            playingSince?.let { elapsed += (now() - it).coerceAtLeast(0) }
            playingSince = null
        }
    }

    fun reset() { elapsed = 0; playingSince = null }

    companion object { const val MIN_PLAYBACK_MS = 30_000L }
}
