package com.windstrom5.rebit.data.wikipedia

/**
 * What we know about a song's artist besides the name (genre, country, script), used to choose between
 * Wikipedia articles that share it. "Penthouse" the Japanese pop act is not "Penthouse" the 1980s band.
 */
data class WikiHints(
    /** Words that, if an article's description or intro contains them, make it more likely to be right. */
    val positive: List<String> = emptyList(),
    /** Nationalities that make an article less likely to be right (only when we are fairly sure). */
    val negative: List<String> = emptyList(),
    /** Extra words to add to the search query, most useful first. */
    val queryTerms: List<String> = positive.take(2),
    /** Wikipedia editions worth trying after the usual ones (e.g. "ja" for a Japanese act). */
    val languages: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = positive.isEmpty() && negative.isEmpty()
    val key: String get() = positive.joinToString(",") + "|" + negative.joinToString(",") + "|" + languages.joinToString(",")
}

/** One article returned by a Wikipedia search, before we decide whether it is the right one. */
data class WikiCandidate(
    val title: String,
    val description: String?,
    val extract: String?,
    val thumbnailUrl: String?,
    val pageUrl: String,
    /** Position in the search ranking (1 = best). */
    val rank: Int
)

/** The article we settled on, ready to display. */
data class WikiSummary(
    val title: String,
    val description: String?,
    val extract: String,
    val thumbnailUrl: String?,
    val pageUrl: String,
    val language: String
)

sealed interface WikiLookup {
    data class Found(val summary: WikiSummary) : WikiLookup
    data object NotFound : WikiLookup
    data class Failed(val message: String) : WikiLookup
}

/** Load state for one card (artist or song) in the UI. */
sealed interface WikiLoad {
    data object Idle : WikiLoad
    data object Loading : WikiLoad
    data class Ready(val summary: WikiSummary) : WikiLoad
    data class Missing(val searchUrl: String?) : WikiLoad
    data class Error(val message: String) : WikiLoad
}

data class WikipediaUiState(
    val trackKey: String? = null,
    val artistName: String? = null,
    val songTitle: String? = null,
    val artist: WikiLoad = WikiLoad.Idle,
    val song: WikiLoad = WikiLoad.Idle
)
