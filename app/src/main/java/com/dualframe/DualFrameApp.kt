package com.dualframe

import android.app.Application
import com.dualframe.monetize.BillingManager

class DualFrameApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Connect billing on app start to restore existing purchases.
        // If user bought pro_upgrade on this or another device with the same
        // Google account, this restores the entitlement automatically.
        BillingManager.getInstance(this).connect()
    }
}
