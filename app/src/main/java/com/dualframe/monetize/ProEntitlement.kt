package com.dualframe.monetize

import android.content.Context

/**
 * Simple local persistence for PRO entitlement (watermark removal).
 * In production, validate against Google Play Billing purchase state on each launch.
 * This local flag is a cache — the source of truth should be the Play Store.
 */
object ProEntitlement {

    private const val PREFS = "dualframe_pro"
    private const val KEY_PRO = "is_pro_owned"

    fun isProOwned(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRO, false)

    fun grantPro(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PRO, true).apply()
    }
}
