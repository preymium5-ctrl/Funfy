package com.example.funfy.ads

import com.example.funfy.BuildConfig

/**
 * Google AdMob unit IDs for Funfy.
 *
 * App ID is also declared in AndroidManifest as
 * com.google.android.gms.ads.APPLICATION_ID.
 *
 * Your unit is an **App Open** ad (shows when the app is opened), not a banner.
 */
object AdMobConfig {
    /** Master switch — set false to disable all ads. */
    const val ENABLED = true

    /** AdMob application ID (also in AndroidManifest). */
    const val APP_ID = "ca-app-pub-5210332589190598~6084028398"

    /** Your production App Open unit from AdMob console. */
    const val APP_OPEN_AD_UNIT_ID = "ca-app-pub-5210332589190598/5661356560"

    /**
     * Google sample App Open unit — always fills during development.
     * @see https://developers.google.com/admob/android/test-ads
     */
    const val TEST_APP_OPEN_AD_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"

    /**
     * Debug builds use Google's test App Open unit so you can verify the flow.
     * Release builds use your real unit.
     */
    val activeAppOpenAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_APP_OPEN_AD_UNIT_ID else APP_OPEN_AD_UNIT_ID

    /** Production Native Advanced unit ID. */
    const val NATIVE_AD_UNIT_ID = "ca-app-pub-5210332589190598/6450506621"

    /** Google sample Native Advanced unit ID for test ads. */
    const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

    /** Debug builds use Google test Native unit; release builds use production unit. */
    val activeNativeAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_NATIVE_AD_UNIT_ID else NATIVE_AD_UNIT_ID

    /**
     * Devices that should receive test ads even with a production unit.
     * Logged by AdMob on this device earlier.
     */
    val TEST_DEVICE_IDS = listOf(
        "26768CA6B7F5A16B17AA6E7701DB439E",
    )

    /** App Open ads expire after ~4 hours per Google guidance. */
    const val AD_TIMEOUT_MS = 4L * 60L * 60L * 1000L
}
