package com.example.sploot.data.export

import java.io.Writer

/**
 * Minimal RFC-4180-style CSV writer/reader: quotes fields containing commas,
 * quotes or line breaks, doubles embedded quotes. Round-trip safe.
 */
object Csv {

    fun escape(field: String?): String {
        if (field == null) return ""
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"${field.replace("\"", "\"\"")}\"" else field
    }

    fun line(fields: List<String?>): String = fields.joinToString(",") { escape(it) }

    fun writeRow(writer: Writer, fields: List<String?>) {
        writer.write(line(fields))
        writer.write("\r\n")
    }

    /** Parses one CSV line (quoted fields supported). Returns empty list on blank line. */
    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> {
                    out += sb.toString(); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    fun parse(text: String): List<List<String>> =
        text.split("\r\n", "\n").filter { it.isNotEmpty() }.map { parseLine(it) }
}
