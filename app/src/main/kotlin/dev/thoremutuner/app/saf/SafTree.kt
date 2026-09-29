package dev.thoremutuner.app.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.thoremutuner.core.scan.ByteSource
import dev.thoremutuner.core.scan.DocEntry
import dev.thoremutuner.core.scan.TreeAccess
import dev.thoremutuner.core.store.FolderGrant
import java.io.FileNotFoundException
import java.io.IOException

/** Thrown when a granted folder is no longer accessible (revoked, emulator reinstalled, ...). */
class PermissionLostException : IOException("Folder access was revoked; grant the folder again")

/**
 * Storage Access Framework helpers. Children are listed with DocumentsContract queries (not
 * DocumentFile, which is slow); files are written with mode "wt" (PLAN section 7.2).
 */
class SafAccess(private val resolver: ContentResolver) {

    // ------------------------------------------------------------------ permissions

    fun takePersistable(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        resolver.takePersistableUriPermission(uri, flags)
    }

    fun release(treeUri: String) {
        val uri = Uri.parse(treeUri)
        for (p in resolver.persistedUriPermissions.filter { it.uri == uri }) {
            val flags = (if (p.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (p.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(uri, flags) }
        }
    }

    fun hasPermission(treeUri: String, write: Boolean): Boolean {
        val uri = Uri.parse(treeUri)
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && (!write || it.isWritePermission) }
    }

    /** Describes a freshly picked tree. */
    fun grantFor(treeUri: Uri): FolderGrant {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val label = displayName(treeUri, rootId) ?: rootId.substringAfterLast('/').substringAfter(':').ifEmpty { "Folder" }
        return FolderGrant(treeUri.toString(), rootId, label)
    }

    fun documentUri(treeUri: String, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), documentId)

    // ------------------------------------------------------------------ listing

    fun children(treeUri: String, parentDocumentId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeUri), parentDocumentId)
        val out = mutableListOf<DocEntry>()
        val cursor = try {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw PermissionLostException()
        } ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += DocEntry(
                    documentId = id,
                    displayName = c.getString(1) ?: id.substringAfterLast('/'),
                    isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    fun treeAccess(treeUri: String): TreeAccess {
        val saf = this
        return object : TreeAccess {
            override fun children(dirDocumentId: String): List<DocEntry> = saf.children(treeUri, dirDocumentId)

            override fun open(documentId: String): ByteSource {
                val pfd = try {
                    resolver.openFileDescriptor(documentUri(treeUri, documentId), "r")
                } catch (e: SecurityException) {
                    throw PermissionLostException()
                } ?: throw FileNotFoundException("unreadable document")
                return SafByteSource(pfd)
            }
        }
    }

    /** Finds `a/b/c.ini` below the tree root (case-sensitive first, then case-insensitive). */
    fun resolve(grant: FolderGrant, relativePath: String): DocEntry? {
        var parent = grant.rootDocumentId
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var found: DocEntry? = null
        for ((i, part) in parts.withIndex()) {
            val kids = children(grant.treeUri, parent)
            val match = kids.firstOrNull { it.displayName == part } ?: kids.firstOrNull { it.displayName.equals(part, true) } ?: return null
            if (i < parts.lastIndex && !match.isDirectory) return null
            parent = match.documentId
            found = match
        }
        return found
    }

    fun exists(grant: FolderGrant, relativePath: String): Boolean = resolve(grant, relativePath) != null

    // ------------------------------------------------------------------ read / write

    fun readText(uri: Uri, maxBytes: Int = MAX_TEXT): String {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("unreadable document")
        return input.use {
            val bytes = it.readNBytesCompat(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("File is too large to edit safely")
            String(bytes, Charsets.UTF_8)
        }
    }

    fun readTextOrNull(grant: FolderGrant, relativePath: String): String? {
        val entry = resolve(grant, relativePath) ?: return null
        if (entry.isDirectory) throw IOException("Expected a file but found a folder")
        return readText(documentUri(grant.treeUri, entry.documentId))
    }

    /**
     * Writes [text] to [relativePath], creating missing folders and the file. Uses mode "wt"
     * (truncate); providers that reject "wt" get "w" and the caller verifies by reading back.
     * Returns the document URI.
     */
    fun writeText(grant: FolderGrant, relativePath: String, text: String): Uri {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() && parts.none { it == ".." }) { "invalid path" }
        var parentId = grant.rootDocumentId
        for (dir in parts.dropLast(1)) {
            val existing = children(grant.treeUri, parentId).firstOrNull { it.displayName == dir && it.isDirectory }
            parentId = existing?.documentId ?: run {
                val created = DocumentsContract.createDocument(resolver, documentUri(grant.treeUri, parentId), Document.MIME_TYPE_DIR, dir)
                    ?: throw IOException("Could not create folder $dir")
                DocumentsContract.getDocumentId(created)
            }
        }
        val name = parts.last()
        val existing = children(grant.treeUri, parentId).firstOrNull { it.displayName == name && !it.isDirectory }
        val uri = if (existing != null) {
            documentUri(grant.treeUri, existing.documentId)
        } else {
            DocumentsContract.createDocument(resolver, documentUri(grant.treeUri, parentId), "application/octet-stream", name)
                ?: throw IOException("Could not create $name")
        }
        writeBytes(uri, text.toByteArray(Charsets.UTF_8))
        return uri
    }

    fun writeBytes(uri: Uri, bytes: ByteArray) {
        val out = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } catch (e: UnsupportedOperationException) {
            resolver.openOutputStream(uri, "w")
        } ?: throw IOException("Could not open the file for writing")
        out.use { it.write(bytes); it.flush() }
    }

    fun delete(uri: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    private fun displayName(treeUri: Uri, documentId: String): String? = runCatching {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_TEXT = 2 * 1024 * 1024
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
