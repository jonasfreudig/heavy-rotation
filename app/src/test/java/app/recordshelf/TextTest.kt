package app.recordshelf

import app.recordshelf.util.Text
import app.recordshelf.util.finiteJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TextTest {
    @Test
    fun numbersJsonCantCarryGoOutAsZero() {
        // A 0/0 in an array: Android lets one in through an array made from a list, this org.json doesn't, so it goes in directly.
        val shares = JSONArray().put(0.5)
        @Suppress("UNCHECKED_CAST")
        (JSONArray::class.java.getDeclaredField("myArrayList").apply { isAccessible = true }.get(shares) as ArrayList<Any?>).addAll(listOf(0.0 / 0.0, 1.0 / 0.0))
        val o = JSONObject().put("ok", 1.5).put("life", JSONObject().put("shares", shares))
        assertEquals(listOf(".life.shares[1]", ".life.shares[2]"), o.finiteJson())
        assertEquals("[0.5,0,0]", JSONObject(o.toString()).getJSONObject("life").getJSONArray("shares").toString())
    }

    @Test
    fun namesOfSymbolsOnlyStayApart() {
        assertEquals("!!!", Text.norm("!!!"))
        assertNotEquals(Text.norm("!!!"), Text.norm("+/-"))
        assertEquals("beyonce", Text.norm("Beyoncé"))
        assertEquals("simon and garfunkel", Text.norm("Simon & Garfunkel"))
        assertEquals("dont stop", Text.norm("Don’t Stop!"))
    }
}
