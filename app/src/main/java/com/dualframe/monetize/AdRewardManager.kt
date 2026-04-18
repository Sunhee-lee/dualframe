package com.dualframe.monetize

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Manages AdMob Rewarded Ads for the watermark-removal flow.
 *
 * Usage: call loadAd() early, then showAd() when the user taps "Watch ad".
 * The onRewarded callback fires only if the user watched the full ad.
 */
object AdRewardManager {

    private const val TAG = "AdRewardManager"

    // *** GOOGLE SAMPLE TEST AD UNIT ID ***
    // Replace with your real production rewarded ad unit ID before release.
    const val REWARDED_TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    /** Preload a rewarded ad so it's ready when the user taps "Watch ad". */
    fun loadAd(activity: Activity) {
        if (rewardedAd != null || isLoading) return
        isLoading = true
        RewardedAd.load(
            activity,
            REWARDED_TEST_AD_UNIT_ID,
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

    /** Preload using Context (for calling from Application.onCreate). */
    fun preload(context: android.content.Context) {
        if (rewardedAd != null || isLoading) return
        isLoading = true
        RewardedAd.load(
            context,
            REWARDED_TEST_AD_UNIT_ID,
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

    /**
     * Show the loaded rewarded ad. Calls [onRewarded] only if the user earns the reward.
     * Calls [onFailed] if the ad isn't loaded or an error occurs.
     */
    fun showAd(
        activity: Activity,
        onRewarded: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val ad = rewardedAd
        if (ad == null) {
            onFailed("Ad not ready. Please try again.")
            loadAd(activity) // try to preload for next attempt
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadAd(activity) // preload next ad
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                onFailed("Ad failed to show: ${error.message}")
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
