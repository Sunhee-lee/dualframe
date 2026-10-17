package com.dualframe.monetize

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.sunnlab.dualframe.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// TODO: Add UMP consent flow (required for EU/EEA users)
// TODO: Add privacy policy link to settings page

/**
 * Single source of truth for the rewarded ad.
 *
 * The previous version exposed only "show it or fail", so a tap while a load was
 * still in flight reported failure instantly and the retry button re-reported it
 * forever — the user never found out an ad was on its way. The UI now observes
 * [state] instead, so "still loading" is a state of its own and never a failure.
 *
 * Invariants worth keeping:
 *  - At most one SDK request is in flight. Every entry point funnels into
 *    [ensureLoaded], which no-ops when LOADING or when a fresh ad is in hand.
 *  - A callback from a superseded attempt can never overwrite a newer ad; each
 *    attempt carries a generation number.
 *  - A loaded ad is consumed the moment it is shown, so a spent reference is
 *    never handed to show() a second time.
 */
object AdRewardManager {

    private const val TAG = "DF_AD"

    /** What the save flow needs to know. */
    enum class AdState { IDLE, LOADING, READY, FAILED }

    /**
     * How long a loaded ad is treated as usable.
     *
     * Rewarded ads expire some time after loading, and show() on an expired
     * reference fails. This value is deliberately conservative rather than
     * matched to a documented figure — confirm against Google's current
     * rewarded-ad documentation and adjust here if it differs.
     */
    const val AD_TTL_MS = 50L * 60L * 1000L

    /** Unsticks LOADING if an SDK callback never arrives. Does not cancel the request. */
    private const val LOAD_TIMEOUT_MS = 30_000L

