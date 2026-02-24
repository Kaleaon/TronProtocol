package com.tronprotocol.app.plugins

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.Date

/**
 * File Manager Plugin
 *
 * Provides full filesystem access for document editing, creation, and management.
 * Inspired by ToolNeuron's FileManagerPlugin and landseek's document processing.
 *
 * SECURITY NOTE: This plugin provides extensive file access. Use with caution.
 */
class FileManagerPlugin : Plugin {

    companion object {
        private const val TAG = "FileManagerPlugin"
        private const val ID = "file_manager"
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB limit
    }

    private var context: Context? = null

    private val protectedWritePrefixes = listOf("/proc", "/sys", "/dev", "/system", "/vendor", "/data")

    override val id: String = ID

    override val name: String = "File Manager"

    override val description: String =
        "Full filesystem access: read, write, create, delete, list files and directories. " +
            "Supports document editing and creation. Commands: read|path, write|path|content, " +
            "list|path, delete|path, create|path, move|from|to, copy|from|to, mkdir|path"

    override var isEnabled: Boolean = true

    override fun execute(input: String): PluginResult {
        val startTime = System.currentTimeMillis()

        return try {
            val parts = input.split("\\|".toRegex())
            if (parts.isEmpty()) {
                throw Exception("No command specified")
            }

            val command = parts[0].trim().lowercase()
            val result = when (command) {
                "read" -> {
                    if (parts.size < 2) throw Exception("Path required")
                    readFile(parts[1])
                }
                "write" -> {
                    if (parts.size < 3) throw Exception("Path and content required")
                    writeFile(parts[1], parts[2])
                }
                "append" -> {
                    if (parts.size < 3) throw Exception("Path and content required")
                    appendFile(parts[1], parts[2])
                }
                "create" -> {
                    if (parts.size < 2) throw Exception("Path required")
                    createFile(parts[1])
                }
                "delete" -> {
                    if (parts.size < 2) throw Exception("Path required")
                    deleteFile(parts[1])
                }
                "list" -> {
                    val path = if (parts.size > 1) parts[1] else "."
                    listFiles(path)
                }
                "mkdir" -> {
                    if (parts.size < 2) throw Exception("Path required")
                    createDirectory(parts[1])
                }
                "move" -> {
                    if (parts.size < 3) throw Exception("Source and destination required")
                    moveFile(parts[1], parts[2])
                }
                "copy" -> {
                    if (parts.size < 3) throw Exception("Source and destination required")
                    copyFile(parts[1], parts[2])
                }
                "exists" -> {
                    if (parts.size < 2) throw Exception("Path required")
                    checkExists(parts[1])
                }
                "info" -> {
                    if (parts.size < 2) throw Exception("Path required")
                    getFileInfo(parts[1])
                }
                "search" -> {
                    if (parts.size < 3) throw Exception("Directory and pattern required")
                    searchFiles(parts[1], parts[2])
                }
                else -> throw Exception("Unknown command: $command")
            }

            val duration = System.currentTimeMillis() - startTime
            PluginResult.success(result, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            PluginResult.error("File operation failed: ${e.message}", duration)
        }
    }

    /** Read file contents */
    private fun readFile(path: String): String {
        val file = resolveFile(path)

        if (!file.exists()) throw IOException("File not found: $path")
        if (!file.isFile) throw IOException("Not a file: $path")
        if (file.length() > MAX_FILE_SIZE) {
            throw IOException("File too large (max 10MB): ${file.length()} bytes")
        }

        val content = StringBuilder()
        BufferedReader(FileReader(file)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                content.append(line).append("\n")
                line = reader.readLine()
            }
        }

        return "Read ${file.length()} bytes from $path:\n\n$content"
    }

    /** Write content to file (overwrites existing) */
    private fun writeFile(path: String, content: String): String {
        val file = resolveFile(path)
        enforceWriteAllowed(file)

        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }

        BufferedWriter(FileWriter(file, false)).use { writer ->
            writer.write(content)
        }

