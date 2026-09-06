package dev.phoneport.core

import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.EOFException
import java.nio.ByteBuffer

object DockerStreams {
    /** TTY streams are plain text; non-TTY streams use Docker's 8-byte frame header. */
    fun logs(input: InputStream, tty: Boolean, emit: (String) -> Unit) {
        if (tty) {
            val reader = input.reader(Charsets.UTF_8); val chars = CharArray(4096)
            while (true) { val n = reader.read(chars); if (n == -1) break; emit(String(chars, 0, n)) }
            return
        }
        val header = ByteArray(8)
        while (true) {
            val first = input.read(); if (first == -1) return
            header[0] = first.toByte(); readFully(input, header, 1, 7)
            require(first in 0..2 && header.sliceArray(1..3).all { it == 0.toByte() }) { "Invalid Docker log frame" }
            val length = ByteBuffer.wrap(header, 4, 4).int
            require(length in 0..16_777_216) { "Invalid Docker log frame length" }
            val payload = ByteArray(length); readFully(input, payload, 0, length)
            emit(payload.toString(Charsets.UTF_8))
        }
    }
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var position = offset
        while (position < offset + length) {
            val n = input.read(buffer, position, offset + length - position)
            if (n < 0) throw EOFException("Truncated Docker log frame")
            position += n
        }
    }
    fun pull(input: InputStream, emit: (String) -> Unit) {
        input.bufferedReader().forEachLine { line ->
            if (line.isNotBlank()) {
                val event = catalogJson.parseToJsonElement(line) as JsonObject
                val error = event.str("error").ifBlank { (event["errorDetail"] as? JsonObject)?.str("message").orEmpty() }
                if (error.isNotBlank()) throw PortainerException(error)
                emit(listOf(event.str("id"), event.str("status"), event.str("progress")).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
    }
}
