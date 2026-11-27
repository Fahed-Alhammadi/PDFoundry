package io.pdfoundry.app.data

/** The result of reading page ranges typed by the user. */
sealed interface RangeResult {
    /** Each part is a list of 0-based page indexes, in the order typed. */
    data class Ok(val parts: List<List<Int>>) : RangeResult
    data class Bad(val message: String) : RangeResult
}

/** Page ranges for Split PDF. The same rules and messages as on iOS and desktop. */
object PageRanges {
    /** Reads text such as "1-3, 4-6, 9" (pages counted from 1). Spaces are ignored; overlaps are allowed. */
    fun parse(text: String, pageCount: Int): RangeResult {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return RangeResult.Bad("Enter page ranges, such as 1-3, 4-6.")
        val parts = mutableListOf<List<Int>>()
        for (piece in clean.split(',')) {
            if (piece.isEmpty()) continue
            val match = Regex("^(\\d+)(?:-(\\d+))?$").matchEntire(piece) ?: return RangeResult.Bad("Use numbers, dashes and commas, such as 1-3, 4-6.")
            val first = match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
            val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: Int.MAX_VALUE } ?: first
            for (page in listOf(first, last)) {
                if (page < 1 || page > pageCount) return RangeResult.Bad("Page $page doesn't exist. This PDF has $pageCount ${if (pageCount == 1) "page" else "pages"}.")
            }
            if (first > last) return RangeResult.Bad("Write ranges from low to high, such as 3-5.")
            parts += (first - 1 until last).toList()
        }
        if (parts.isEmpty()) return RangeResult.Bad("Use numbers, dashes and commas, such as 1-3, 4-6.")
        return RangeResult.Ok(parts)
    }

    /** Consecutive parts of [n] pages; the last one may be shorter. Every page is `every(1, count)`. */
    fun every(n: Int, pageCount: Int): List<List<Int>> {
        val size = n.coerceAtLeast(1)
        return (0 until pageCount).chunked(size)
    }
}
