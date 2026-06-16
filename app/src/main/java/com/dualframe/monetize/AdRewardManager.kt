package com.dualframe.monetize

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.sunnlab.dualframe.BuildConfig

// TODO: Add UMP consent flow (required for EU/EEA users)
// TODO: Add privacy policy link to settings page
object AdRewardManager {

    private const val TAG = "AdRewardManager"

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    fun loadAd(activity: Activity) {
        if (rewardedAd != null || isLoading) return
        if (activity.isFinishing || activity.isDestroyed) return
        isLoading = true
        RewardedAd.load(
            activity,
            BuildConfig.REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoading = false
                    Log.i(TAG, "Rewarded ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    Log.e(TAG, "Rewarded ad failed to load: ${error.message}")
                }
            },
        )
    }

    fun preload(context: android.content.Context) {
        if (rewardedAd != null || isLoading) return
        isLoading = true
        RewardedAd.load(
            context,
            BuildConfig.REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoading = false
                    Log.i(TAG, "Rewarded ad preloaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    Log.e(TAG, "Rewarded ad preload failed: ${error.message}")
                }
            },
        )
    }

    fun showAd(
        activity: Activity,
        onRewarded: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val ad = rewardedAd
        if (ad == null) {
            onFailed(activity.getString(com.sunnlab.dualframe.R.string.error_ad_not_available))
            loadAd(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                if (!activity.isFinishing && !activity.isDestroyed) {
                    loadAd(activity)
                }
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onFailed(activity.getString(com.sunnlab.dualframe.R.string.error_ad_not_available))
                    loadAd(activity)
                }
            }
        }

        ad.show(activity) { _ ->
            Log.i(TAG, "User earned reward")
            if (!activity.isFinishing && !activity.isDestroyed) {
                onRewarded()
            }
        }
    }

    val isAdReady: Boolean get() = rewardedAd != null
}
