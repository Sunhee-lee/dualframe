package com.dualframe

import android.app.Application
import com.dualframe.monetize.AdRewardManager
import com.dualframe.monetize.BillingManager
import com.dualframe.util.FileStorage

class DualFrameApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingManager.getInstance(this).connect()
        if (!com.dualframe.monetize.ProEntitlement.isProOwned(this)) {
            AdRewardManager.preload(this)
        }
        FileStorage.cleanupStaleTemp(this)
    }
}
