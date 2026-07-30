package com.example.funfy.ads

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

private const val TAG = "NativeAdCard"

/**
 * AdMob Native Advanced Ad composable card.
 *
 * Renders high-converting native ad layouts with headline, media view (video/image),
 * body description, advertiser details, and call-to-action button matching app styling.
 */
@Composable
fun NativeAdCard(
    modifier: Modifier = Modifier,
    unitId: String = AdMobConfig.activeNativeAdUnitId,
) {
    if (!AdMobConfig.ENABLED) return

    val context = LocalContext.current
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(unitId) {
        loadNativeAd(
            context = context,
            unitId = unitId,
            onAdLoaded = { ad ->
                nativeAd = ad
                loadFailed = false
            },
            onAdFailed = {
                // If primary unit fails, retry with Google sample test unit
                if (unitId != AdMobConfig.TEST_NATIVE_AD_UNIT_ID) {
                    Log.i(TAG, "Primary Native Ad unit failed; retrying test unit")
                    loadNativeAd(
                        context = context,
                        unitId = AdMobConfig.TEST_NATIVE_AD_UNIT_ID,
                        onAdLoaded = { fallbackAd ->
                            nativeAd = fallbackAd
                            loadFailed = false
                        },
                        onAdFailed = {
                            loadFailed = true
                        },
                    )
                } else {
                    loadFailed = true
                }
            },
        )
    }

    DisposableEffect(nativeAd) {
        onDispose {
            nativeAd?.destroy()
        }
    }

    if (loadFailed || (nativeAd == null && !AdMobConfig.ENABLED)) {
        return
    }

    val currentAd = nativeAd

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF161922),
        border = BorderStroke(1.dp, Color(0xFF262C3A)),
    ) {
        if (currentAd != null) {
            AndroidView(
                factory = { ctx ->
                    createNativeAdView(ctx, currentAd)
                },
                update = { view ->
                    populateNativeAdView(currentAd, view)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Loading placeholder skeleton height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(Color(0xFF1C202C)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF2979FF),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private fun loadNativeAd(
    context: Context,
    unitId: String,
    onAdLoaded: (NativeAd) -> Unit,
    onAdFailed: () -> Unit,
) {
    try {
        val adLoader = AdLoader.Builder(context, unitId)
            .forNativeAd { ad ->
                Log.i(TAG, "Native ad loaded successfully unit=$unitId")
                onAdLoaded(ad)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Native ad failed code=${error.code} msg=${error.message} unit=$unitId")
                    onAdFailed()
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setRequestCustomMuteThisAd(true)
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build(),
            )
            .build()
        adLoader.loadAd(AdRequest.Builder().build())
    } catch (t: Throwable) {
        Log.e(TAG, "AdLoader setup error", t)
        onAdFailed()
    }
}

private fun createNativeAdView(context: Context, nativeAd: NativeAd): NativeAdView {
    val nativeAdView = NativeAdView(context)
    nativeAdView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    // Top Header: Icon + Headline + Sponsor/Advertiser + Ad Badge
    val headerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(context, 8)
        }
    }

    val iconView = ImageView(context).apply {
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(
            dpToPx(context, 40),
            dpToPx(context, 40),
        ).apply {
            rightMargin = dpToPx(context, 10)
        }
    }
    nativeAdView.iconView = iconView
    headerLayout.addView(iconView)

    val titleColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
    }

    val headlineView = TextView(context).apply {
        id = View.generateViewId()
        setTextColor(AndroidColor.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    nativeAdView.headlineView = headlineView
    titleColumn.addView(headlineView)

    val advertiserView = TextView(context).apply {
        id = View.generateViewId()
        setTextColor(AndroidColor.parseColor("#9E9E9E"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    nativeAdView.advertiserView = advertiserView
    titleColumn.addView(advertiserView)

    headerLayout.addView(titleColumn)

    // "Ad" attribution badge view
    val adBadge = TextView(context).apply {
        text = "Ad"
        setTextColor(AndroidColor.parseColor("#FFD700"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dpToPx(context, 6), dpToPx(context, 2), dpToPx(context, 6), dpToPx(context, 2))
        background = GradientDrawable().apply {
            setColor(AndroidColor.parseColor("#33FFD700"))
            cornerRadius = dpToPx(context, 4).toFloat()
            setStroke(dpToPx(context, 1), AndroidColor.parseColor("#80FFD700"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dpToPx(context, 6)
        }
    }
    headerLayout.addView(adBadge)

    root.addView(headerLayout)

    // Media Content View
    val mediaView = MediaView(context).apply {
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(context, 180),
        ).apply {
            bottomMargin = dpToPx(context, 8)
        }
    }
    nativeAdView.mediaView = mediaView
    root.addView(mediaView)

    // Body Text
    val bodyView = TextView(context).apply {
        id = View.generateViewId()
        setTextColor(AndroidColor.parseColor("#CCCCCC"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(context, 10)
        }
    }
    nativeAdView.bodyView = bodyView
    root.addView(bodyView)

    // Call to Action (CTA) Button
    val ctaButton = Button(context).apply {
        id = View.generateViewId()
        setTextColor(AndroidColor.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            setColor(AndroidColor.parseColor("#2979FF"))
            cornerRadius = dpToPx(context, 8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(context, 42),
        )
    }
    nativeAdView.callToActionView = ctaButton
    root.addView(ctaButton)

    nativeAdView.addView(root)
    populateNativeAdView(nativeAd, nativeAdView)

    return nativeAdView
}

private fun populateNativeAdView(nativeAd: NativeAd, nativeAdView: NativeAdView) {
    (nativeAdView.headlineView as? TextView)?.text = nativeAd.headline
    (nativeAdView.bodyView as? TextView)?.apply {
        text = nativeAd.body
        visibility = if (nativeAd.body.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    (nativeAdView.callToActionView as? Button)?.apply {
        text = nativeAd.callToAction ?: "Learn More"
        visibility = if (nativeAd.callToAction.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    (nativeAdView.iconView as? ImageView)?.apply {
        val icon = nativeAd.icon
        if (icon != null) {
            setImageDrawable(icon.drawable)
            visibility = View.VISIBLE
        } else {
            visibility = View.GONE
        }
    }

    (nativeAdView.advertiserView as? TextView)?.apply {
        val adv = nativeAd.advertiser ?: nativeAd.store
        text = adv
        visibility = if (adv.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    nativeAdView.mediaView?.let { media ->
        nativeAdView.setNativeAd(nativeAd)
    }
}

private fun dpToPx(context: Context, dp: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
