package com.example.funfy.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Named folder for bookmarks or offline downloads. */
data class MediaFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

internal fun List<MediaFolder>.toJsonArray(): JSONArray {
    val arr = JSONArray()
    forEach { f ->
        arr.put(
            JSONObject()
                .put("id", f.id)
                .put("name", f.name)
                .put("createdAt", f.createdAt),
        )
    }
    return arr
}

internal fun parseMediaFolders(raw: String?): List<MediaFolder> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    MediaFolder(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        createdAt = o.optLong("createdAt").takeIf { it > 0L }
                            ?: System.currentTimeMillis(),
                    ),
                )
            }
        }.sortedBy { it.name.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }
}
