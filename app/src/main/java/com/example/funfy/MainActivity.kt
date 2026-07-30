package com.example.funfy

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.funfy.theme.FunfyTheme
import com.example.funfy.ui.main.PasscodeUnlockDialog
import java.security.MessageDigest
import java.security.SecureRandom

/** Playback behavior shared by Settings and the player. */
enum class LoopMode(val preferenceValue: String, val displayName: String) {
  AUTO("auto", "Auto"),
  OFF("off", "Off"),
  ONE("one", "Repeat current video"),
  ;

  companion object {
    fun fromPreference(value: String?): LoopMode =
      entries.firstOrNull { it.preferenceValue == value } ?: AUTO
  }
}

/** Launcher identities exposed by the Discreet icon setting. */
enum class LauncherIdentity(
  val preferenceValue: String,
  @param:StringRes val labelRes: Int,
  @param:DrawableRes val iconRes: Int,
  val componentSuffix: String,
) {
  FUNFY("funfy", R.string.identity_funfy, R.mipmap.ic_launcher, "LauncherFunfy"),
  CALCULATOR(
    "calculator",
    R.string.identity_calculator,
    R.drawable.ic_identity_calculator,
    "LauncherCalculator",
  ),
  NOTES("notes", R.string.identity_notes, R.drawable.ic_identity_notes, "LauncherNotes"),
  WEATHER("weather", R.string.identity_weather, R.drawable.ic_identity_weather, "LauncherWeather"),
  FILES("files", R.string.identity_files, R.drawable.ic_identity_files, "LauncherFiles"),
  ;

  fun componentName(context: Context): ComponentName =
    ComponentName(context.packageName, "${context.packageName}.$componentSuffix")

  companion object {
    fun fromPreference(value: String?): LauncherIdentity =
      entries.firstOrNull { it.preferenceValue == value } ?: FUNFY
  }
}

/**
 * Small, stable settings API used by Compose and the player.
 *
 * Values live in one private preference file so playback code can read them at
 * the moment an item starts instead of holding stale process state.
 */
object AppSettings {
  private const val PREFERENCES = "funfy_app_settings"
  private const val KEY_PASSCODE_SALT = "passcode_salt"
  private const val KEY_PASSCODE_HASH = "passcode_hash"
  private const val KEY_USER_NAME = "user_name"
  private const val KEY_LOOP_MODE = "loop_mode"
  private const val KEY_FULLSCREEN_ON_ROTATION = "fullscreen_on_rotation"
  private const val KEY_DISABLE_PREVIEWS = "disable_previews"
  private const val KEY_FORCE_MP4 = "force_mp4"
  private const val KEY_AUTO_PLAY = "autoplay"
  private const val KEY_AUTO_SHUFFLE = "auto_shuffle"
  private const val KEY_LAUNCHER_IDENTITY = "launcher_identity"
  private const val HASH_ROUNDS = 20_000

  private fun preferences(context: Context) =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun hasPasscode(context: Context): Boolean =
    !preferences(context).getString(KEY_PASSCODE_HASH, null).isNullOrBlank()

