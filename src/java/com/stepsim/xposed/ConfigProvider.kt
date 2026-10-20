package com.stepsim.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 配置 ContentProvider，供 hook 进程跨进程读取开关和参数。
 * Authority: com.stepsim.xposed.config
 * URI: content://com.stepsim.xposed.config/config
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.stepsim.xposed.config"
        val CONFIG_URI: Uri = Uri.parse("content://$AUTHORITY/config")
        const val PREFS_NAME = "stepsim_config"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf("enabled", "pace_sec", "fluctuation"))
        cursor.addRow(arrayOf(
            if (prefs.getBoolean("enabled", false)) 1 else 0,
            prefs.getInt("pace_sec", 360),
            prefs.getInt("fluctuation", 60),
        ))
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/config"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
