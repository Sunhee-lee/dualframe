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

object AdRewardManager {

    private const val TAG = "AdRewardManager"

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    fun loadAd(activity: Activity) {
        if (rewardedAd != null || isLoading) return
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
            onFailed("Ad not available. Please try again later.")
            loadAd(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadAd(activity)
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                onFailed("Ad not available. Please try again later.")
                loadAd(activity)
            }
        }

        ad.show(activity) { _ ->
            Log.i(TAG, "User earned reward")
            onRewarded()
        }
    }

    val isAdReady: Boolean get() = rewardedAd != null
}
