package com.example.funfy.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads and shows AdMob **App Open** ads when the user opens or returns to the app.
 *
 * Defensive about activity lifecycle: never show on a finishing/destroyed activity
 * (AdMob/WebView/Glide otherwise can crash the process on some devices).
 */
class AppOpenAdManager(
    private val application: Application,
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var loadTime: Long = 0L
    private val currentActivity = AtomicReference<Activity?>(null)
    private val sdkReady = AtomicBoolean(false)
    private var coldStartPending = true
    /** Prevent infinite retry loops between primary and fallback unit. */
    private var usedFallbackUnit = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (!AdMobConfig.ENABLED) return
        try {
            application.registerActivityLifecycleCallbacks(this)
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            MobileAds.initialize(application) {
                sdkReady.set(true)
                Log.i(TAG, "MobileAds ready — loading App Open ad")
                mainHandler.post { loadAd(preferFallback = false) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AppOpen start failed", t)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!AdMobConfig.ENABLED) return
        // Delay so activity is fully resumed and Compose has applied first frame.
        mainHandler.postDelayed({
            tryShowOnCurrentActivity()
        }, 600L)
    }

    fun loadAd(preferFallback: Boolean = false) {
        if (!AdMobConfig.ENABLED) return
        if (isLoadingAd || isAdAvailable()) return
        if (!sdkReady.get()) {
            Log.d(TAG, "SDK not ready yet; skip load")
            return
        }
        isLoadingAd = true
        val unitId = if (preferFallback || usedFallbackUnit) {
            AdMobConfig.TEST_APP_OPEN_AD_UNIT_ID
        } else {
            AdMobConfig.activeAppOpenAdUnitId
        }
        Log.i(TAG, "Loading App Open ad unit=$unitId fallback=$preferFallback")
        try {
            AppOpenAd.load(
                application,
                unitId,
                AdRequest.Builder().build(),
                object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        Log.i(TAG, "App Open ad loaded unit=$unitId")
                        appOpenAd = ad
                        isLoadingAd = false
                        loadTime = Date().time
                        if (coldStartPending) {
                            // Wait for a stable resumed activity — avoids
                            // "cannot start a load for a destroyed activity".
                            mainHandler.postDelayed({ tryShowOnCurrentActivity() }, 800L)
                        }
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        Log.w(
                            TAG,
                            "App Open failed code=${loadAdError.code} msg=${loadAdError.message} unit=$unitId",
                        )
                        isLoadingAd = false
                        appOpenAd = null
                        // Code 3 = NO_FILL. Retry once with Google's always-on test App Open unit.
                        if (
                            !usedFallbackUnit &&
                            unitId != AdMobConfig.TEST_APP_OPEN_AD_UNIT_ID &&
                            (loadAdError.code == 3 || loadAdError.code == 0 || loadAdError.code == 1)
                        ) {
                            usedFallbackUnit = true
                            Log.i(TAG, "Primary unit no fill — retrying Google test App Open unit")
                            mainHandler.post { loadAd(preferFallback = true) }
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            isLoadingAd = false
            Log.e(TAG, "AppOpenAd.load threw", t)
        }
    }

    private fun tryShowOnCurrentActivity() {
        val activity = currentActivity.get() ?: return
        showAdIfAvailable(activity)
    }

    fun showAdIfAvailable(activity: Activity) {
        if (!AdMobConfig.ENABLED) return
        if (!isActivitySafe(activity)) {
            Log.d(TAG, "Skip show — activity not safe")
            return
        }
        if (isShowingAd) {
            Log.d(TAG, "Already showing")
            return
        }
        if (!isAdAvailable()) {
            Log.d(TAG, "No ad ready — load")
            loadAd(preferFallback = usedFallbackUnit)
            return
        }
        val ad = appOpenAd ?: return
        Log.i(TAG, "Showing App Open ad")
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "App Open dismissed")
                appOpenAd = null
                isShowingAd = false
                coldStartPending = false
                usedFallbackUnit = false
                loadAd(preferFallback = false)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.w(TAG, "App Open show failed: ${adError.message}")
                appOpenAd = null
                isShowingAd = false
                coldStartPending = false
                loadAd(preferFallback = usedFallbackUnit)
            }

            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "App Open showed")
                isShowingAd = true
                coldStartPending = false
            }
        }
        try {
            // Mark showing before show() so a concurrent onStart does not double-fire.
            isShowingAd = true
            ad.show(activity)
        } catch (t: Throwable) {
            // Glide/WebView inside AdMob can throw if the activity is mid-destroy.
            Log.e(TAG, "App Open show threw", t)
            isShowingAd = false
            appOpenAd = null
            coldStartPending = false
        }
    }

    private fun isActivitySafe(activity: Activity): Boolean {
        return try {
            !activity.isFinishing && !activity.isDestroyed
        } catch (_: Throwable) {
            false
        }
    }

    private fun isAdAvailable(): Boolean {
        appOpenAd ?: return false
        val age = Date().time - loadTime
        if (age >= AdMobConfig.AD_TIMEOUT_MS) {
            Log.d(TAG, "Ad expired ageMs=$age")
            appOpenAd = null
            return false
        }
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        if (!isShowingAd) currentActivity.set(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isShowingAd) {
            currentActivity.set(activity)
            if (coldStartPending && isAdAvailable()) {
                mainHandler.postDelayed({ showAdIfAvailable(activity) }, 700L)
            }
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        // Drop reference so delayed show cannot target a background activity.
        currentActivity.compareAndSet(activity, null)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        currentActivity.compareAndSet(activity, null)
        // Cancel delayed runnables targeting this activity by clearing show path.
        if (isShowingAd) {
            // Ad may still be open; do not force-null isShowingAd (callback will).
        }
    }

    companion object {
        private const val TAG = "AppOpenAd"
    }
}
