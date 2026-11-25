package com.windstrom5.rebit.playback.autoeq

/** One measured headphone/IEM in the AutoEq catalogue. [path] is the URL-encoded folder under /results. */
data class IemEntry(
    val name: String,
    val path: String,
    val source: String,
    val rig: String?,
    val isInEar: Boolean
) {
    /** Human label for the measurement, e.g. "oratory1990" or "crinacle (GRAS RA0045)". */
    val sourceLabel: String get() = if (rig != null) "$source ($rig)" else source
}

object AutoEqIndex {
    const val RAW_BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    const val INDEX_URL = RAW_BASE + "INDEX.md"

    /**
     * Parses lines such as
     *   - [Moondrop Aria](./oratory1990/in-ear/Moondrop%20Aria) by oratory1990
     *   - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
     * Names and paths may themselves contain parentheses, so we split on the first ") by ".
     */
    fun parse(markdown: String): List<IemEntry> {
        val out = ArrayList<IemEntry>(9000)
        for (line in markdown.lineSequence()) {
            if (!line.startsWith("- [")) continue
            val open = line.indexOf("](./")
            if (open < 0) continue
            val name = line.substring(3, open).trim()
            val rest = line.substring(open + 4)
            val by = rest.indexOf(") by ")
            if (by < 0) continue
            val path = rest.substring(0, by)
            val credit = rest.substring(by + 5).trim()
            val segs = path.split('/')
            if (segs.size < 3) continue
            val typeSeg = segs[segs.size - 2].lowercase()
            val source = credit.substringBefore(" on ").trim()
            val rig = credit.substringAfter(" on ", "").trim().ifEmpty { null }
            out += IemEntry(name, path, source, rig, isInEar = typeSeg.contains("in-ear"))
        }
        return out
    }

    /** Lower is better. oratory1990's measurements are the usual reference for Harman-target EQ. */
    fun sourcePriority(source: String): Int = when (source.lowercase()) {
        "oratory1990" -> 0
        "crinacle" -> 1
        "super review" -> 2
        "rtings" -> 3
        "hypethesonics" -> 4
        "innerfidelity" -> 5
        else -> 9
    }

    /** Entries of the same model grouped, best measurement first. */
    fun groupByModel(entries: List<IemEntry>): List<List<IemEntry>> =
        entries.groupBy { it.name.lowercase() }.values
            .map { g -> g.sortedBy { sourcePriority(it.source) } }
            .sortedBy { it.first().name.lowercase() }

    /** Words must all appear in the name; names starting with the query rank first. */
    fun search(groups: List<List<IemEntry>>, query: String, limit: Int = 60): List<List<IemEntry>> {
        val words = query.lowercase().split(' ', '-', '/').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return groups
            .filter { g -> val n = g.first().name.lowercase(); words.all { n.contains(it) } }
            .sortedBy { g ->
                val n = g.first().name.lowercase()
                (if (n.startsWith(words.first())) 0 else 1) * 1000 + n.length
            }
            .take(limit)
    }

    fun presetUrl(entry: IemEntry, file: String = "ParametricEQ.txt"): String {
        val folder = entry.path.substringAfterLast('/')
        return RAW_BASE + entry.path + "/" + folder + "%20" + file
    }
}