        return "Wrote ${content.length} characters to $path"
    }

    /** Append content to file */
    private fun appendFile(path: String, content: String): String {
        val file = resolveFile(path)
        enforceWriteAllowed(file)

        if (!file.exists()) throw IOException("File not found: $path")

        BufferedWriter(FileWriter(file, true)).use { writer ->
            writer.write(content)
        }

        return "Appended ${content.length} characters to $path"
    }

    /** Create empty file */
    private fun createFile(path: String): String {
        val file = resolveFile(path)
        enforceWriteAllowed(file)

        if (file.exists()) throw IOException("File already exists: $path")

        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }

        if (!file.createNewFile()) {
            throw IOException("Failed to create file: $path")
        }

        return "Created file: ${file.absolutePath}"
    }

    /** Delete file or directory */
    private fun deleteFile(path: String): String {
        val file = resolveFile(path)
        enforceWriteAllowed(file)

        if (!file.exists()) throw IOException("File not found: $path")

        val listing = file.list()
        if (file.isDirectory && listing != null && listing.isNotEmpty()) {
            throw IOException("Directory not empty: $path")
        }

        if (!file.delete()) throw IOException("Failed to delete: $path")

        return "Deleted: $path"
    }

    /** List files in directory */
    private fun listFiles(path: String): String {
        val dir = resolveFile(path)

        if (!dir.exists()) throw IOException("Directory not found: $path")
        if (!dir.isDirectory) throw IOException("Not a directory: $path")

        val files = dir.listFiles() ?: return "Empty directory"

        val result = StringBuilder()
        result.append("Contents of ${dir.absolutePath}:\n\n")

        var fileCount = 0
        var dirCount = 0
        for (file in files) {
            if (file.isDirectory) {
                result.append("[DIR]  ${file.name}/\n")
                dirCount++
            } else {
                result.append("[FILE] ${file.name} (${formatSize(file.length())})\n")
                fileCount++
            }
        }

        result.append("\nTotal: $dirCount directories, $fileCount files")
        return result.toString()
    }

    /** Create directory */
    private fun createDirectory(path: String): String {
        val dir = resolveFile(path)
        enforceWriteAllowed(dir)

        if (dir.exists()) throw IOException("Directory already exists: $path")
        if (!dir.mkdirs()) throw IOException("Failed to create directory: $path")

        return "Created directory: ${dir.absolutePath}"
    }

    /** Move/rename file */
    private fun moveFile(from: String, to: String): String {
        val source = resolveFile(from)
        val dest = resolveFile(to)
        enforceWriteAllowed(dest)

        if (!source.exists()) throw IOException("Source not found: $from")
        if (dest.exists()) throw IOException("Destination already exists: $to")

        if (!source.renameTo(dest)) {
            copyFileContents(source, dest)
            if (!source.delete()) {
                throw IOException("Moved file data but could not delete source: $from")
            }
        }

        return "Moved $from to $to"
    }

    /** Copy file */
    private fun copyFile(from: String, to: String): String {
        val source = resolveFile(from)
        val dest = resolveFile(to)
        enforceWriteAllowed(dest)

        if (!source.exists()) throw IOException("Source not found: $from")
        if (!source.isFile) throw IOException("Can only copy files, not directories: $from")
        if (dest.exists()) throw IOException("Destination already exists: $to")

        copyFileContents(source, dest)

        return "Copied $from to $to"
    }

    private fun copyFileContents(source: File, dest: File) {
        dest.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }

        FileInputStream(source).use { inputStream ->
            FileOutputStream(dest).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead = inputStream.read(buffer)
                while (bytesRead != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesRead = inputStream.read(buffer)
                }
                outputStream.fd.sync()
            }
        }
    }

    /** Check if file/directory exists */
    private fun checkExists(path: String): String {
        val file = resolveFile(path)
        return if (file.exists()) {
            val type = if (file.isDirectory) "directory" else "file"
            "Yes, $type exists: ${file.absolutePath}"
        } else {
            "No, does not exist: $path"
        }
    }

    /** Get file information */
    private fun getFileInfo(path: String): String {
        val file = resolveFile(path)

        if (!file.exists()) throw IOException("File not found: $path")

        return buildString {
            append("Path: ${file.absolutePath}\n")
            append("Type: ${if (file.isDirectory) "Directory" else "File"}\n")
            append("Size: ${formatSize(file.length())}\n")
            append("Modified: ${Date(file.lastModified())}\n")
            append("Readable: ${file.canRead()}\n")
            append("Writable: ${file.canWrite()}\n")
            append("Executable: ${file.canExecute()}\n")
        }
    }

    /** Search for files matching pattern */
    private fun searchFiles(dirPath: String, pattern: String): String {
        val dir = resolveFile(dirPath)

        if (!dir.exists() || !dir.isDirectory) {
            throw IOException("Invalid directory: $dirPath")
        }

        val matches = mutableListOf<File>()
        searchRecursive(dir, pattern, matches, 0)

        if (matches.isEmpty()) {
            return "No files found matching: $pattern"
        }

        return buildString {
            append("Found ${matches.size} file(s) matching '$pattern':\n\n")
            for (file in matches) {
                append("${file.absolutePath}\n")
            }
        }
    }

    private fun searchRecursive(dir: File, pattern: String, matches: MutableList<File>, depth: Int) {
        if (depth > 10) return // Limit recursion depth

        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.name.lowercase().contains(pattern.lowercase())) {
                matches.add(file)
            }
            if (file.isDirectory) {
                searchRecursive(file, pattern, matches, depth + 1)
            }
        }
    }

    /** Resolve file path (supports relative and absolute paths) */
    private fun resolveFile(path: String): File {
        val decodedPath = decodePath(path)
        if (containsTraversal(decodedPath)) {
            Log.w(TAG, "Denied path traversal attempt: $path")
            throw SecurityException("Access denied: path traversal detected")
        }

        var file = File(decodedPath)

        // If relative path, resolve from external storage
        if (!file.isAbsolute) {
            val externalStorage = Environment.getExternalStorageDirectory()
            file = File(externalStorage, decodedPath)
        }

        return file.canonicalFile
    }

    private fun enforceWriteAllowed(file: File) {
        val absolutePath = file.absolutePath.lowercase()
        if (protectedWritePrefixes.any { absolutePath == it || absolutePath.startsWith("$it/") }) {
            Log.w(TAG, "Denied write attempt to protected path: $absolutePath")
            throw SecurityException("Access denied: write to protected path")
        }
    }

    private fun decodePath(path: String): String {
        var decoded = path
        repeat(2) {
            decoded = java.net.URLDecoder.decode(decoded, Charsets.UTF_8.name())
        }
        return decoded
    }

    private fun containsTraversal(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == ".." || normalized.contains("../") || normalized.endsWith("/..")
    }

    /** Format file size */
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "${"KMGTPE"[exp - 1]}B"
        return String.format("%.1f %s", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    override fun initialize(context: Context) {
        this.context = context
    }

    override fun destroy() {
        this.context = null
    }
}
