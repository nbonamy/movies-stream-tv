package fr.bonamy.movies

import android.view.View
import android.widget.Button
import fr.bonamy.movies.core.Episode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shares one episode lookup between the end-of-video prompt and automatic continuation. */
internal class NextEpisodePrompt(
    private val button: Button,
    private val playbackFocus: View,
    private val scope: CoroutineScope,
    private val advance: (Episode) -> Unit,
) {
    private var load: (suspend () -> Episode?)? = null
    private var lookup: Deferred<Result<Episode?>>? = null
    private var candidate: Episode? = null
    var resolved: Result<Episode?>? = null
        private set
    private var dismissed = false

    init {
        button.visibility = View.GONE
        button.setOnClickListener { candidate?.let { episode -> dismiss(); advance(episode) } }
    }

    fun start(load: suspend () -> Episode?) {
        stop()
        this.load = load
        lookup = beginLookup(load)
    }

    private fun beginLookup(load: suspend () -> Episode?) = scope.async {
        try {
            val next = load()
            currentCoroutineContext().ensureActive()
            candidate = next
            Result.success(next).also { resolved = it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A speculative failure must not interrupt the current episode.
            Result.failure(error)
        }
    }

    suspend fun nextEpisode(): Episode? {
        val request = lookup ?: beginLookup(requireNotNull(load)).also { lookup = it }
        val result = request.await()
        if (result.isFailure && lookup === request) lookup = null // Allow the existing Retry action.
        return result.getOrThrow()
    }

    fun update(position: Long, duration: Long, canShow: Boolean) {
        val nearEnd = duration > 0 && position >= 0 && duration - position in 1..30_000L
        if (duration > 0 && !nearEnd) dismissed = false
        if (nearEnd && canShow && !dismissed && candidate != null) {
            if (button.visibility != View.VISIBLE) {
                button.visibility = View.VISIBLE
                button.requestFocus()
            }
        } else hide()
    }

    fun dismiss(): Boolean {
        if (button.visibility != View.VISIBLE) return false
        dismissed = true
        hide()
        return true
    }

    private fun hide() {
        val hadFocus = button.hasFocus()
        button.visibility = View.GONE
        if (hadFocus) playbackFocus.requestFocus()
    }

    fun stop() {
        lookup?.cancel()
        lookup = null
        load = null
        candidate = null
        resolved = null
        dismissed = false
        hide()
    }
}
