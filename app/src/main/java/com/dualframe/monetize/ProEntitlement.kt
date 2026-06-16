package com.dualframe.monetize

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object ProEntitlement {

    private const val TAG = "ProEntitlement"
    private const val PREFS = "dualframe_pro_secure"
    private const val KEY_PRO = "pro_owned"

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS,
                masterKey,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences failed, falling back", e)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun isProOwned(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRO, false)

    fun grantPro(context: Context) {
        prefs(context).edit().putBoolean(KEY_PRO, true).apply()
    }

    fun revokePro(context: Context) {
        prefs(context).edit().putBoolean(KEY_PRO, false).apply()
    }
}
