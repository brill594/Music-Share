package com.musicshare.android.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

class DocumentUriResolver {
    fun resolve(treeUriString: String, powerampPath: String): Uri? {
        if (treeUriString.isBlank() || powerampPath.isBlank()) {
            return null
        }
        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val treeSegments = parseDocId(treeDocId) ?: return null
        val pathSegments = parsePowerampPath(
            rawPath = powerampPath.trim(),
            fallbackVolume = treeSegments.volume,
        ) ?: return null
        if (!pathSegments.volume.equals(treeSegments.volume, ignoreCase = true)) {
            return null
        }

        val targetRelative = when {
            pathSegments.relative == treeSegments.relative -> treeSegments.relative
            pathSegments.relative.startsWith("${treeSegments.relative}/") -> pathSegments.relative
            treeSegments.relative.isBlank() -> pathSegments.relative
            pathSegments.relative.isBlank() -> treeSegments.relative
            else -> null
        } ?: return null

        val documentId = if (targetRelative.isBlank()) {
            "${treeSegments.volume}:"
        } else {
            "${treeSegments.volume}:$targetRelative"
        }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    fun isReadable(context: Context, documentUriString: String): Boolean {
        if (documentUriString.isBlank()) {
            return false
        }
        val uri = runCatching { Uri.parse(documentUriString) }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun parsePowerampPath(rawPath: String, fallbackVolume: String): DocSegments? {
        if (rawPath.startsWith("content://", ignoreCase = true)) {
            return null
        }
        val normalized = rawPath
            .removePrefix("file://")
            .trim()
            .replace('\\', '/')
            .trimStart('/')

        if (normalized.isBlank()) {
            return null
        }

        return when {
            normalized.startsWith("storage/emulated/0/", ignoreCase = true) ->
                DocSegments("primary", normalized.removePrefix("storage/emulated/0/"))
            normalized.startsWith("sdcard/", ignoreCase = true) ->
                DocSegments("primary", normalized.removePrefix("sdcard/"))
            ':' in normalized -> parseDocId(normalized)
            else -> {
                val segments = normalized.split('/').filter { it.isNotBlank() }
                if (segments.isEmpty()) {
                    null
                } else if (segments.first().lowercase(Locale.US) == "primary") {
                    DocSegments("primary", segments.drop(1).joinToString("/"))
                } else if (segments.first().contains('-')) {
                    DocSegments(segments.first(), segments.drop(1).joinToString("/"))
                } else {
                    DocSegments(fallbackVolume, segments.joinToString("/"))
                }
            }
        }
    }

    private fun parseDocId(docId: String): DocSegments? {
        val index = docId.indexOf(':')
        return if (index >= 0) {
            DocSegments(
                volume = docId.substring(0, index),
                relative = docId.substring(index + 1).trim('/'),
            )
        } else {
            val segments = docId.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) {
                null
            } else {
                DocSegments(segments.first(), segments.drop(1).joinToString("/"))
            }
        }
    }

    private data class DocSegments(
        val volume: String,
        val relative: String,
    )
}
