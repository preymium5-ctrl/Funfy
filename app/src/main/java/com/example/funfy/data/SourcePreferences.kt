package com.example.funfy.data

import android.content.Context

class SourcePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSource(): VideoSource =
        VideoSource.fromId(prefs.getString(KEY_SOURCE, VideoSource.DEFAULT.id))

    fun setSource(source: VideoSource) {
        val selectableSource = source.takeIf { it.isSelectable } ?: VideoSource.DEFAULT
        prefs.edit().putString(KEY_SOURCE, selectableSource.id).apply()
    }

    companion object {
        private const val PREFS = "funfy_prefs"
        private const val KEY_SOURCE = "video_source"
    }
}
