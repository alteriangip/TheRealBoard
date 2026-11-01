package com.refboard.app.data

/**
 * Everything that differs between the sites the app can browse. Each app
 * (RefBoard for PornPics, EroBoard for Erome) picks one at startup.
 */
interface Site {
    val id: String
    /** Shown in the side menu. */
    val appName: String
    val root: String

    /** Feeds on the Feed tab besides "For you": (kind, title). */
    val listings: List<Pair<String, String>>
    fun listingUrl(kind: String, orientation: Orientation): String
    fun isListingUrl(url: String): Boolean

    /** Gay / trans content setting in the menu. */
    val hasOrientation: Boolean
    /** A separate A-Z tag list besides the categories. */
    val hasTagIndex: Boolean
    /** Title of the categories grid. */
    val categoriesTitle: String
    /** Label for people favourites ("Pornstars", "Uploaders"). */
    val peopleLabel: String
    /** Channels exist as their own kind of favourite. */
    val hasChannels: Boolean
    val starterSearches: List<String>

    fun searchUrl(query: String): String
    fun tagUrl(tag: String): String
    /** Where a person's galleries are (null: search by name). */
    fun personUrl(name: String): String?
    fun isGallery(url: String): Boolean
    /** True for the site's image servers (rate limits, offline copies). */
    fun isImageHost(host: String): Boolean
    /** A smaller copy of an image for grids, if the site has one. */
    fun smallImage(url: String): String

    suspend fun startSearch(query: String): SearchStart
    /** One page of galleries; pass [ListingPage.next] back as [offset]. */
    suspend fun listingPage(url: String, offset: Int): ListingPage
    suspend fun gallery(url: String): GalleryData?
    suspend fun categories(orientation: Orientation): List<Category>
    suspend fun tagsIndex(orientation: Orientation): List<TagEntry>

    /** First gallery thumbnail on a category, tag or search page. */
    suspend fun sample(pageUrl: String): String? =
        listingPage(pageUrl, 0).items.firstOrNull { it.thumb != null }?.thumb
}

/** Where a search lands, and its first page. */
class SearchStart(val url: String, val redirected: Boolean, val page: ListingPage)

object PornPicsSite : Site {
    override val id = "pornpics"
    override val appName = "RefBoard"
    override val root = SiteParser.ROOT
    override val listings = listOf(
        "popular" to "Popular",
        "recent" to "Newest",
        "rating" to "Top rated",
        "likes" to "Most liked",
        "views" to "Most viewed",
    )
    private val listingPath = Regex("""^${Regex.escape(root)}/(?:(?:gay|shemale)/)?(popular|recent|rating|likes|views|comments)/?$""")

    override fun listingUrl(kind: String, orientation: Orientation) = orientation.url("$kind/")
    override fun isListingUrl(url: String) = listingPath.matches(url)
    override val hasOrientation = true
    override val hasTagIndex = true
    override val categoriesTitle = "Categories"
    override val peopleLabel = "Pornstars"
    override val hasChannels = true
    override val starterSearches = listOf(
        "standing", "sitting", "kneeling", "lying", "from behind", "side view",
        "close up", "legs", "feet", "hands", "stretching", "yoga", "looking back",
    )

    override fun searchUrl(query: String) = SiteParser.searchUrl(query)
    override fun tagUrl(tag: String) = SiteParser.tagUrl(tag)
    override fun personUrl(name: String): String? = null
    override fun isGallery(url: String) = SiteApi.isSiteGallery(url)
    override fun isImageHost(host: String) = host.startsWith("cdni.")
    override fun smallImage(url: String) = Thumbs.small(url)

    override suspend fun startSearch(query: String) = SiteApi.startSearch(query)
    override suspend fun listingPage(url: String, offset: Int) = SiteApi.listingPage(url, offset)
    override suspend fun gallery(url: String) = SiteApi.gallery(url)
    override suspend fun categories(orientation: Orientation) = SiteApi.categories(orientation)
    override suspend fun tagsIndex(orientation: Orientation) = SiteApi.tagsIndex(orientation)
}
