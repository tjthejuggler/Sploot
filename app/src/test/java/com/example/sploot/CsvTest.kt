package com.example.sploot

import com.example.sploot.data.export.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    @Test
    fun `plain fields pass through unquoted`() {
        assertEquals("1700000000000,600,PPI", Csv.line(listOf("1700000000000", "600", "PPI")))
    }

    @Test
    fun `commas quotes and newlines are escaped`() {
        assertEquals(
            "\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\"",
            Csv.line(listOf("a,b", "say \"hi\"", "line1\nline2")),
        )
    }

    @Test
    fun `null fields become empty cells`() {
        assertEquals("600,,", Csv.line(listOf("600", null, null)))
    }

    @Test
    fun `line round-trips through parse`() {
        val fields = listOf(
            "1700000000000",
            "600",
            "a,comma",
            "quote\"inside",
            "multi\nline",
            "",
        )
        val parsed = Csv.parseLine(Csv.line(fields))
        assertEquals(fields, parsed)
    }

    @Test
    fun `full document round-trips`() {
        val rows = listOf(
            listOf("timestampMs", "intervalMs", "note"),
            listOf("1", "600", "hello, night"),
            listOf("2", "700", "escaped \"quote\""),
        )
        val doc = rows.joinToString("\r\n") { Csv.line(it) } + "\r\n"
        assertEquals(rows, Csv.parse(doc))
    }
}
