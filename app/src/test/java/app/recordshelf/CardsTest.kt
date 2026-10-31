package app.recordshelf

import app.recordshelf.history.History
import app.recordshelf.history.Stats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Your cards: songs by plays as bronze, silver, gold and holo, and when each reached its tier. */
class CardsTest {
    private fun at(month: Int, d: Int, k: Int) = LocalDateTime.of(2026, month, d, 12, 0).toEpochSecond(ZoneOffset.UTC) + k * 300L

    @Test
    fun tiersAndWhenTheyWereReached() {
        val b = History.Builder()
        // Gold: 30 plays in September, 22 in October (its 50th play on October 20). Silver: 25 plays in September.
        // Bronze: 12 plays in September. Almost: 8 plays. Too few: 5 plays.
        repeat(30) { b.add(at(9, 1 + it / 10, it), "A", "Gold", "One") }
        repeat(22) { b.add(at(10, 1 + it, 0), "A", "Gold", "One") }
        repeat(25) { b.add(at(9, 15, it), "B", "Silver", "Two") }
        repeat(12) { b.add(at(9, 20, it), "C", "Bronze", "") }
        repeat(8) { b.add(at(9, 22, it), "C", "Almost", "") }
        repeat(5) { b.add(at(9, 23, it), "C", "Few", "") }
        val s = Stats(b.build(), ZoneId.of("UTC"), at(10, 30, 0))

        val c = s.cards()
        val cards = c.getJSONArray("cards")
        assertEquals(listOf("Gold", "Silver", "Bronze"), (0 until cards.length()).map { cards.getJSONObject(it).getString("title") })
        assertEquals("the 50th play made it gold", at(10, 20, 0) * 1000, cards.getJSONObject(0).getLong("reached"))
        assertEquals("the 25th play made it silver", at(9, 15, 24) * 1000, cards.getJSONObject(1).getLong("reached"))
        assertEquals(3, c.getInt("artists"))
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000, c.getLong("monthFrom"))
        val almost = c.getJSONArray("almost")
        assertEquals(1, almost.length())
        assertEquals("Almost", almost.getJSONObject(0).getString("title"))

        val sum = s.cardsSummary()
        assertEquals(3, sum.getInt("total"))
        assertEquals("bronze, silver, gold, holo", listOf(1, 1, 1, 0), (0 until 4).map { sum.getJSONArray("counts").getInt(it) })
        assertEquals("only the gold card reached its tier this month", 1, sum.getInt("fresh"))
        val best = sum.getJSONArray("best")
        assertEquals("the best of each tier held, top tier first", listOf("Gold", "Silver", "Bronze"), (0 until best.length()).map { best.getJSONObject(it).getString("title") })
    }
}
