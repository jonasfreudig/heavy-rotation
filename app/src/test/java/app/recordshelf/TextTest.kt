package app.recordshelf

import app.recordshelf.util.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TextTest {
    @Test
    fun namesOfSymbolsOnlyStayApart() {
        assertEquals("!!!", Text.norm("!!!"))
        assertNotEquals(Text.norm("!!!"), Text.norm("+/-"))
        assertEquals("beyonce", Text.norm("Beyoncé"))
        assertEquals("simon and garfunkel", Text.norm("Simon & Garfunkel"))
        assertEquals("dont stop", Text.norm("Don’t Stop!"))
    }
}