    /** Background recovery after a *confirmed* load failure. */
    private val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L)

    /**
     * Floor between loads started by returning to the app.
     *
     * That path deliberately sits outside the retry cap so an exhausted run can
     * recover, which means app-switching would otherwise issue one request per
     * return — unbounded, and fastest exactly when loads are failing quickly.
     * An explicit retry from the user is not throttled.
     */
    private const val FOREGROUND_LOAD_MIN_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _state = MutableStateFlow(AdState.IDLE)
    val state: StateFlow<AdState> = _state.asStateFlow()

    /** Application context only — nothing here ever holds an Activity. */
    private var appContext: Context? = null
    private var rewardedAd: RewardedAd? = null
    private var loadedAtMs = 0L
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /**
     * Set when an automatic attempt was abandoned without being made, because
     * the device was offline when its turn came. Nothing reschedules the chain,
     * so a caller waiting on it needs to know it has stopped.
     *
     * Deliberately separate from [consecutiveLoadFailures]: no request went out,
     * so nothing failed. Cleared the moment a load starts, and when the app
     * comes back with a connection.
     */
    private val _recoveryStalled = MutableStateFlow(false)
    val recoveryStalled: StateFlow<Boolean> = _recoveryStalled.asStateFlow()
    private var initialized = false

    private var generation = 0
    private var inFlightGeneration = -1

    private var retryJob: Job? = null
    private var timeoutJob: Job? = null
    private var retryIndex = 0
    private var lastForegroundLoadAtMs = 0L

    /**
     * Automatic attempts that have come and gone without producing an ad since
     * the last success — a failed load, a timeout, or a scheduled retry that
     * could not run.
     *
     * One failure with a retry still queued is not the same thing as an ad the
     * app cannot deliver, so callers wait for this to pass the first retry
     * before acting. It is a flow rather than a plain counter because a load
     * that fails within a single frame collapses LOADING -> FAILED into one
     * emission, leaving [state] unchanged from FAILED and nothing to wake an
     * observer with.
     */
    // Always written before [state] flips to FAILED, so an observer reading the
    // two separately can see a stale count next to a fresh FAILED (harmless, it
    // re-evaluates when the count arrives) but never a fresh count beside a
    // state belonging to a later request.
    private val _consecutiveLoadFailures = MutableStateFlow(0)
    val consecutiveLoadFailures: StateFlow<Int> = _consecutiveLoadFailures.asStateFlow()

    // ── Diagnostics ───────────────────────────────────────────────────
    // This project has no analytics SDK, and adding one is out of scope for a
    // bug fix. Counters live in memory and every transition is logged under a
    // single tag, so `adb logcat -s DF_AD` gives the whole picture on a test
    // device. Nothing user-identifying is recorded.
    object Diag {
        var loadStarted = 0
        var loadSucceeded = 0
        var loadFailed = 0
        var loadTimedOut = 0
        var showFailed = 0
        var rewardsEarned = 0
        var retriesScheduled = 0
        /** Taps on "retry" that arrived while a load was already in flight. */
        var retryTapsWhileLoading = 0
        var userRetryTaps = 0
        var lastLoadMs = 0L
        var lastErrorCode: Int? = null
        var lastErrorDomain: String? = null

        fun snapshot(): String =
            "start=$loadStarted ok=$loadSucceeded fail=$loadFailed timeout=$loadTimedOut " +
                "showFail=$showFailed reward=$rewardsEarned retry=$retriesScheduled " +
                "userRetry=$userRetryTaps retryWhileLoading=$retryTapsWhileLoading " +
                "lastMs=$lastLoadMs lastErr=$lastErrorCode/$lastErrorDomain"
    }

    private fun log(event: String) = Log.i(TAG, "$event | ${Diag.snapshot()}")

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Initializes the Mobile Ads SDK and primes the first ad.
     *
     * initialize() does disk and network work, so it runs off the main thread;
     * the preload waits for its completion callback instead of racing it, with a
     * timed fallback in case the callback never arrives.
     */
    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext

        scope.launch(Dispatchers.IO) {
            try {
                MobileAds.initialize(app) {
                    log("sdk_initialized")
                    scope.launch { ensureLoaded() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sdk_init_failed", e)
            }
        }

        scope.launch {
            delay(5_000L)
            if (_state.value == AdState.IDLE) {
                log("init_fallback_load")
                ensureLoaded()
            }
        }
    }

    /** Called from the Application's activity-lifecycle tracker. */
    fun notifyForeground(foreground: Boolean) {
        if (_isForeground.value == foreground) return
        _isForeground.value = foreground
        if (!foreground) return
        val ctx = appContext ?: return
        if (!AdGraceManager.isOnline(ctx)) return
        // Whatever stalled the chain is over; the throttle below may still hold
        // off the load, and the gate's retry button covers that.
        _recoveryStalled.value = false
        // Coming back to the app is the natural moment to recover from an
        // exhausted retry run, but only at a bounded rate.
        if (validAdOrNull() != null || _state.value == AdState.LOADING) return
        val now = System.currentTimeMillis()
        if (now - lastForegroundLoadAtMs < FOREGROUND_LOAD_MIN_INTERVAL_MS) {
            log("foreground_load_throttled")
            return
        }
        lastForegroundLoadAtMs = now
        ensureLoaded()
    }

    // ── Loading ───────────────────────────────────────────────────────

    /** Starts a load only if there is nothing usable and nothing in flight. */
    fun ensureLoaded() {
        val ctx = appContext ?: run {
            // init() is skipped for PRO owners, so this is reachable only by a
            // caller that should not have asked. Fail rather than stall.
            _state.value = AdState.FAILED
            log("no_context")
            return
        }
        if (validAdOrNull() != null) {
            // Holding an ad settles whatever stalled an earlier attempt.
            _recoveryStalled.value = false
            _state.value = AdState.READY
            return
        }
        if (_state.value == AdState.LOADING) return
        startLoad(ctx)
    }

    /**
     * User asked for an ad right now.
     *
     * The whole point of the fix: while a load is in flight this reuses it
     * instead of firing another request and reporting failure.
     */
    fun retryNow() {
        Diag.userRetryTaps++
        val ctx = appContext ?: run {
            _state.value = AdState.FAILED
            log("no_context")
            return
        }
        if (_state.value == AdState.LOADING) {
            Diag.retryTapsWhileLoading++
            log("user_retry_reused_inflight")
            return
        }
        if (validAdOrNull() != null) {
            _state.value = AdState.READY
            return
        }
        // startLoad cancels any queued retry; the counter resets because an
        // explicit ask deserves a fresh recovery run.
        retryIndex = 0
        log("user_retry_load")
        startLoad(ctx)
    }

    private fun startLoad(ctx: Context) {
        retryJob?.cancel()
        _recoveryStalled.value = false
        generation++
        val gen = generation
        inFlightGeneration = gen
        _state.value = AdState.LOADING
        val startedAt = System.currentTimeMillis()
        // Whether the request went out with a connection at all. Checked again
        // when it comes back, because a failure explained by the device's own
        // connectivity is not the ad system failing to supply one.
        val startedOnline = AdGraceManager.isOnline(ctx)
        Diag.loadStarted++
        log("load_start gen=$gen attempt=$retryIndex online=$startedOnline")

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (inFlightGeneration == gen && _state.value == AdState.LOADING) {
                // Half a minute with no callback is a failure to deliver an ad,
                // so this reports FAILED like any other. The request may still
                // be alive inside the SDK and we cannot cancel it — but bumping
                // the generation means its callback, whenever it lands, is
                // ignored rather than racing whatever ran in the meantime.
                generation++
                inFlightGeneration = -1
                Diag.loadTimedOut++
                recordFailedAttempt(ctx, networkError = false, startedOnline = startedOnline)
                _state.value = AdState.FAILED
                log("load_timeout gen=$gen")
                scheduleRetry()
            }
        }

        RewardedAd.load(
            ctx,
            BuildConfig.REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    // A callback from a superseded attempt touches nothing.
                    // Salvaging it would mean a second path into READY that can
                    // land while a newer load is running or a retry is queued,
                    // and the saved request is not worth that ambiguity.
                    if (gen != inFlightGeneration) {
                        log("stale_load_ignored gen=$gen")
                        return
                    }
                    inFlightGeneration = -1
                    timeoutJob?.cancel()
                    rewardedAd = ad
                    loadedAtMs = System.currentTimeMillis()
                    retryIndex = 0
                    _consecutiveLoadFailures.value = 0
                    Diag.loadSucceeded++
                    Diag.lastLoadMs = loadedAtMs - startedAt
                    _state.value = AdState.READY
                    log("load_ok gen=$gen")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (gen != inFlightGeneration) {
                        log("stale_fail_dropped gen=$gen code=${error.code}")
                        return
                    }
                    inFlightGeneration = -1
                    timeoutJob?.cancel()
                    rewardedAd = null
                    loadedAtMs = 0L
                    Diag.loadFailed++
                    Diag.lastErrorCode = error.code
                    Diag.lastErrorDomain = error.domain
                    Diag.lastLoadMs = System.currentTimeMillis() - startedAt
                    recordFailedAttempt(
                        ctx,
                        networkError = error.code == AdRequest.ERROR_CODE_NETWORK_ERROR,
                        startedOnline = startedOnline,
                    )
                    _state.value = AdState.FAILED
                    log("load_failed gen=$gen code=${error.code} domain=${error.domain}")
                    scheduleRetry()
                }
            },
        )
    }

    /**
     * Files a load attempt that produced no ad.
     *
     * Only a request that went out with a connection, came back with one, and
     * did not come back blaming the network counts toward
     * [consecutiveLoadFailures]: that counter is the evidence the save policy
     * acts on, and it should mean "the ad system had nothing for us", not "this
     * phone was in a tunnel". An attempt the device's own connectivity explains
     * is never added — and nothing is ever subtracted either, so a genuine
     * online failure recorded earlier still stands after a trip offline.
     */
    private fun recordFailedAttempt(ctx: Context, networkError: Boolean, startedOnline: Boolean) {
        val onlineNow = AdGraceManager.isOnline(ctx)
        when {
            startedOnline && onlineNow && !networkError -> {
                _consecutiveLoadFailures.value++
                log("failure_counted total=${_consecutiveLoadFailures.value}")
            }
            !onlineNow -> {
                // Offline: nothing further is coming until a connection does,
                // and the user should be told rather than left waiting. The
                // offline dialog is reached without spending grace.
                _recoveryStalled.value = true
                log("failure_offline_not_counted")
            }
            else -> {
                // The connection returned while the request was out, or the SDK
                // blamed the network while ours looked up. Evidence of nothing;
                // the retry already scheduled will settle it.
                log("failure_unattributable_not_counted")
            }
        }
    }

    /**
     * Limited background recovery. Capped at [RETRY_DELAYS_MS].size attempts and
     * skipped while backgrounded or offline, so a failing device cannot turn into
     * a request generator. Returning to the foreground gives one more attempt via
     * [notifyForeground]; an explicit user retry resets the run.
     */
    private fun scheduleRetry() {
        retryJob?.cancel()
        if (retryIndex >= RETRY_DELAYS_MS.size) {
            log("retry_exhausted")
            return
        }
        val delayMs = RETRY_DELAYS_MS[retryIndex]
        retryIndex++
        Diag.retriesScheduled++
        log("retry_scheduled n=$retryIndex in_ms=$delayMs")
        retryJob = scope.launch {
            delay(delayMs)
            val ctx = appContext ?: return@launch
            if (!_isForeground.value) {
                // No request went out, so nothing failed and nothing is
                // reported. The user is not here to see a result anyway;
                // returning to the app restarts the search.
                log("retry_skipped_background n=$retryIndex")
                return@launch
            }
            if (!AdGraceManager.isOnline(ctx)) {
                // Also not a failed request, but the chain does end here, and
                // the user is watching. Say so without inventing a failure:
                // onAdFailed() recognises the offline case and shows the
                // offline dialog rather than spending grace.
                _recoveryStalled.value = true
                log("retry_skipped_offline n=$retryIndex")
                return@launch
            }
            if (rewardedAd != null || _state.value == AdState.LOADING) return@launch
            startLoad(ctx)
        }
    }

    /** Returns the cached ad, discarding and clearing it once past [AD_TTL_MS]. */
    private fun validAdOrNull(): RewardedAd? {
        val ad = rewardedAd ?: return null
        val ageMs = System.currentTimeMillis() - loadedAtMs
        if (ageMs >= AD_TTL_MS) {
            rewardedAd = null
            loadedAtMs = 0L
            if (_state.value == AdState.READY) _state.value = AdState.IDLE
            log("ad_expired age_ms=$ageMs")
            return null
        }
        return ad
    }

    /** True when an unexpired ad is in hand. Never triggers a load. */
    fun hasValidAd(): Boolean = validAdOrNull() != null

    // ── Showing ───────────────────────────────────────────────────────

    /**
     * Shows the cached ad if one is ready, and reports whether it did.
     *
     * Never loads-then-plays: the caller decides when to show, so an ad that
     * finishes loading while the user is reading the dialog does not start
     * playing on its own.
     *
     * @return false when no usable ad is in hand; a load is kicked off instead.
     */
    fun showIfReady(
        activity: Activity,
        onRewarded: () -> Unit,
        onFailedToShow: () -> Unit,
    ): Boolean {
        val ad = validAdOrNull()
        if (ad == null) {
            ensureLoaded()
            return false
        }

        // Consume the reference the moment we commit to showing it. A
        // RewardedAd may be shown once, and clearing it from a callback would
        // make that invariant depend on which callback arrives first.
        rewardedAd = null
        loadedAtMs = 0L
        _state.value = AdState.IDLE

        // Reward and show-failure are the two ways a presentation can end, and
        // the SDK is not obliged to pick only one. This flag makes the first one
        // to arrive the only one the app acts on, so a reward can never be paired
        // with a failure for the same ad.
        var outcomeDelivered = false
        var rewardDelivered = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                log("ad_shown")
            }

            // Reward and dismiss arrive in either order; neither depends on the
            // other. Reward is delivered once (rewardDelivered), and reloading
            // is idempotent, so both orderings end in the same place.
            override fun onAdDismissedFullScreenContent() {
                log("ad_dismissed rewarded=$rewardDelivered")
                ensureLoaded()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Diag.showFailed++
                Diag.lastErrorCode = error.code
                Diag.lastErrorDomain = error.domain
                log("show_failed code=${error.code} domain=${error.domain}")
                if (outcomeDelivered) {
                    log("show_failed_after_outcome_ignored")
                } else {
                    outcomeDelivered = true
                    onFailedToShow()
                }
                ensureLoaded()
            }
        }

        ad.show(activity) { _ ->
            if (outcomeDelivered) {
                log("reward_after_outcome_ignored")
                return@show
            }
            outcomeDelivered = true
            rewardDelivered = true
            Diag.rewardsEarned++
            log("reward_earned")
            onRewarded()
        }
        return true
    }
}
