package com.example.recordshelf.history

import com.example.recordshelf.net.DzRelease
import com.example.recordshelf.util.Text

/**
 * Release years of albums, for the decade filter: the year a title first came out among an
 * artist's releases on Deezer (remasters and deluxe editions come later), and the year of the
 * record on your shelf, whichever is earlier.
 */
object Years {
    /** The year [title] first came out among [releases] (any edition, any type), 0 if it isn't there. */
    fun match(releases: List<DzRelease>, title: String): Int {
        val want = Text.normLoose(title)
        return releases.filter { Text.normLoose(it.title) == want && it.year > 0 }.minOfOrNull { it.year } ?: 0
    }

    /** The earliest of the known years (0 counts as unknown). */
    fun earliest(vararg years: Int) = years.filter { it > 0 }.minOrNull() ?: 0
}
