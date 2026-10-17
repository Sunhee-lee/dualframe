package com.dualframe

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.dualframe.monetize.AdRewardManager
import com.dualframe.monetize.BillingManager
import com.dualframe.util.FileStorage

class DualFrameApp : Application() {

    /**
     * Foreground tracking for ad retries: background retries would burn requests
     * for a user who is not even looking at the app.
     */
    private var startedActivities = 0

    private val foregroundTracker = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) AdRewardManager.notifyForeground(true)
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                AdRewardManager.notifyForeground(false)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        BillingManager.getInstance(this).connect()
        if (!com.dualframe.monetize.ProEntitlement.isProOwned(this)) {
            registerActivityLifecycleCallbacks(foregroundTracker)
            // Initializes the Mobile Ads SDK off the main thread and preloads
            // once initialization reports back, instead of racing it.
            AdRewardManager.init(this)
        }
        FileStorage.cleanupStaleTemp(this)
    }
}
