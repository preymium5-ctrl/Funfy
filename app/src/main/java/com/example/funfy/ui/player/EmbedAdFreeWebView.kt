package com.example.funfy.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * WebView tuned for Indo18 / jomblo / similar embed players:
 * blocks common ad hosts, kills overlay popunders, and maximizes the video iframe.
 */
@SuppressLint("SetJavaScriptEnabled")
class EmbedAdFreeWebView(context: Context) : WebView(context) {

  /** Fired when a playable media URL is seen in network traffic (enables Indo18 download/play). */
  var onMediaUrlDetected: ((String) -> Unit)? = null

  init {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false

    // Block window.open popups
    webChromeClient = object : WebChromeClient() {
      override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
      ): Boolean = false
    }

    webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString().orEmpty()
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (isMediaUrl(lower)) {
          onMediaUrlDetected?.invoke(url)
        }
        // Stay on known player hosts; block ad / redirect junk
        if (isAdUrl(lower) || isBlockedNavigation(lower)) {
          return true
        }
        return false
      }

      override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
      ): WebResourceResponse? {
        val url = request?.url?.toString().orEmpty()
        val lower = url.lowercase()
        if (isMediaUrl(lower) && !isAdUrl(lower)) {
          // Capture for ExoPlayer / download, but still let WebView load it
          view?.post { onMediaUrlDetected?.invoke(url) }
        }
        if (isAdUrl(lower)) {
          return emptyResponse()
        }
        return super.shouldInterceptRequest(view, request)
      }

      override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        injectAdKillCss(view)
      }

      override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        injectAdKillCss(view)
        injectAdKillJs(view)
        // Re-run after delayed ad scripts
        view?.postDelayed({ injectAdKillJs(view) }, 800)
        view?.postDelayed({ injectAdKillJs(view) }, 2000)
      }
    }
  }

  private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse(
      "text/plain",
      "utf-8",
      ByteArrayInputStream(ByteArray(0)),
    )

  private fun injectAdKillCss(view: WebView?) {
    view ?: return
    val css = """
      (function(){
        if (document.getElementById('funfy-adkill')) return;
        var s = document.createElement('style');
        s.id = 'funfy-adkill';
        s.textContent = `
          a[target="_blank"],
          a[href*="ouo.io"], a[href*="ouo.press"],
          a[href*="propeller"], a[href*="popunder"],
          a[href*="ads."], a[href*="doubleclick"],
          a[href*="pornxxi"], a[href*="click"],
          iframe[src*="ad"], iframe[src*="banner"],
          iframe[src*="pop"], iframe[id*="ad"],
          div[id*="ad-"], div[class*="ads"],
          div[class*="banner"], .adsbygoogle {
            display: none !important;
            pointer-events: none !important;
            width: 0 !important; height: 0 !important;
            opacity: 0 !important;
          }
          body > a {
            display: none !important;
            pointer-events: none !important;
          }
          iframe {
            position: fixed !important;
            inset: 0 !important;
            width: 100% !important;
            height: 100% !important;
            border: 0 !important;
            z-index: 1 !important;
          }
          body, html {
            margin: 0 !important;
            padding: 0 !important;
            overflow: hidden !important;
            background: #000 !important;
          }
        `;
        (document.head || document.documentElement).appendChild(s);
      })();
    """.trimIndent()
    view.evaluateJavascript(css, null)
  }

  private fun injectAdKillJs(view: WebView?) {
    view ?: return
    val js = """
      (function(){
        try {
          // Remove full-page click-catchers and popunder anchors
          document.querySelectorAll('body > a, a[style*="position:fixed"], a[style*="position: fixed"]').forEach(function(e){
            e.remove();
          });
          // Kill common overlay divs covering the player
          document.querySelectorAll('div').forEach(function(d){
            var st = window.getComputedStyle(d);
            if ((st.position === 'fixed' || st.position === 'absolute') &&
                parseInt(st.zIndex || '0', 10) >= 999 &&
                d.querySelector('iframe') === null &&
                d.tagName !== 'IFRAME') {
              var r = d.getBoundingClientRect();
              if (r.width > window.innerWidth * 0.8 && r.height > window.innerHeight * 0.4) {
                // likely ad overlay
                if (!d.querySelector('video,iframe')) d.remove();
              }
            }
          });
          // Histats / tracking scripts
          document.querySelectorAll('script[src*="histats"], script[src*="popunder"], script[src*="ad"]').forEach(function(s){
            s.remove();
          });
          // Maximize first real player iframe
          var ifr = document.querySelector('iframe[src*="play"], iframe[src*="embed"], iframe[src*="file"], iframe');
          if (ifr) {
            ifr.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;width:100%;height:100%;border:0;z-index:1;';
          }
          // Disable window.open
          window.open = function(){ return null; };
        } catch(e) {}
      })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
  }

  companion object {
    fun isMediaUrl(url: String): Boolean {
      val u = url.lowercase()
      return (u.contains(".mp4") || u.contains(".m3u8") || u.contains("/video/") && u.contains("cdn")) &&
        !u.contains(".jpg") && !u.contains(".png") && !u.contains(".gif") && !u.contains(".vtt")
    }

    private val AD_HOST_SNIPPETS = listOf(
      "doubleclick", "googlesyndication", "googleadservices", "adservice",
      "adnxs", "adsystem", "adcolony", "popads", "popcash", "propeller",
      "exoclick", "exosrv", "juicyads", "trafficjunky", "tsyndicate",
      "ouo.io", "ouo.press", "histats", "clickadu", "adsterra",
      "popunder", "banner", "adskeeper", "mgid", "taboola",
      "pornxxi", "stripchat", "livejasmin", "chaturbate",
      "onclicka", "ad-maven", "hilltopads",
    )

    fun isAdUrl(url: String): Boolean {
      val u = url.lowercase()
      return AD_HOST_SNIPPETS.any { u.contains(it) }
    }

    fun isBlockedNavigation(url: String): Boolean {
      val u = url.lowercase()
      // Allow player hosts
      if (u.contains("jomblo.org") ||
        u.contains("playmogo") ||
        u.contains("luluvid") ||
        u.contains("dood") ||
        u.contains("streamtape") ||
        u.contains("filemoon") ||
        u.contains("indo18") ||
        u.contains("pinayot") ||
        u.contains("about:blank")
      ) {
        return false
      }
      // Block leave-to-ad-site navigations
      return isAdUrl(u) || u.contains("ouo.") || u.startsWith("intent:")
    }
  }
}
