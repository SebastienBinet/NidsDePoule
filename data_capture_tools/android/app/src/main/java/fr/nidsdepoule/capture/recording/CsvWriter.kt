package fr.nidsdepoule.capture.recording

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Buffered CSV writer. All writes go through an 8 KB buffer for I/O efficiency.
 * At ~500 Hz with ~50 bytes/line, this flushes roughly every 0.6 seconds.
 */
class CsvWriter(file: File, header: String) {

    private val writer = BufferedWriter(FileWriter(file), 8192)
    var bytesWritten: Long = 0L
        private set
    var lineCount: Long = 0L
        private set

    init {
        writer.write(header)
        writer.newLine()
        bytesWritten += header.length + 1
    }

    fun writeLine(line: String) {
        writer.write(line)
        writer.newLine()
        bytesWritten += line.length + 1
        lineCount++
    }

    fun flush() {
        writer.flush()
    }

    fun close() {
        writer.flush()
        writer.close()
    }
}
