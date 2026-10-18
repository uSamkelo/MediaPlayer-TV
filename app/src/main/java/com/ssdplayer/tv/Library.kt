package com.ssdplayer.tv

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Video(
    val uri: Uri,
    val title: String,
    val folder: String,
    val size: Long,
    val badges: List<String> = emptyList(),
)

private val TAGS = Regex(
    """[\s(\[._-]*\b(2160p|1080p|720p|480p|4k|uhd|web-?dl|webrip|bluray|blu-ray|brrip|bdrip|hdrip|hdtv|hdr10\+?|hdr|dovi|dolby[ .]?vision|x26[45]|h[ .]?26[45]|hevc|10bit|ddp?\d|atmos|truehd|dts(-hd)?|remux)\b""",
    RegexOption.IGNORE_CASE,
)

private fun stripExt(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext in VIDEO_EXT) name.substringBeforeLast('.') else name
}

/** "House.Of.The.Dragon.S03E01.2160p.MAX.WEB-DL.DV.HDR-GRP" -> "House Of The Dragon S03E01" */
fun cleanTitle(raw: String): String {
    val spaced = stripExt(raw).replace('.', ' ').replace('_', ' ')
    val cut = TAGS.find(spaced)?.let { spaced.substring(0, it.range.first) } ?: spaced
    val t = cut.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
        .trimEnd('-', '+', '(', '[', ' ')
    if (t.isBlank()) return spaced.replace(Regex("\\s+"), " ").trim()
    return if (t == t.lowercase()) t.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else t
}

/** Quality badges guessed from the file and folder names (not read from the stream). */
fun badgesOf(raw: String): List<String> {
    val s = raw.lowercase()
    return buildList {
        if (Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(s)) add("4K") else if ("1080p" in s) add("1080p")
        if (Regex("\\bdv\\b|dovi|dolby[ ._]?vision").containsMatchIn(s)) add("Dolby Vision")
        if ("hdr" in s) add("HDR")
        if ("atmos" in s) add("Atmos") else if ("truehd" in s) add("TrueHD")
    }
}

private fun hiddenName(name: String) =
    name.startsWith(".") || name.startsWith("$") || name.equals("System Volume Information", ignoreCase = true)

private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp")

object VideoScanner {
    suspend fun scan(ctx: Context, tree: Uri): List<Video> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Video>()
        walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), "Library", 0, out)
        out.sortedWith(compareBy<Video> { it.folder.lowercase() }.thenBy { it.title.lowercase() })
    }

    /** Fallback: every video the TV has indexed, including USB drives. Needs the video permission. */
    suspend fun scanMedia(ctx: Context): List<Video> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Video>()
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            ctx.contentResolver.query(collection, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val bucket = c.getString(3) ?: "Videos"
                    if (hiddenName(name) || hiddenName(bucket)) continue
                    out += Video(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        title = cleanTitle(name),
                        folder = cleanTitle(bucket),
                        size = if (c.isNull(2)) 0L else c.getLong(2),
                        badges = badgesOf("$name $bucket"),
                    )
                }
            }
        }
        out.sortedWith(compareBy<Video> { it.folder.lowercase() }.thenBy { it.title.lowercase() })
    }

    private fun walk(ctx: Context, tree: Uri, parentId: String, folderName: String, depth: Int, out: MutableList<Video>) {
        if (depth > 8) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val subdirs = mutableListOf<Pair<String, String>>()
        runCatching {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    when {
                        hiddenName(name) -> Unit
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> subdirs += id to name
                        mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in VIDEO_EXT ->
                            out += Video(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                title = cleanTitle(name),
                                folder = cleanTitle(folderName),
                                size = size,
                                badges = badgesOf("$name $folderName"),
                            )
                    }
                }
            }
        }
        subdirs.forEach { (id, name) -> walk(ctx, tree, id, name, depth + 1, out) }
    }
}

/** Remembers resume positions. */
object Progress {
    private fun p(ctx: Context) = ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun save(ctx: Context, uri: String, pos: Long, dur: Long) {
        if (dur <= 0) return
        if (pos > dur * 0.95 || pos < 5_000) { clear(ctx, uri); return }
        p(ctx).edit().putLong("p:$uri", pos).putLong("d:$uri", dur).putLong("t:$uri", System.currentTimeMillis()).apply()
    }

    fun clear(ctx: Context, uri: String) {
        p(ctx).edit().remove("p:$uri").remove("d:$uri").remove("t:$uri").apply()
    }

    fun position(ctx: Context, uri: String): Long = p(ctx).getLong("p:$uri", 0L)

    fun fraction(ctx: Context, uri: String): Float {
        val d = p(ctx).getLong("d:$uri", 0L)
        return if (d > 0) (position(ctx, uri).toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun lastPlayed(ctx: Context, uri: String): Long = p(ctx).getLong("t:$uri", 0L)
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    var videos by mutableStateOf<List<Video>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var hasFolder by mutableStateOf(false)
        private set

    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    init { refresh() }

    fun setFolder(uri: Uri) {
        prefs.edit().putString("tree", uri.toString()).putBoolean("media", false).apply()
        refresh()
    }

    /** Use the system video index instead of a picked folder. */
    fun useMediaStore() {
        prefs.edit().remove("tree").putBoolean("media", true).apply()
        refresh()
    }

    /** Go back to the "choose a source" screen. */
    fun resetSource() {
        prefs.edit().remove("tree").putBoolean("media", false).apply()
        videos = emptyList()
        hasFolder = false
    }

    fun refresh() {
        val tree = prefs.getString("tree", null)?.toUri()
        val media = prefs.getBoolean("media", false)
        hasFolder = tree != null || media
        if (!hasFolder) return
        viewModelScope.launch {
            loading = true
            videos = if (media) VideoScanner.scanMedia(getApplication())
            else VideoScanner.scan(getApplication(), tree!!)
            loading = false
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%d MB".format(bytes / (1L shl 20))
}