  fun setPasscode(context: Context, pin: String): Boolean {
    if (!pin.matches(Regex("\\d{4,8}"))) return false
    val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    val hash = derivePinHash(pin, salt)
    preferences(context).edit()
      .putString(KEY_PASSCODE_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
      .putString(KEY_PASSCODE_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
      .apply()
    return true
  }

  fun verifyPasscode(context: Context, pin: String): Boolean {
    val prefs = preferences(context)
    val encodedSalt = prefs.getString(KEY_PASSCODE_SALT, null) ?: return false
    val encodedHash = prefs.getString(KEY_PASSCODE_HASH, null) ?: return false
    return runCatching {
      val salt = Base64.decode(encodedSalt, Base64.NO_WRAP)
      val expected = Base64.decode(encodedHash, Base64.NO_WRAP)
      MessageDigest.isEqual(expected, derivePinHash(pin, salt))
    }.getOrDefault(false)
  }

  fun clearPasscode(context: Context) {
    preferences(context).edit()
      .remove(KEY_PASSCODE_SALT)
      .remove(KEY_PASSCODE_HASH)
      .apply()
  }

  fun userName(context: Context): String =
    preferences(context).getString(KEY_USER_NAME, "").orEmpty()

  fun setUserName(context: Context, value: String) {
    preferences(context).edit().putString(KEY_USER_NAME, value.trim().take(40)).apply()
  }

  fun loopMode(context: Context): LoopMode =
    LoopMode.fromPreference(preferences(context).getString(KEY_LOOP_MODE, null))

  fun setLoopMode(context: Context, mode: LoopMode) {
    preferences(context).edit().putString(KEY_LOOP_MODE, mode.preferenceValue).apply()
  }

  fun fullscreenOnRotation(context: Context): Boolean =
    preferences(context).getBoolean(KEY_FULLSCREEN_ON_ROTATION, false)

  fun setFullscreenOnRotation(context: Context, enabled: Boolean) {
    preferences(context).edit().putBoolean(KEY_FULLSCREEN_ON_ROTATION, enabled).apply()
  }

  fun disablePreviews(context: Context): Boolean =
    preferences(context).getBoolean(KEY_DISABLE_PREVIEWS, false)

  fun setDisablePreviews(context: Context, disabled: Boolean) {
    preferences(context).edit().putBoolean(KEY_DISABLE_PREVIEWS, disabled).apply()
  }

  fun forceMp4(context: Context): Boolean =
    preferences(context).getBoolean(KEY_FORCE_MP4, false)

  fun setForceMp4(context: Context, enabled: Boolean) {
    preferences(context).edit().putBoolean(KEY_FORCE_MP4, enabled).apply()
  }

  fun autoPlay(context: Context): Boolean =
    preferences(context).getBoolean(KEY_AUTO_PLAY, true)

  fun setAutoPlay(context: Context, enabled: Boolean) {
    preferences(context).edit().putBoolean(KEY_AUTO_PLAY, enabled).apply()
  }

  /** When on, each home page is shuffled so the grid order feels random. */
  fun autoShuffle(context: Context): Boolean =
    preferences(context).getBoolean(KEY_AUTO_SHUFFLE, false)

  fun setAutoShuffle(context: Context, enabled: Boolean) {
    preferences(context).edit().putBoolean(KEY_AUTO_SHUFFLE, enabled).apply()
  }

  fun launcherIdentity(context: Context): LauncherIdentity =
    LauncherIdentity.fromPreference(
      preferences(context).getString(KEY_LAUNCHER_IDENTITY, null),
    )

  internal fun recordLauncherIdentity(context: Context, identity: LauncherIdentity) {
    preferences(context).edit()
      .putString(KEY_LAUNCHER_IDENTITY, identity.preferenceValue)
      .commit()
  }

  private fun derivePinHash(pin: String, salt: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    var value = digest.digest(salt + pin.toByteArray(Charsets.UTF_8))
    repeat(HASH_ROUNDS - 1) {
      digest.reset()
      digest.update(salt)
      value = digest.digest(value)
    }
    return value
  }
}

/** Applies alias changes in an enable-first order so the app always has a launcher entry. */
object LauncherIdentityManager {
  fun select(context: Context, identity: LauncherIdentity): Result<Unit> = runCatching {
    val packageManager = context.packageManager
    packageManager.setComponentEnabledSetting(
      identity.componentName(context),
      PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
      PackageManager.DONT_KILL_APP,
    )
    // Persist after the new entry exists, before retiring the previous entry.
    AppSettings.recordLauncherIdentity(context, identity)
    var firstFailure: Throwable? = null
    LauncherIdentity.entries
      .filterNot { it == identity }
      .forEach { other ->
        runCatching {
          packageManager.setComponentEnabledSetting(
            other.componentName(context),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
          )
        }.onFailure { if (firstFailure == null) firstFailure = it }
      }
    firstFailure?.let { throw it }
    (context as? Activity)?.applyTaskIdentity(identity)
  }

  fun reconcile(context: Context): Result<Unit> =
    select(context, AppSettings.launcherIdentity(context))
}

/** Keeps the recent-apps card as discreet as the launcher entry itself. */
private fun Activity.applyTaskIdentity(identity: LauncherIdentity) {
  val label = getString(identity.labelRes)
  title = label
  val drawable = ContextCompat.getDrawable(this, identity.iconRes) ?: return
  val width = drawable.intrinsicWidth.coerceAtLeast(108)
  val height = drawable.intrinsicHeight.coerceAtLeast(108)
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  drawable.setBounds(0, 0, width, height)
  drawable.draw(Canvas(bitmap))
  @Suppress("DEPRECATION")
  setTaskDescription(ActivityManager.TaskDescription(label, bitmap, Color.rgb(10, 26, 74)))
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Keep private playback/search frames out of screenshots and Recents snapshots.
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
      window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    window.decorView.setBackgroundColor(Color.BLACK)

    val identity = AppSettings.launcherIdentity(this)
    LauncherIdentityManager.reconcile(this)
    applyTaskIdentity(identity)
    enableEdgeToEdge()
    setContent {
      var appUnlocked by remember { mutableStateOf(!AppSettings.hasPasscode(this)) }
      val lifecycleOwner = LocalLifecycleOwner.current
      DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_STOP && AppSettings.hasPasscode(this@MainActivity)) {
            appUnlocked = false
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
      }
      FunfyTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background,
        ) {
          if (appUnlocked) {
            MainNavigation()
          } else {
            // Do not compose private titles or thumbnails behind the lock
            // window; this also keeps them out of task-switcher snapshots.
            Box(modifier = Modifier.fillMaxSize())
            PasscodeUnlockDialog(onUnlocked = { appUnlocked = true })
          }
        }
      }
    }
  }
}
