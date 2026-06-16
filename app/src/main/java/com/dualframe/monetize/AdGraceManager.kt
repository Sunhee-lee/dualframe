package com.dualframe.monetize

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

object AdGraceManager {

    private const val TAG = "AdGraceManager"
    private const val PREFS = "ad_grace"
    private const val KEY_COUNT = "grace_count"
    private const val KEY_TIMESTAMP = "grace_timestamp"
    private const val GRACE_PERIOD_MS = 24 * 60 * 60 * 1000L

    fun canUseGrace(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUsed = prefs.getLong(KEY_TIMESTAMP, 0)
        if (System.currentTimeMillis() - lastUsed > GRACE_PERIOD_MS) {
            prefs.edit().putInt(KEY_COUNT, 0).apply()
        }
        return prefs.getInt(KEY_COUNT, 0) < 1
    }

    fun useGrace(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Grace save used")
    }

    fun resetGrace(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COUNT, 0).apply()
        Log.i(TAG, "Grace count reset")
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
