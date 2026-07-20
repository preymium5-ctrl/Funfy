package com.example.funfy

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.example.funfy.data.BookmarkStore
import com.example.funfy.data.DefaultDataRepository
import com.example.funfy.data.DownloadStore
import com.example.funfy.data.NetworkClient
import com.example.funfy.data.SourcePreferences
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FunfyApp : Application(), ImageLoaderFactory {

  lateinit var repository: DefaultDataRepository
    private set

  lateinit var downloadStore: DownloadStore
    private set

  lateinit var bookmarkStore: BookmarkStore
    private set

  override fun onCreate() {
    super.onCreate()
    repository = DefaultDataRepository(SourcePreferences(this))
    downloadStore = DownloadStore(this)
    bookmarkStore = BookmarkStore(this)
  }

  override fun newImageLoader(): ImageLoader {
    val client = OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
          .header("User-Agent", NetworkClient.USER_AGENT)
        if (original.header("Referer") == null) {
          val host = original.url.host.lowercase()
          // Same-site referer so tube CDNs allow hotlinked thumbs (sexvid, javmiku, etc.).
          val referer = when {
            host.contains("xvideos") || host.contains("xnxx") -> "https://www.xvideos.com/"
            host.contains("phncdn") || host.contains("pornhub") -> "https://www.pornhub.com/"
            host.contains("eporner") -> "https://www.eporner.com/"
            host.contains("buumal") -> "https://www.buumal.com/"
            host.contains("indo18") -> "https://www.indo18.com/"
            host.contains("pinayot") -> "https://pinayot.com/"
            host.contains("pinayflixhd") -> "https://pinayflixhd.com/"
            host.contains("pinayflix") -> "https://pinayflix.uk/"
            host.contains("flixtream") || host.contains("goostream") ||
                host.contains("corecache") -> "https://flixtream.top/"
            host.contains("pinaypornsite") -> "https://www.pinaypornsite.com/"
            host.contains("cloudflarestorage") || host.contains("r2.dev") ->
                "https://www.buumal.com/"
            host.contains("hqporner") || host.contains("bigcdn") || host.contains("mydaddy") ->
              "https://hqporner.com/"
            host.contains("redtube") || host.contains("rdtcdn") || host.contains("pix-cdn") ->
              "https://www.redtube.com/"
            host.contains("xxxtime") || host.contains("siska") -> "https://xxxtime.video/"
            host.contains("sexvid") -> "https://www.sexvid.xxx/"
            host.contains("javmiku") || host.contains("jav.guru") -> "https://jav.guru/"
            host.contains("javfree") -> "https://javfree.me/"
            host.contains("javtsunami") || host.contains("imagerls") -> "https://javtsunami.com/"
            host.contains("missav") || host.contains("fourhoi") || host.contains("surrit") ->
              "https://missav.ws/"
            host.contains("javff") || host.contains("dmm.co.jp") || host.contains("pics.dmm") ->
              "https://javtsunami.com/"
            host.contains("javseen") -> "https://javseenz.tv/"
            host.contains("drkogyi") -> "https://drkogyi.vip/"
            host.contains("mmporns") -> "https://mmporns.com/"
            host.contains("mmhd") -> "https://mmhdhub.com/"
            host.contains("babextube") -> "https://babextube.com/"
            host.contains("thaipornxxx") -> "https://thaipornxxx.com/"
            host.contains("pornthai") -> "https://pornthai.org/"
            host.contains("vlxx") || host.contains("vlimg") -> "https://vlxx.moi/"
            host.contains("sexhay24h") -> "https://sexhay24h.net/"
            host.contains("shennana") || host.contains("goodhub") || host.contains("sn-cdn") ->
              "https://shennana.com/"
            host.contains("hentaimama") || host.contains("gdvid") || host.contains("javprovider") ->
              "https://hentaimama.io/"
            host.contains("hentai4k") -> "https://hentai4k.com/"
            host.contains("pornkai") || host.contains("others-cdn") || host.contains("thumb-cdn") ->
              "https://pornkai.com/"
            host.contains("tnaflix") -> "https://www.tnaflix.com/"
            host.contains("porntrex") || host.contains("cdntrex") -> "https://www.porntrex.com/"
            host.contains("analdin") -> "https://www.analdin.com/"
            else -> "https://$host/"
          }
          builder.header("Referer", referer)
        }
        chain.proceed(builder.build())
      }
      .build()

    return ImageLoader.Builder(this)
      .okHttpClient(client)
      .crossfade(true)
      .memoryCache {
        MemoryCache.Builder(this)
          .maxSizePercent(0.25)
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("image_cache"))
          .maxSizeBytes(100L * 1024 * 1024)
          .build()
      }
      .memoryCachePolicy(CachePolicy.ENABLED)
      .diskCachePolicy(CachePolicy.ENABLED)
      .build()
  }
}
