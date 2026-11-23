package com.movnet.app

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Development integration uses Google's official test ad unit IDs only.
 * Replace them with the publisher's verified IDs after consent/account setup.
 */
object MovnetAds {
    private const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    private var interstitial: InterstitialAd? = null
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        MobileAds.initialize(context.applicationContext) { loadInterstitial(context.applicationContext) }
    }

    @Synchronized
    private fun loadInterstitial(context: Context) {
        if (interstitial != null) return
        InterstitialAd.load(context, TEST_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }

    fun showPreRoll(activity: Activity, onFinished: () -> Unit) {
        val ad = interstitial
        if (ad == null) {
            loadInterstitial(activity.applicationContext)
            onFinished()
            return
        }
        interstitial = null
        var continued = false
        fun finishOnce() {
            if (!continued) {
                continued = true
                loadInterstitial(activity.applicationContext)
                onFinished()
            }
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finishOnce()
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) = finishOnce()
        }
        ad.show(activity)
    }

    fun createTestBanner(context: Context): AdView = AdView(context).apply {
        adUnitId = "ca-app-pub-3940256099942544/6300978111"
        setAdSize(AdSize.BANNER)
        loadAd(AdRequest.Builder().build())
    }
}
