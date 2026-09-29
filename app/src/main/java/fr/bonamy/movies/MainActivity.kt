package fr.bonamy.movies

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import androidx.leanback.widget.SearchBar
import androidx.leanback.widget.SearchOrbView
import androidx.leanback.widget.SpeechOrbView
import androidx.core.content.res.ResourcesCompat
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import fr.bonamy.movies.core.Title
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.UniversalSearch
import fr.bonamy.movies.core.StreamingSite
import fr.bonamy.movies.core.CatalogRequest
import fr.bonamy.movies.core.CatalogBrowser
import fr.bonamy.movies.core.BrowseMenuItem
import fr.bonamy.movies.core.RequestSession
import fr.bonamy.movies.core.Season
import fr.bonamy.movies.core.PlaybackSource
import fr.bonamy.movies.core.ResolvedPlayback
import fr.bonamy.movies.core.SubtitleClient
import fr.bonamy.movies.core.SubtitleContext
import fr.bonamy.movies.core.SubtitleSearch
import fr.bonamy.movies.core.OnlineSubtitle
import fr.bonamy.movies.core.SubtitleSelection
import fr.bonamy.movies.core.SubtitleLanguage
import fr.bonamy.movies.core.subtitleLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URL
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

@OptIn(markerClass = [UnstableApi::class])
class MainActivity : ComponentActivity() {
    private val accent = Color.rgb(52, 152, 218)
    private val muted = Color.rgb(168, 189, 216)
    private val sites = AppServices.sites
    private val sitePreferences by lazy { SitePreferences(getSharedPreferences("playback", MODE_PRIVATE)) }
    private val activeSite get() = sites.initial(sitePreferences.activeSiteId)
    private var playbackSite: StreamingSite? = null
    private val playbackSession = RequestSession()
    private var detailJob: Job? = null
    private val detailSession = RequestSession()
    private val subtitleClient = SubtitleClient()
    private var subtitleJob: Job? = null
    private var subtitleContext: SubtitleContext? = null
    private var subtitleSearch: SubtitleSearch? = null
    private var onlineSubtitle: OnlineSubtitle? = null
    private var pendingSubtitle: OnlineSubtitle? = null
    private var selectedSubtitle: SubtitleSelection? = null
    private var subtitleToRestore: SubtitleSelection? = null
    private val images = PosterLoader()
    private lateinit var root: FrameLayout
    private lateinit var gallery: LinearLayout
    private lateinit var movieGrid: RecyclerView
    private lateinit var movieCards: MediaCardAdapter
    private lateinit var resumeSection: HomeSectionAdapter
    private lateinit var resumeGrid: RecyclerView
    private val catalogOffset get() = resumeSection.itemCount + 1
    private var catalogColumns = 7
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var searchBar: SearchBar
    private var searchQuery = ""
    private var searchJob: Job? = null
    private val universalSearch = UniversalSearch(sites.sites)
    private lateinit var searchResults: SearchResultsView
    private lateinit var searchStatus: TextView
    private var universalSearchJob: Job? = null
    private val searchPageJobs = mutableMapOf<String, Job>()
    private var searching = false
    private lateinit var remoteSearch: RemoteSearchController
    private lateinit var searchPanel: LinearLayout
    private lateinit var detailOverlay: FrameLayout
    private lateinit var playerLayer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var playerStatus: TextView
    private lateinit var playerTopBar: ViewGroup
    private lateinit var playerTitle: TextView
    private lateinit var sourceButton: Button
    private lateinit var subtitlesButton: Button
    private lateinit var qualityButton: Button
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var playbackReady = false
    private val playbackProgress by lazy { PlaybackProgress(getSharedPreferences("progress", MODE_PRIVATE)) }
    private var catalogJob: Job? = null
    private var playbackJob: Job? = null
    private var optionsDialog: Dialog? = null
    private var currentMovie: Title? = null
    private var currentTarget: PlayableRef? = null
    private var currentEpisodeName: String? = null
    private var currentArtwork = ""
    private var returnToHome = false
    private var catalogSection = sites.sites.first().descriptor.sections.first()
    private val catalogBrowser by lazy { CatalogBrowser(activeSite, CatalogRequest(catalogSection.id)) }
    private var catalogQuery: String? = null
    private var catalogGeneration = 0
    private val catalogItems get() = catalogBrowser.items
    private lateinit var episodeOverlay: FrameLayout
    private var episodeJob: Job? = null
    private var selectedEpisodeCard: View? = null
    private var returnToEpisodes = false
    private var browserDialog: Dialog? = null
    private var selectedMovieCard: View? = null
    private var currentSourceId: String? = null
    private var availableSources: List<PlaybackSource> = emptyList()
    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query ->
            searchBar.setSearchQuery(query)
            searchQuery = query
            searchJob?.cancel()
            search()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).also {
            it.background = getDrawable(R.drawable.browser_surface_background)
            setContentView(it)
        }
        catalogSection = sitePreferences.section(activeSite.descriptor)
        buildGallery()
        buildDetail()
        episodeOverlay = FrameLayout(this).apply {
            background = getDrawable(R.drawable.browser_surface_background)
            visibility = View.GONE
        }
        root.addView(episodeOverlay, FrameLayout.LayoutParams(-1, -1))
        buildPlayer()
        loadLatest()
    }

    private fun buildGallery() {
        gallery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gallery, FrameLayout.LayoutParams(-1, -1))
        val header = layoutInflater.inflate(R.layout.browser_header, gallery, false)
        gallery.addView(header)
        breadcrumb = header.findViewById(R.id.breadcrumb)
        breadcrumb.text = "Movies / Popular"
        header.findViewById<ImageButton>(R.id.menu).apply {
            contentDescription = "Browse sections and sites"
            setOnClickListener { showSectionMenu() }
        }
        header.findViewById<ImageButton>(R.id.search).apply {
            visibility = View.VISIBLE
            contentDescription = "Search movies"
            setOnClickListener { showSearch() }
        }
        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        gallery.addView(searchPanel, LinearLayout.LayoutParams(-1, -2))
        searchBar = (layoutInflater.inflate(R.layout.search_bar, searchPanel, false) as SearchBar).apply {
            title = "Movies"
            setSearchBarListener(object : SearchBar.SearchBarListener {
                override fun onSearchQueryChange(query: String) {
                    searchQuery = query
                    searchJob?.cancel()
                    catalogJob?.cancel()
                    ++catalogGeneration
                    catalogBrowser.invalidate()
                    cancelUniversalSearch()
                    detailJob?.cancel()
                    detailSession.invalidate()
                    searchJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(600)
                        search(submitted = false)
                    }
                }
                override fun onSearchQuerySubmit(query: String) {
                    searchQuery = query
                    searchJob?.cancel()
                    search()
                }
                override fun onKeyboardDismiss(query: String) { focusCatalogStart() }
            })
        }
        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        searchPanel.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        searchRow.addView(searchBar, LinearLayout.LayoutParams(0, -2, 1f))
        remoteSearch = RemoteSearchController(this, ::submitRemoteSearch)
        searchRow.addView(remoteSearch.shortcut, LinearLayout.LayoutParams(-2, -2).apply {
            rightMargin = dp(36)
        })
        searchBar.setSpeechRecognitionCallback {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search ${searchLabel.lowercase()}")
            runCatching { voiceSearch.launch(intent) }.onFailure {
                searchBar.findViewById<View>(androidx.leanback.R.id.lb_search_text_editor).requestFocus()
            }
        }
        searchBar.findViewById<View>(androidx.leanback.R.id.lb_search_bar_items)
            .setBackgroundResource(R.drawable.search_input_background)
        searchBar.findViewById<TextView>(androidx.leanback.R.id.lb_search_text_editor).apply {
            fontFeatureSettings = "kern"
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.theme)
        }
        searchBar.findViewById<SpeechOrbView>(androidx.leanback.R.id.lb_search_bar_speech_orb).apply {
            setNotListeningOrbColors(SearchOrbView.Colors(accent, accent, Color.WHITE))
            setListeningOrbColors(SearchOrbView.Colors(accent, accent, Color.WHITE))
            showNotListening()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), 0, dp(36), 0)
        }
        gallery.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val section = layoutInflater.inflate(R.layout.section_header, content, false)
        heading = section.findViewById(R.id.title)
        heading.text = "Popular Movies"
        val catalogHeader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catalogHeader.addView(section)
        status = text("Loading titles…", 14f, muted)
        catalogHeader.addView(status, LinearLayout.LayoutParams(-1, dp(28)))
        val resumePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resumePanel.addView(layoutInflater.inflate(R.layout.section_header, resumePanel, false).apply {
            findViewById<TextView>(R.id.title).text = "Continue watching"
        })
        resumeGrid = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
        }
        resumePanel.addView(resumeGrid, LinearLayout.LayoutParams(-1, -2))
        resumeSection = HomeSectionAdapter(resumePanel, false)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        catalogColumns = if (widthDp >= 800) 7 else max(4, (widthDp / 120).toInt())
        movieGrid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, catalogColumns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (position < catalogOffset) catalogColumns else 1
                }
            }
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            setPadding(0, 0, 0, dp(48))
        }
        movieCards = MediaCardAdapter(image = { url, view ->
            images.load(url, view) { bitmap -> if (bitmap != null) view.setImageBitmap(bitmap) }
        }, select = { index, card ->
            selectedMovieCard = card
            showDetail(catalogItems[index])
        }, focus = { index ->
            if (index >= catalogItems.size - catalogColumns * 2) loadCatalogPage(append = true)
        })
        movieGrid.adapter = ConcatAdapter(resumeSection, HomeSectionAdapter(catalogHeader), movieCards)
        content.addView(movieGrid, LinearLayout.LayoutParams(-1, 0, 1f))
        searchStatus = text("", 14f, muted).apply { visibility = View.GONE }
        content.addView(searchStatus, LinearLayout.LayoutParams(-1, -2))
        searchResults = SearchResultsView(this, image = { url, view ->
            images.load(url, view) { bitmap -> if (bitmap != null) view.setImageBitmap(bitmap) }
        }, select = { title, card ->
            selectedMovieCard = card
            showDetail(title)
        }, loadMore = ::loadSearchPage).apply { visibility = View.GONE }
        content.addView(searchResults, LinearLayout.LayoutParams(-1, 0, 1f))
        lifecycleScope.launch {
            universalSearch.rows.collect { rows -> searchResults.submit(rows) }
        }

    }

    private fun buildDetail() {
        detailOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(detailOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildPlayer() {
        playerLayer = layoutInflater.inflate(R.layout.activity_playback, root, false) as FrameLayout
        playerLayer.setBackgroundColor(Color.BLACK)
        playerLayer.visibility = View.GONE
        root.addView(playerLayer)
        playerView = playerLayer.findViewById(R.id.player_view)
        playerView.apply {
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 5_000
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowSubtitleButton(false)
            isFocusable = true
        }
        playerTopBar = playerLayer.findViewById(R.id.info_container_top)
        playerTitle = playerLayer.findViewById(R.id.title_text_view)
        playerLayer.findViewById<View>(R.id.indicators).visibility = View.GONE
        val controls = playerLayer.findViewById<LinearLayout>(R.id.controls_root)
        subtitlesButton = playerActionButton("SUBTITLES", controls) { showSubtitleOptions() }
        qualityButton = playerActionButton("QUALITY", controls) { showQualityOptions() }
        sourceButton = playerActionButton("SOURCE", controls) { showSourceOptions() }
        controls.addView(subtitlesButton)
        controls.addView(qualityButton)
        controls.addView(sourceButton)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            playerTopBar.visibility = visibility
        })
        playerStatus = text("Preparing movie…", 20f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundResource(R.drawable.playback_controls_bg)
        }
        playerLayer.addView(playerStatus, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun showSearch() {
        remoteSearch.start()
        searchPanel.visibility = View.VISIBLE
        refreshContinueWatching()
        if (!searching) search(submitted = false)
        searchBar.requestFocus()
    }

    private fun submitRemoteSearch(query: String) {
        detailJob?.cancel()
        detailSession.invalidate()
        browserDialog?.dismiss()
        if (playerLayer.visibility == View.VISIBLE) closePlayer()
        episodeJob?.cancel()
        episodeOverlay.visibility = View.GONE
        detailOverlay.visibility = View.GONE
        gallery.visibility = View.VISIBLE
        searchPanel.visibility = View.VISIBLE
        refreshContinueWatching()
        searchBar.setSearchQuery(query)
        searchQuery = query
        searchJob?.cancel()
        search()
        focusCatalogStart()
    }

    private fun showSectionMenu(site: StreamingSite = activeSite) {
        browserDialog?.dismiss()
        browserDialog = SiteMenuDialog.create(this, sites.menu(site), sitePreferences.section(site.descriptor),
            title = "Browse ${site.descriptor.name}") { item ->
            browserDialog?.dismiss()
            when (item) {
                is BrowseMenuItem.Section -> {
                    sitePreferences.activeSiteId = site.descriptor.id
                    sitePreferences.selectSection(site.descriptor, item.section)
                }
                is BrowseMenuItem.Site -> {
                    showSectionMenu(sites.get(item.descriptor.id))
                    return@create
                }
                BrowseMenuItem.Divider -> return@create
            }
            searchJob?.cancel()
            catalogJob?.cancel()
            episodeJob?.cancel()
            detailJob?.cancel()
            detailSession.invalidate()
            ++catalogGeneration
            episodeOverlay.visibility = View.GONE
            detailOverlay.visibility = View.GONE
            gallery.visibility = View.VISIBLE
            catalogSection = sitePreferences.section(activeSite.descriptor)
            searchQuery = ""
            searchBar.setSearchQuery("")
            searchBar.title = searchLabel
            loadLatest()
        }.also { it.show() }
    }

    private fun loadLatest() {
        searchJob?.cancel()
        cancelUniversalSearch()
        searching = false
        searchResults.visibility = View.GONE
        searchStatus.visibility = View.GONE
        movieGrid.visibility = View.VISIBLE
        searchPanel.visibility = View.GONE
        catalogQuery = null
        breadcrumb.text = "${activeSite.descriptor.name} / ${catalogSection.title}"
        heading.text = catalogSection.title
        searchBar.title = searchLabel
        root.findViewById<ImageButton>(R.id.search).contentDescription = "Search ${searchLabel.lowercase()}"
        refreshContinueWatching()
        loadCatalogPage()
    }

    private val searchLabel: String get() = "All sites"

    private fun cancelUniversalSearch() {
        searchResults.cancelPendingFocus()
        universalSearchJob?.cancel()
        searchPageJobs.values.forEach { it.cancel() }
        searchPageJobs.clear()
        universalSearch.invalidate()
    }

    private fun loadSearchPage(siteId: String) {
        if (!searching || searchJob?.isActive == true || searchPageJobs[siteId]?.isActive == true) return
        searchPageJobs[siteId] = lifecycleScope.launch { universalSearch.loadNext(siteId) }
    }

    private fun search(submitted: Boolean = true) {
        val query = searchQuery.trim()
        cancelUniversalSearch()
        catalogJob?.cancel()
        ++catalogGeneration
        detailJob?.cancel()
        detailSession.invalidate()
        searching = true
        movieGrid.visibility = View.GONE
        searchResults.visibility = View.VISIBLE
        searchStatus.text = if (query.isBlank()) "Search movies and TV shows across all sites" else ""
        searchStatus.visibility = if (query.isBlank()) View.VISIBLE else View.GONE
        breadcrumb.text = "All sites / Search"
        universalSearch.reset(query)
        universalSearchJob = lifecycleScope.launch {
            sites.sites.forEach { site -> launch { universalSearch.loadNext(site.descriptor.id) } }
        }
        // Editing updates results without submitting or changing keyboard focus.
        if (submitted) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(searchBar.windowToken, 0)
        }
    }

    private fun loadCatalogPage(append: Boolean = false) {
        if (searching) return
        if (append && !catalogBrowser.canLoadMore) return
        catalogJob?.cancel()
        val generation = ++catalogGeneration
        val site = activeSite
        val section = catalogSection
        val query = catalogQuery
        status.text = if (append) "Loading more…" else "Loading ${section.title.lowercase()}…"
        if (!append) status.visibility = View.VISIBLE
        if (!append) {
            catalogBrowser.reset(site, CatalogRequest(section.id, query))
            movieCards.clear()
        }
        catalogJob = lifecycleScope.launch {
            try {
                val added = catalogBrowser.loadNext() ?: return@launch
                if (generation != catalogGeneration || site !== activeSite) return@launch
                status.isFocusable = false
                status.setOnClickListener(null)
                status.text = "No ${section.title.lowercase()} found."
                status.visibility = if (catalogItems.isEmpty()) View.VISIBLE else View.GONE
                renderMovies(added, append)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != catalogGeneration || site !== activeSite) return@launch
                status.text = if (append) "Could not load more. Press Down to retry."
                    else "Could not load ${site.descriptor.name}. Select here to retry."
                status.visibility = View.VISIBLE
                status.isFocusable = !append
                status.setOnClickListener { loadCatalogPage(append) }
            }
        }
    }

    private fun renderMovies(movies: List<Title>, append: Boolean = false) {
        movieCards.append(movies.map { MediaCard(it.ref, it.title, it.poster) })
        if (!append && searchPanel.visibility != View.VISIBLE) {
            movieGrid.scrollToPosition(0)
            movieGrid.post { focusCatalogStart() }
        }
    }

    private fun focusCatalogStart() {
        if (searching) {
            searchResults.focusResults()
            return
        }
        if (resumeSection.visible && resumeGrid.getChildAt(0)?.requestFocus() == true) return
        movieGrid.findViewHolderForAdapterPosition(catalogOffset)?.itemView?.requestFocus()
    }

    private fun refreshContinueWatching(focusTarget: PlayableRef? = null) {
        val items = playbackProgress.list(activeSite.descriptor.id, catalogSection.id)
        resumeSection.visible = searchPanel.visibility != View.VISIBLE && items.isNotEmpty()
        val cards = MediaCardAdapter(landscape = catalogSection.mediaType == MediaType.TV, image = { url, view ->
            images.load(url, view) { bitmap -> if (bitmap != null) view.setImageBitmap(bitmap) }
        }, select = { index, _ ->
            val item = items[index]
            openPlayer(item.movie, item.target, item.episodeName, item.artwork)
        }, focus = {
            movieGrid.post { (movieGrid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(0, 0) }
        })
        cards.append(items.map { item ->
            val title = playbackLabel(item.movie, item.target, item.episodeName)
            MediaCard(item.target, title, item.artwork,
                (item.position.toDouble() / item.duration * 100).toInt())
        })
        resumeGrid.adapter = cards
        if (focusTarget != null) {
            val index = items.indexOfFirst { it.target == focusTarget }.coerceAtLeast(0)
            movieGrid.scrollToPosition(0)
            resumeGrid.scrollToPosition(index)
            movieGrid.post {
                if (resumeSection.visible) resumeGrid.post {
                    resumeGrid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                } else focusCatalogStart()
            }
        }
    }

    private fun showDetail(movie: Title) {
        detailJob?.cancel()
        val ticket = detailSession.next()
        val site = sites.get(movie.siteId)
        detailJob = lifecycleScope.launch {
            try {
                val detail = site.details(movie)
                require(detail.ref == movie.ref) { "Site returned a different title" }
                if (detailSession.isCurrent(ticket)) renderDetail(detail)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (detailSession.isCurrent(ticket)) {
                    val message = if (searching) searchStatus else status
                    message.text = "Could not load title details. Select the title to retry."
                    message.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderDetail(movie: Title) {
        gallery.visibility = View.GONE
        detailOverlay.removeAllViews()
        val backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        detailOverlay.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        images.load(movie.backdrop.ifBlank { movie.poster }, backdrop) { bitmap ->
            if (bitmap != null) backdrop.setImageBitmap(bitmap)
        }
        val details = layoutInflater.inflate(R.layout.item_movie_details, detailOverlay, false)
        detailOverlay.addView(details)
        details.findViewById<TextView>(R.id.movie_type).setText(
            if (movie.type == MediaType.TV) R.string.details_type_tv else R.string.details_type_movie,
        )
        details.findViewById<TextView>(R.id.movie_title).text = movie.title
        details.findViewById<TextView>(R.id.movie_year).apply {
            text = movie.releaseDate.take(4)
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        details.findViewById<View>(R.id.movie_meta).visibility =
            if (movie.releaseDate.isBlank() && movie.rating.isBlank()) View.GONE else View.VISIBLE
        details.findViewById<View>(R.id.movie_rating_separator).visibility =
            if (movie.releaseDate.isBlank() || movie.rating.isBlank()) View.GONE else View.VISIBLE
        details.findViewById<TextView>(R.id.movie_rating).text = movie.rating
        if (movie.rating.isBlank()) {
            details.findViewById<View>(R.id.movie_rating).visibility = View.GONE
            details.findViewById<View>(R.id.rating_star).visibility = View.GONE
        }
        details.findViewById<TextView>(R.id.movie_overview).apply {
            text = movie.overview
            visibility = if (movie.overview.isBlank()) View.GONE else View.VISIBLE
        }
        val watch = details.findViewById<View>(R.id.action_watch)
        watch.nextFocusRightId = watch.id
        details.findViewById<TextView>(R.id.action_watch_label).text =
            if (movie.type == MediaType.TV) "EPISODES" else "WATCH"
        watch.setOnClickListener {
            if (movie.type == MediaType.TV) showSeasons(movie) else openPlayer(movie, PlayableRef(movie.ref))
        }
        detailOverlay.visibility = View.VISIBLE
        watch.requestFocus()
    }

    private fun showSeasons(show: Title, known: List<Season>? = null, selected: String? = null) {
        fun present(seasons: List<Season>) {
            browserDialog?.dismiss()
            val builder = DialogUtils.getDialogBuilder(this, show.title)
            if (seasons.isEmpty()) builder.setMessage("No seasons available.")
            else builder.setSingleChoiceItems(seasons.map { "${it.name} · ${it.episodeCount} episodes" }.toTypedArray(),
                seasons.indexOfFirst { it.ref.id == selected }.takeIf { it >= 0 }
                    ?: seasons.indexOfFirst { (it.number ?: 1) > 0 }.coerceAtLeast(0)) { dialog, index ->
                dialog.dismiss()
                showEpisodes(show, seasons, seasons[index])
            }
            browserDialog = builder.create().also { it.show() }
        }
        if (known != null) { present(known); return }
        episodeJob?.cancel()
        val loading = DialogUtils.getDialogBuilder(this, show.title).setMessage("Loading seasons…").create()
        browserDialog = loading
        loading.show()
        episodeJob = lifecycleScope.launch {
            try {
                val seasons = requireNotNull(sites.get(show.siteId).series).seasons(show.ref)
                currentCoroutineContext().ensureActive()
                if (browserDialog === loading && loading.isShowing) present(seasons)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (browserDialog === loading && loading.isShowing) {
                    loading.dismiss()
                    browserDialog = DialogUtils.getDialogBuilder(this@MainActivity, "Seasons unavailable")
                        .setMessage("Could not load this show's seasons.")
                        .setPositiveButton("Retry") { dialog, _ -> dialog.dismiss(); showSeasons(show) }
                        .create().also { it.show() }
                }
            }
        }
    }

    private fun showEpisodes(show: Title, seasons: List<Season>, season: Season) {
        episodeJob?.cancel()
        episodeOverlay.removeAllViews()
        episodeOverlay.visibility = View.VISIBLE
        detailOverlay.visibility = View.GONE
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        episodeOverlay.addView(panel, FrameLayout.LayoutParams(-1, -1))
        val header = layoutInflater.inflate(R.layout.browser_header, panel, false)
        header.findViewById<TextView>(R.id.breadcrumb).text = "TV Shows / ${show.title}"
        header.findViewById<ImageButton>(R.id.menu).apply {
            contentDescription = "Browse sections and sites"
            setOnClickListener { showSectionMenu() }
        }
        panel.addView(header)
        val seasonRow = LinearLayout(this).apply { setPadding(dp(36), dp(8), dp(36), dp(8)) }
        val seasonButton = playerActionButton(season.name.uppercase(), seasonRow) {
            showSeasons(show, seasons, season.ref.id)
        }
        seasonRow.addView(seasonButton)
        panel.addView(seasonRow)
        val message = text("Loading episodes…", 14f, muted).apply { setPadding(dp(36), 0, dp(36), 0) }
        panel.addView(message)
        val grid = RecyclerView(this).apply {
            val columns = max(2, ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 58) / 214).toInt())
            layoutManager = GridLayoutManager(this@MainActivity, columns)
            clipChildren = false
            clipToPadding = false
            itemAnimator = null
            setPadding(dp(29), 0, dp(29), dp(36))
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        seasonButton.requestFocus()
        episodeJob = lifecycleScope.launch {
            try {
                val episodes = requireNotNull(sites.get(show.siteId).series).episodes(season.ref)
                currentCoroutineContext().ensureActive()
                message.text = if (episodes.isEmpty()) "No episodes available." else ""
                message.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
                val cards = MediaCardAdapter(landscape = true, image = { url, view ->
                    images.load(url, view) { bitmap -> if (bitmap != null) view.setImageBitmap(bitmap) }
                }, select = { index, card ->
                    val episode = episodes[index]
                    selectedEpisodeCard = card
                    openPlayer(show, episode.target,
                        episode.name, episode.still.ifBlank { show.backdrop.ifBlank { show.poster } })
                })
                grid.adapter = cards
                cards.append(episodes.map { episode -> MediaCard(episode.target, episode.number?.let { "$it. ${episode.name}" } ?: episode.name,
                    episode.still.ifBlank { show.backdrop.ifBlank { show.poster } }) })
                grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.text = "Could not load episodes. Select the season to retry."
            }
        }
    }

    private fun openPlayer(movie: Title, target: PlayableRef, episodeName: String? = null,
        artwork: String = movie.poster, continuingSeries: Boolean = false) {
        val preferredSource = if (continuingSeries) currentSourceId else sitePreferences.source(target.siteId)
        val subtitle = if (continuingSeries) currentSubtitleSelection()?.nextEpisode()
            else playbackProgress.subtitle(target)
        playbackJob?.cancel()
        optionsDialog?.dismiss()
        releasePlayer()
        currentMovie = movie
        currentTarget = target
        currentEpisodeName = episodeName
        currentArtwork = artwork
        val resumePosition = if (continuingSeries) 0 else playbackProgress.position(target)
        if (!continuingSeries) {
            returnToHome = gallery.visibility == View.VISIBLE
            returnToEpisodes = episodeOverlay.visibility == View.VISIBLE
        }
        gallery.visibility = View.GONE
        episodeOverlay.visibility = View.GONE
        detailOverlay.visibility = View.GONE
        val site = sites.get(target.siteId)
        playbackSite = site
        val ticket = playbackSession.next()
        currentSourceId = null
        availableSources = emptyList()
        sourceButton.isEnabled = false
        playerLayer.visibility = View.VISIBLE
        playerTitle.text = playbackLabel(movie, target, episodeName)
        playerStatus.text = "Preparing ${movie.title}…"
        playerStatus.visibility = View.VISIBLE
        playerView.requestFocus()
        playbackJob = lifecycleScope.launch {
            try {
                val options = site.sources(target)
                if (!playbackSession.isCurrent(ticket)) return@launch
                availableSources = options.sources
                currentSourceId = options.preferred(preferredSource).id
                sourceButton.isEnabled = true
                val stream = site.resolve(target, currentSourceId)
                if (playbackSession.isCurrent(ticket)) startPlayback(stream, resumePosition, subtitle = subtitle)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!playbackSession.isCurrent(ticket)) return@launch
                playerStatus.text = "Could not play this title\n${error.message ?: "Unknown playback error"}"
                playerStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun startPlayback(stream: ResolvedPlayback, startPosition: Long = 0, autoPlay: Boolean = true,
        subtitle: SubtitleSelection? = currentSubtitleSelection()) {
        releasePlayer()
        selectedSubtitle = subtitle
        subtitleToRestore = subtitle
        subtitleContext = stream.subtitleContext
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(stream.requestHeaders)
            .setAllowCrossProtocolRedirects(true)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, dataSource)))
            .build()
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setForceHighestSupportedBitrate(true)
            .apply { if (subtitle != null) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
            .build()
        var endHandled = false
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== exoPlayer) return
                if (state == Player.STATE_READY) {
                    endHandled = false
                    playbackReady = true
                    playerStatus.visibility = View.GONE
                    subtitleToRestore?.let { choice ->
                        subtitleToRestore = null
                        restoreSubtitle(exoPlayer, choice)
                    }
                }
                if (state == Player.STATE_ENDED && !endHandled) {
                    endHandled = true
                    onPlaybackEnded(exoPlayer)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === exoPlayer && !isPlaying) savePlaybackProgress()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) {
                if (player === exoPlayer && reason == Player.DISCONTINUITY_REASON_SEEK) savePlaybackProgress()
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (player !== exoPlayer) return
                val subtitle = pendingSubtitle ?: return
                val group = tracks.groups.firstOrNull { group ->
                    group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any {
                        group.getTrackFormat(it).label == subtitle.release
                    }
                } ?: return
                val index = (0 until group.length).first { group.getTrackFormat(it).label == subtitle.release }
                pendingSubtitle = null
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== exoPlayer) return
                playerStatus.text = "Playback stopped\n${error.errorCodeName}"
                playerStatus.visibility = View.VISIBLE
            }
        })
        player = exoPlayer
        playerView.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.Builder().setUri(stream.streamUrl)
            .setSubtitleConfigurations(stream.subtitles.map { track ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                    .setId(track.id).setLanguage(track.language).setLabel(track.label)
                    .setMimeType(MimeTypes.TEXT_VTT).build()
            })
            .setMimeType(stream.format.mimeType).build())
        exoPlayer.prepare()
        if (startPosition > 0) exoPlayer.seekTo(startPosition)
        exoPlayer.playWhenReady = autoPlay && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(5_000)
                savePlaybackProgress()
            }
        }
        playerView.showController()
    }

    private fun onPlaybackEnded(endedPlayer: ExoPlayer) {
        if (player !== endedPlayer || endedPlayer.playbackState != Player.STATE_ENDED) return
        savePlaybackProgress()
        val target = currentTarget ?: return
        val title = currentMovie ?: return
        if (target.type == MediaType.MOVIE) {
            closePlayer()
            return
        }
        val series = playbackSite?.series ?: return
        optionsDialog?.dismiss()
        subtitleJob?.cancel()
        playbackJob?.cancel()
        val ticket = playbackSession.next()
        playerStatus.text = "Loading next episode…"
        playerStatus.visibility = View.VISIBLE
        playbackJob = lifecycleScope.launch {
            try {
                val next = series.nextEpisode(target)
                currentCoroutineContext().ensureActive()
                if (!playbackSession.isCurrent(ticket) || player !== endedPlayer ||
                    endedPlayer.playbackState != Player.STATE_ENDED) return@launch
                if (next == null) closePlayer()
                else {
                    require(next.target.title == target.title && next.target.key != target.key)
                    openPlayer(title, next.target, next.name,
                        next.still.ifBlank { title.backdrop.ifBlank { title.poster } }, continuingSeries = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!playbackSession.isCurrent(ticket) || player !== endedPlayer ||
                    endedPlayer.playbackState != Player.STATE_ENDED) return@launch
                playerStatus.visibility = View.GONE
                showOptionsDialog(DialogUtils.getDialogBuilder(this@MainActivity, "Next episode unavailable")
                    .setMessage("Could not load the next episode.")
                    .setPositiveButton("Retry") { dialog, _ ->
                        dialog.dismiss()
                        onPlaybackEnded(endedPlayer)
                    }.create())
            }
        }
    }

    private fun showSourceOptions() {
        if (currentMovie == null) return
        showSourceDialog(availableSources)
    }

    private fun showSourceDialog(sources: List<PlaybackSource>) {
        optionsDialog?.dismiss()
        val dialog = DialogUtils.getDialogBuilder(this, "Sources")
            .setSingleChoiceItems(sources.map { it.label }.toTypedArray(),
                sources.indexOfFirst { it.id == currentSourceId }) { selectedDialog, index ->
                selectedDialog.dismiss()
                if (sources[index].id != currentSourceId) switchSource(sources[index])
            }.create()
        showOptionsDialog(dialog)
    }

    private fun switchSource(source: PlaybackSource) {
        val target = currentTarget ?: return
        val site = playbackSite ?: return
        val position = player?.currentPosition ?: playbackProgress.position(target)
        val wasPlaying = player?.playWhenReady != false
        playbackJob?.cancel()
        val ticket = playbackSession.next()
        playerStatus.text = "Switching to ${source.label}…"
        playerStatus.visibility = View.VISIBLE
        playbackJob = lifecycleScope.launch {
            try {
                val stream = site.resolve(target, source.id)
                if (!playbackSession.isCurrent(ticket)) return@launch
                currentSourceId = source.id
                sitePreferences.selectSource(site.descriptor.id, source.id)
                startPlayback(stream, position, wasPlaying)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (playbackSession.isCurrent(ticket)) playerStatus.text = "Could not use ${source.label}\n${error.message ?: "Unknown playback error"}"
            }
        }
    }

    private fun showSubtitleOptions() {
        val currentPlayer = player ?: return
        val target = currentTarget ?: return
        subtitleJob?.cancel()
        subtitleToRestore = null
        val result = subtitleSearch
        val dialog = subtitleDialog(currentPlayer, result,
            if (result == null) "Searching French and English subtitles…" else null)
        if (result != null) return
        subtitleJob = lifecycleScope.launch {
            try {
                val context = subtitleContext ?: playbackSite?.subtitleContext(target)
                val found = if (context == null) SubtitleSearch(emptyList(), emptyList()) else
                    runInterruptible(Dispatchers.IO) { subtitleClient.search(context) }
                if (player !== currentPlayer) return@launch
                subtitleSearch = found
                if (optionsDialog === dialog && dialog.isShowing) subtitleDialog(currentPlayer, found)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (player === currentPlayer && optionsDialog === dialog && dialog.isShowing) {
                    subtitleDialog(currentPlayer, null, "Could not search subtitles. Close and reopen to retry.")
                }
            }
        }
    }

    private fun embeddedSubtitle(group: Tracks.Group, index: Int): SubtitleSelection.Embedded {
        val format = group.getTrackFormat(index)
        return SubtitleSelection.Embedded(format.language, format.id, format.label, format.roleFlags)
    }

    private fun currentSubtitleSelection(): SubtitleSelection? {
        selectedSubtitle?.let { return it }
        val currentPlayer = player ?: return null
        if (!playbackReady) return null
        if (C.TRACK_TYPE_TEXT in currentPlayer.trackSelectionParameters.disabledTrackTypes) return SubtitleSelection.Off
        currentPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) return embeddedSubtitle(group, index)
            }
        }
        return SubtitleSelection.Off
    }

    /** Runs once after tracks are known. User selection, source changes and exit cancel this job. */
    private fun restoreSubtitle(currentPlayer: ExoPlayer, selection: SubtitleSelection) {
        if (selection == SubtitleSelection.Off) return
        val language = when (selection) {
            is SubtitleSelection.Language -> subtitleLanguageCode(selection.code)
            is SubtitleSelection.Embedded -> subtitleLanguageCode(selection.language)
            is SubtitleSelection.Online -> selection.subtitle.language.code
            SubtitleSelection.Off -> return
        }
        if (selection !is SubtitleSelection.Online) {
            val tracks = currentPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
            val exact = if (selection is SubtitleSelection.Embedded) tracks.firstOrNull { (group, index) ->
                embeddedSubtitle(group, index) == selection
            } else null
            val match = exact ?: tracks.firstOrNull { (group, index) ->
                language != null && subtitleLanguageCode(group.getTrackFormat(index).language) == language
            }
            if (match != null) {
                val (group, index) = match
                selectedSubtitle = embeddedSubtitle(group, index)
                currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                savePlaybackProgress()
                return
            }
        }
        val onlineLanguage = SubtitleLanguage.entries.firstOrNull { it.code == language } ?: return
        val target = currentTarget ?: return
        val site = playbackSite ?: return
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            try {
                // Exact resume uses the same file. Search can refresh an expired download link.
                val saved = (selection as? SubtitleSelection.Online)?.subtitle
                if (saved != null) {
                    try {
                        val file = subtitleFile(saved)
                        currentCoroutineContext().ensureActive()
                        if (player === currentPlayer) attachSubtitle(saved, file, currentPlayer)
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { /* Refresh the exact subtitle's URL below. */ }
                }
                val context = subtitleContext ?: site.subtitleContext(target) ?: return@launch
                val found = runInterruptible(Dispatchers.IO) { subtitleClient.search(context, listOf(onlineLanguage)) }
                // Searches are episode-scoped; never carry a subtitle file across episodes.
                val candidates = if (saved != null) found.subtitles.filter { it.id == saved.id }
                    else found.subtitles.take(3)
                for (candidate in candidates) {
                    try {
                        val file = subtitleFile(candidate)
                        currentCoroutineContext().ensureActive()
                        if (player === currentPlayer) attachSubtitle(candidate, file, currentPlayer)
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { /* Try the next release in the same language. */ }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Subtitle availability must not interrupt playback or open a dialog.
                // Keep the intended choice in the bookmark so another resume can retry.
            }
        }
    }

    private suspend fun subtitleFile(subtitle: OnlineSubtitle): File = runInterruptible(Dispatchers.IO) {
        require(subtitle.id.matches(Regex("[0-9]+")))
        val cached = listOf("vtt", "srt").map { File(cacheDir, "subtitle-${subtitle.id}.$it") }
            .firstOrNull { it.isFile && it.length() > 0 }
        cached ?: run {
            val document = subtitleClient.download(subtitle)
            val file = File(cacheDir, "subtitle-${subtitle.id}.${if (document.isWebVtt) "vtt" else "srt"}")
            val temporary = File.createTempFile("subtitle-", ".tmp", cacheDir)
            try {
                temporary.writeText(document.text, Charsets.UTF_8)
                check(temporary.renameTo(file)) { "Could not cache subtitles" }
                file
            } finally { temporary.delete() }
        }
    }

    private fun subtitleDialog(currentPlayer: ExoPlayer, result: SubtitleSearch?, message: String? = null): Dialog {
        val textTracks = buildList {
            currentPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
                for (index in 0 until group.length) {
                    if (group.isTrackSupported(index) && group.getTrackFormat(index).label != onlineSubtitle?.release) {
                        add(group to index)
                    }
                }
            }
        }
        val online = (listOfNotNull(onlineSubtitle) + result?.subtitles.orEmpty()).distinctBy { it.id }
        val labels = listOf("Off") + textTracks.map { (group, index) ->
            val format = group.getTrackFormat(index)
            format.label ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage } ?: "Subtitle"
        } + online.map { "${it.language.label} · ${it.release}" }
        val disabled = C.TRACK_TYPE_TEXT in currentPlayer.trackSelectionParameters.disabledTrackTypes
        val selected = when {
            disabled -> 0
            onlineSubtitle != null && currentPlayer.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any {
                    group.isTrackSelected(it) && group.getTrackFormat(it).label == onlineSubtitle?.release
                }
            } -> online.indexOfFirst { it.id == onlineSubtitle?.id }
                .takeIf { it >= 0 }?.let { textTracks.size + it + 1 } ?: -1
            else -> textTracks.indexOfFirst { (group, index) -> group.isTrackSelected(index) } + 1
        }
        val failures = result?.failedLanguages.orEmpty()
        val builder = DialogUtils.getDialogBuilder(this, "Subtitles")
        val status = message ?: when {
            failures.isNotEmpty() -> "${failures.joinToString { it.label }} search unavailable. Close and reopen to retry."
            result != null && online.isEmpty() && textTracks.isEmpty() -> "No French or English subtitles found online."
            else -> null
        }
        if (status != null) builder.setMessage(status)
        val dialog = builder.setSingleChoiceItems(labels.toTypedArray(), selected) { selectedDialog, index ->
            if (index > textTracks.size) {
                downloadSubtitle(online[index - textTracks.size - 1], currentPlayer)
            } else {
                subtitleJob?.cancel()
                pendingSubtitle = null
                val parameters = currentPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, index == 0)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                selectedSubtitle = if (index == 0) SubtitleSelection.Off else {
                    val (group, trackIndex) = textTracks[index - 1]
                    parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
                    embeddedSubtitle(group, trackIndex)
                }
                currentPlayer.trackSelectionParameters = parameters.build()
                savePlaybackProgress()
                selectedDialog.dismiss()
            }
        }.create()
        optionsDialog?.dismiss()
        showOptionsDialog(dialog)
        // Keep partial results visible, but retry failed searches on the next opening.
        if (failures.isNotEmpty()) subtitleSearch = null
        return dialog
    }

    private fun downloadSubtitle(subtitle: OnlineSubtitle, currentPlayer: ExoPlayer) {
        subtitleJob?.cancel()
        optionsDialog?.dismiss()
        val dialog = DialogUtils.getDialogBuilder(this, "Subtitles")
            .setMessage("Downloading ${subtitle.language.label} subtitles…").create()
        showOptionsDialog(dialog)
        subtitleJob = lifecycleScope.launch {
            try {
                val file = subtitleFile(subtitle)
                if (player !== currentPlayer || optionsDialog !== dialog || !dialog.isShowing) return@launch
                attachSubtitle(subtitle, file, currentPlayer)
                dialog.dismiss()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (player === currentPlayer && optionsDialog === dialog && dialog.isShowing) {
                    subtitleDialog(currentPlayer, subtitleSearch, "Could not download this subtitle. Choose another release or retry.")
                }
            }
        }
    }

    private fun attachSubtitle(subtitle: OnlineSubtitle, file: File, currentPlayer: ExoPlayer) {
        val config = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setId("online:${subtitle.id}").setLanguage(subtitle.language.code)
            .setLabel(subtitle.release)
            .setMimeType(if (file.extension == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val currentItem = currentPlayer.currentMediaItem ?: return
        val hosted = currentItem.localConfiguration?.subtitleConfigurations.orEmpty()
            .filterNot { it.id?.startsWith("online:") == true }
        val item = currentItem.buildUpon().setSubtitleConfigurations(hosted + config).build()
        selectedSubtitle = SubtitleSelection.Online(subtitle)
        savePlaybackProgress()
        onlineSubtitle = subtitle
        pendingSubtitle = subtitle
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(subtitle.language.code).build()
        val position = currentPlayer.currentPosition
        val playing = currentPlayer.playWhenReady
        currentPlayer.setMediaItem(item, position)
        currentPlayer.prepare()
        currentPlayer.playWhenReady = playing
    }

    private fun showQualityOptions() {
        val currentPlayer = player ?: return
        val qualities = currentPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
            }.sortedWith(compareBy({ (group, index) -> group.getTrackFormat(index).height },
                { (group, index) -> group.getTrackFormat(index).bitrate }))
        if (qualities.isEmpty()) return
        val parameters = currentPlayer.trackSelectionParameters
        val selected = if (parameters.forceHighestSupportedBitrate) qualities.lastIndex + 1
            else qualities.indexOfFirst { (group, index) ->
                parameters.overrides[group.mediaTrackGroup]?.trackIndices?.contains(index) == true
            } + 1
        val labels = listOf("Auto") + qualities.map { (group, index) ->
            val format = group.getTrackFormat(index)
            // Title encodes often crop letterboxing (e.g. 1920×1000 is the 1080p rendition).
            val resolution = max(format.height, format.width * 9 / 16)
            if (resolution > 0) "${resolution}p" else "${format.bitrate / 1000} kbps"
        }
        optionsDialog?.dismiss()
        val dialog = DialogUtils.getDialogBuilder(this, "Quality")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { selectedDialog, index ->
                val builder = currentPlayer.trackSelectionParameters.buildUpon()
                    .setForceHighestSupportedBitrate(false)
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                if (index > 0) {
                    val (group, trackIndex) = qualities[index - 1]
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
                }
                currentPlayer.trackSelectionParameters = builder.build()
                selectedDialog.dismiss()
            }.create()
        showOptionsDialog(dialog)
    }

    private fun showOptionsDialog(dialog: Dialog) {
        optionsDialog = dialog
        playerView.controllerShowTimeoutMs = 0
        dialog.setOnDismissListener {
            if (optionsDialog === dialog) {
                optionsDialog = null
                playerView.controllerShowTimeoutMs = 5_000
                playerView.showController()
            }
        }
        dialog.show()
    }

    private fun closePlayer() {
        optionsDialog?.dismiss()
        playbackJob?.cancel()
        releasePlayer()
        val target = currentTarget
        currentMovie = null
        currentTarget = null
        currentSourceId = null
        availableSources = emptyList()
        playbackSite = null
        playbackSession.invalidate()
        playerLayer.visibility = View.GONE
        if (returnToHome) {
            gallery.visibility = View.VISIBLE
            refreshContinueWatching(target)
        } else if (returnToEpisodes) {
            episodeOverlay.visibility = View.VISIBLE
            selectedEpisodeCard?.requestFocus()
        } else {
            detailOverlay.visibility = View.VISIBLE
            detailOverlay.findViewById<View>(R.id.action_watch)?.requestFocus()
        }
    }

    private fun releasePlayer() {
        savePlaybackProgress()
        progressJob?.cancel()
        playbackReady = false
        subtitleJob?.cancel()
        subtitleSearch = null
        subtitleContext = null
        onlineSubtitle = null
        pendingSubtitle = null
        selectedSubtitle = null
        subtitleToRestore = null
        playerView.player = null
        player?.release()
        player = null
    }

    private fun savePlaybackProgress() {
        val currentPlayer = player ?: return
        val target = currentTarget ?: return
        val movie = currentMovie ?: return
        if (!playbackReady) return
        playbackProgress.save(movie, target, currentEpisodeName, currentArtwork,
            currentPlayer.currentPosition, currentPlayer.duration,
            currentPlayer.playbackState == Player.STATE_ENDED, currentSubtitleSelection())
    }

    @Deprecated("Android TV uses the physical Back button")
    override fun onBackPressed() {
        detailJob?.cancel()
        detailSession.invalidate()
        when {
            optionsDialog?.isShowing == true -> optionsDialog?.dismiss()
            playerLayer.visibility == View.VISIBLE && playerTopBar.visibility == View.VISIBLE -> {
                playerView.hideController()
                playerTopBar.visibility = View.GONE
                playerView.requestFocus()
            }
            playerLayer.visibility == View.VISIBLE -> closePlayer()
            episodeOverlay.visibility == View.VISIBLE -> {
                episodeJob?.cancel()
                episodeOverlay.visibility = View.GONE
                detailOverlay.visibility = View.VISIBLE
                detailOverlay.findViewById<View>(R.id.action_watch)?.requestFocus()
            }
            detailOverlay.visibility == View.VISIBLE -> {
                detailOverlay.visibility = View.GONE
                gallery.visibility = View.VISIBLE
                refreshContinueWatching()
                if (selectedMovieCard?.requestFocus() != true) focusCatalogStart()
            }
            searchPanel.visibility == View.VISIBLE -> loadLatest()
            else -> super.onBackPressed()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (playerLayer.visibility == View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_CAPTIONS || keyCode == KeyEvent.KEYCODE_MENU)) {
            showSubtitleOptions()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // Activity's public key-dispatch hook inherits an AndroidX internal annotation.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            gallery.visibility == View.VISIBLE && searching && searchResults.hasFocus() &&
            searchResults.selectedPosition == 0) {
            searchResults.cancelPendingFocus()
            searchBar.requestFocus()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && gallery.visibility == View.VISIBLE && movieGrid.hasFocus()) {
            val position = movieGrid.focusedChild?.let { movieGrid.getChildAdapterPosition(it) - catalogOffset }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (resumeGrid.hasFocus() || (position in 0 until catalogColumns && !resumeSection.visible)) {
                    gallery.findViewById<View>(R.id.menu).requestFocus()
                    return true
                }
                if (position in 0 until catalogColumns && resumeSection.visible) {
                    (movieGrid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(0, 0)
                    movieGrid.post { resumeGrid.getChildAt(0)?.requestFocus() }
                    return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && resumeGrid.hasFocus()) {
                movieGrid.scrollToPosition(catalogOffset)
                movieGrid.post { movieGrid.findViewHolderForAdapterPosition(catalogOffset)?.itemView?.requestFocus() }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            playerLayer.visibility != View.VISIBLE && detailOverlay.visibility != View.VISIBLE &&
            episodeOverlay.visibility != View.VISIBLE && movieGrid.hasFocus()) {
            val focused = movieGrid.focusedChild?.let { movieGrid.getChildAdapterPosition(it) - catalogOffset }
            if (focused != null && focused >= catalogItems.size - catalogColumns * 2) {
                loadCatalogPage(append = true)
            }
        }
        if (playerLayer.visibility == View.VISIBLE && optionsDialog?.isShowing != true &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (playerTopBar.visibility == View.VISIBLE && event.keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)) {
                playerView.showController()
            }
            val buttons = listOf(subtitlesButton, qualityButton, sourceButton)
            val focused = buttons.indexOfFirst { it.hasFocus() }
            if (focused >= 0 && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val next = focused + if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                buttons[next.coerceIn(buttons.indices)].requestFocus()
                return true
            }
            if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) &&
                focused >= 0) {
                buttons[focused].performClick()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                playerView.showController()
                playerTopBar.visibility = View.VISIBLE
                subtitlesButton.requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                focused >= 0) {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (playerLayer.visibility == View.VISIBLE && event.repeatCount == 0 &&
            !sourceButton.hasFocus() && !subtitlesButton.hasFocus() && !qualityButton.hasFocus() &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            playerView.showController()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        remoteSearch.start()
    }

    override fun onStop() {
        remoteSearch.stop()
        savePlaybackProgress()
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        detailJob?.cancel()
        playbackSession.invalidate()
        catalogJob?.cancel()
        episodeJob?.cancel()
        browserDialog?.dismiss()
        playbackJob?.cancel()
        releasePlayer()
        searchJob?.cancel()
        images.close()
        super.onDestroy()
    }

    private fun playerActionButton(label: String, parent: ViewGroup, click: () -> Unit): Button =
        (layoutInflater.inflate(R.layout.playback_button, parent, false) as Button).apply {
            text = label
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun playbackLabel(title: Title, target: PlayableRef, episodeName: String?): String =
        listOfNotNull(title.title,
            listOfNotNull(target.season?.let { "S$it" }, target.episode?.let { "E$it" })
                .joinToString(" ").takeIf { it.isNotBlank() }, episodeName).joinToString(" · ")

    private fun text(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class PosterLoader {
    private val pool = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(24) {}

    fun load(url: String, view: ImageView, done: (Bitmap?) -> Unit) {
        view.tag = url
        cache.get(url)?.let { done(it); return }
        pool.execute {
            val bitmap = runCatching {
                val connection = URL(url).openConnection().apply { connectTimeout = 7_000; readTimeout = 7_000 }
                connection.getInputStream().use(BitmapFactory::decodeStream)
            }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap)
            view.post { if (view.tag == url) done(bitmap) }
        }
    }

    fun close() = pool.shutdownNow()
}
