package com.shiko.pokedex.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CardIndexEntry(
    val id: String,
    val name: String,
    val number: String,
    val hp: String,
    val setId: String,
    val setName: String
)

data class IndexMatch(val entry: CardIndexEntry, val confident: Boolean)

/**
 * Offline identification: ~20k English Pokémon cards bundled in the APK
 * (assets/cards_index.json). Matching runs entirely on-device — no network call.
 */
object LocalCardIndex {

    private var entries: List<CardIndexEntry> = emptyList()
    private var byNormalizedName: Map<String, List<CardIndexEntry>> = emptyMap()
    private val mutex = Mutex()
    private var loaded = false

    // Score weights. Number/HP matches are worth far more than which OCR line
    // happened to read the name, because the name line is the least reliable
    // signal when many cards share a name (the actual failure mode we kept hitting).
    private const val EXACT_NAME_SCORE = 100
    private const val FUZZY_NAME_PENALTY_PER_EDIT = 20
    private const val NUMBER_MATCH_BONUS = 500
    private const val HP_MATCH_BONUS = 200
    private const val MAX_FUZZY_DISTANCE = 2

    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                val json = context.applicationContext.assets
                    .open("cards_index.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val type = object : TypeToken<List<CardIndexEntry>>() {}.type
                entries = Gson().fromJson(json, type)
                byNormalizedName = entries.groupBy { normalize(it.name) }
            }
            loaded = true
        }
    }

    /**
     * Scores every plausible entry across ALL OCR name-line candidates at once —
     * not just whichever candidate line happens to have any match at all — then
     * picks the highest total score. Number and HP matches dominate the score,
     * so a card whose name-line was second- or third-ranked by OCR confidence
     * still wins if its number/HP actually match, instead of the pipeline
     * settling for whichever name candidate was tried first.
     *
     * `confident = false` means the winning entry's edge over the runner-up came
     * only from name-line ordering, not from an actual number/HP match — i.e.
     * this is a best-effort guess, not a verified identification.
     */
    private data class Scored(val entry: CardIndexEntry, val score: Int, val disambiguated: Boolean)

    fun findBest(nameCandidates: List<String>, collectorNumber: String?, hp: String?): IndexMatch? {
        if (nameCandidates.isEmpty()) return null

        val scored = HashMap<String, Scored>() // keyed by entry.id, keep best score per entry

        for (candidate in nameCandidates) {
            val norm = normalize(candidate)

            val exactMatches = byNormalizedName[norm].orEmpty()
            for (entry in exactMatches) {
                considerCandidate(scored, entry, EXACT_NAME_SCORE, collectorNumber, hp)
            }

            // Only bother with fuzzy matching for this candidate if it had no exact hits —
            // exact hits are strictly more reliable, and fuzzy-scanning all 20k entries
            // per candidate line is the expensive path.
            if (exactMatches.isEmpty()) {
                for (entry in entries) {
                    val dist = levenshtein(norm, normalize(entry.name))
                    if (dist <= MAX_FUZZY_DISTANCE) {
                        val nameScore = EXACT_NAME_SCORE - dist * FUZZY_NAME_PENALTY_PER_EDIT
                        considerCandidate(scored, entry, nameScore, collectorNumber, hp)
                    }
                }
            }
        }

        if (scored.isEmpty()) return null

        val ranked = scored.values.sortedByDescending { it.score }
        val best = ranked.first()
        val runnerUpScore = ranked.getOrNull(1)?.score ?: Int.MIN_VALUE

        // Confident if a number/HP match actually contributed, OR the winner is
        // unambiguously ahead even without one (e.g. genuinely only one plausible entry).
        val confident = best.disambiguated || (best.score - runnerUpScore) >= NUMBER_MATCH_BONUS

        return IndexMatch(best.entry, confident)
    }

    private fun considerCandidate(
        scored: HashMap<String, Scored>,
        entry: CardIndexEntry,
        nameScore: Int,
        collectorNumber: String?,
        hp: String?
    ) {
        var total = nameScore
        var disambiguated = false

        if (collectorNumber != null && numbersMatch(entry.number, collectorNumber)) {
            total += NUMBER_MATCH_BONUS
            disambiguated = true
        }
        if (hp != null && entry.hp == hp) {
            total += HP_MATCH_BONUS
            disambiguated = true
        }

        val existing = scored[entry.id]
        if (existing == null || total > existing.score) {
            scored[entry.id] = Scored(entry, total, disambiguated)
        }
    }

    private fun numbersMatch(a: String, b: String): Boolean {
        val normA = a.trimStart('0').ifEmpty { "0" }
        val normB = b.trimStart('0').ifEmpty { "0" }
        return normA == normB
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
