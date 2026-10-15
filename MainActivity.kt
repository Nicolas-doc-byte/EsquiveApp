package com.nico.esquive

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayConfiguration
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener
import com.unity3d.mediation.rewarded.LevelPlayReward
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EsquiveAds"

        // 1) App Key : à copier depuis ton tableau de bord LevelPlay (page de ton app)
        private const val APP_KEY = "COLLE_TON_APP_KEY_ICI"

        // 2) Ad Unit IDs : vérifie dans LevelPlay lequel est l'interstitielle et lequel est la récompensée
        private const val INTERSTITIAL_ID = "d3brim2cgyuukakf"
        private const val REWARDED_ID = "9xvddnjm5saaqx8n"

        private const val RETRY_MS = 15_000L
    }

    private lateinit var webView: WebView
    private val main = Handler(Looper.getMainLooper())

    private var interstitial: LevelPlayInterstitialAd? = null
    private var rewarded: LevelPlayRewardedAd? = null

    @Volatile private var interstitialReady = false
    @Volatile private var rewardedReady = false
    private var rewardGranted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Plein écran immersif
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true      // pour sauvegarder le record
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            addJavascriptInterface(AdsBridge(), "AndroidAds")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        initLevelPlay()
    }

    // ---------- LevelPlay ----------

    private fun initLevelPlay() {
        val request = LevelPlayInitRequest.Builder(APP_KEY).build()
        LevelPlay.init(this, request, object : LevelPlayInitListener {
            override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                Log.d(TAG, "LevelPlay initialisé")
                main.post {
                    createInterstitial()
                    createRewarded()
                }
            }

            override fun onInitFailed(error: LevelPlayInitError) {
                Log.e(TAG, "Init LevelPlay échouée : $error")
            }
        })
    }

    private fun createInterstitial() {
        val ad = LevelPlayInterstitialAd(INTERSTITIAL_ID)
        ad.setListener(object : LevelPlayInterstitialAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) { interstitialReady = true }
            override fun onAdLoadFailed(error: LevelPlayAdError) {
                interstitialReady = false
                Log.e(TAG, "Interstitielle : chargement échoué : $error")
                main.postDelayed({ interstitial?.loadAd() }, RETRY_MS)
            }
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
                interstitialReady = false
                callJs("onInterstitialClosed()")
                interstitial?.loadAd()
            }
            override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
            override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                interstitialReady = false
                callJs("onInterstitialClosed()")
                interstitial?.loadAd()   // précharge la suivante
            }
            override fun onAdInfoChanged(adInfo: LevelPlayAdInfo) {}
        })
        interstitial = ad
        ad.loadAd()
    }

    private fun createRewarded() {
        val ad = LevelPlayRewardedAd(REWARDED_ID)
        ad.setListener(object : LevelPlayRewardedAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) { rewardedReady = true }
            override fun onAdLoadFailed(error: LevelPlayAdError) {
                rewardedReady = false
                Log.e(TAG, "Récompensée : chargement échoué : $error")
                main.postDelayed({ rewarded?.loadAd() }, RETRY_MS)
            }
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) {
                rewardedReady = false
                callJs("onRewardedResult(false)")
                rewarded?.loadAd()
            }
            override fun onAdClicked(adInfo: LevelPlayAdInfo) {}
            override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) {
                rewardGranted = true
            }
            override fun onAdClosed(adInfo: LevelPlayAdInfo) {
                rewardedReady = false
                callJs("onRewardedResult($rewardGranted)")
                rewardGranted = false
                rewarded?.loadAd()
            }
            override fun onAdInfoChanged(adInfo: LevelPlayAdInfo) {}
        })
        rewarded = ad
        ad.loadAd()
    }

    // ---------- Pont JavaScript ----------

    private fun callJs(call: String) {
        main.post { webView.evaluateJavascript("window.$call", null) }
    }

    /** Exposé au jeu HTML sous le nom : window.AndroidAds */
    inner class AdsBridge {

        @JavascriptInterface
        fun isRewardedReady(): Boolean = rewardedReady

        @JavascriptInterface
        fun showRewarded() {
            main.post {
                val ad = rewarded
                if (ad != null && ad.isAdReady()) {
                    rewardGranted = false
                    ad.showAd(this@MainActivity)
                } else {
                    callJs("onRewardedResult(false)")
                }
            }
        }

        @JavascriptInterface
        fun showInterstitial() {
            main.post {
                val ad = interstitial
                if (ad != null && ad.isAdReady()) {
                    ad.showAd(this@MainActivity)
                } else {
                    // Pas de pub prête : on laisse le joueur rejouer tout de suite
                    callJs("onInterstitialClosed()")
                }
            }
        }
    }

    // ---------- Cycle de vie ----------

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        interstitial?.destroy()
        rewarded?.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
