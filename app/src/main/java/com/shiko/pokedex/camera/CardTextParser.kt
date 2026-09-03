package com.shiko.pokedex.camera

/**
 * Best-effort heuristics to pull identifying details out of raw OCR lines from
 * a Pokémon card: several ranked name candidates (not just one, so a misread or
 * partially-covered top line doesn't sink the whole scan), plus the collector
 * number and HP if visible — both used to disambiguate between different cards
 * that happen to share a name (e.g. "Pikachu" appears in dozens of sets).
 */
object CardTextParser {

    data class ParsedCard(
        val nameCandidates: List<String>,
        val collectorNumber: String?,
        val hp: String?
    )

    private val NUMBER_PATTERN = Regex("""(\d{1,3})\s*/\s*(\d{1,4})""")
    private val HP_PATTERN = Regex("""HP\s*(\d{2,3})|(\d{2,3})\s*HP""", RegexOption.IGNORE_CASE)

    // Lines that are almost never the card name — HP line, stage labels, attack costs, etc.
    private val IGNORE_PATTERNS = listOf(
        Regex("""^HP\s*\d+$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+\s*HP$""", RegexOption.IGNORE_CASE),
        Regex("""^(BASIC|STAGE\s*[12]|VMAX|VSTAR|EX|GX|V)$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+$"""),
        NUMBER_PATTERN
    )

    private val WORD_SHAPE = Regex("""^[A-Za-zÀ-ÿ' .-]{2,24}$""")

    fun parse(lines: List<String>): ParsedCard? {
        if (lines.isEmpty()) return null

        val collectorNumber = lines.firstNotNullOfOrNull { line ->
            NUMBER_PATTERN.find(line)?.groupValues?.get(1)
        }

        val hp = lines.firstNotNullOfOrNull { line ->
            HP_PATTERN.find(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }

        val candidates = lines
            .filter { line -> WORD_SHAPE.matches(line) && IGNORE_PATTERNS.none { it.matches(line) } }
            .map { it.trim() }
            .distinct()
            .take(5)

        if (candidates.isEmpty()) return null

        return ParsedCard(nameCandidates = candidates, collectorNumber = collectorNumber, hp = hp)
    }

    /** Loose match: "054" vs "54" should be treated as equal. */
    fun numbersMatch(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val normA = a.trimStart('0').ifEmpty { "0" }
        val normB = b.trimStart('0').ifEmpty { "0" }
        return normA == normB
    }
}
