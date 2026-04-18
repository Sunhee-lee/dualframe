package com.dualframe

import android.app.Application
import com.dualframe.monetize.AdRewardManager
import com.dualframe.monetize.BillingManager

class DualFrameApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore existing purchases on app start
        BillingManager.getInstance(this).connect()
        // Preload rewarded ad so it's ready on first "Watch Ad" tap
        AdRewardManager.preload(this)
    }
}
