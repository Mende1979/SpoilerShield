package com.mende.dontspoilerfyme.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

class AppOpenAdManager(
    private val context: Context,
    private val adUnitId: String
) {
    private var appOpenAd: AppOpenAd? = null
    private var isLoading = false
    private var isShowing = false

    fun isAvailable(): Boolean = appOpenAd != null && !isShowing

    fun load(force: Boolean = false) {
        if (isLoading) return
        if (!force && appOpenAd != null) return

        isLoading = true

        val request = AdRequest.Builder().build()

        AppOpenAd.load(
            context,
            adUnitId.trim(),
            request,
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenAd = null
                    isLoading = false
                }
            }
        )
    }

    fun showIfAvailable(
        activity: Activity,
        onShown: () -> Unit,
        onDone: () -> Unit
    ) {
        // ✅ se sta già mostrando, NON bloccare la logica chiamante
        if (isShowing) {
            onDone()
            return
        }

        val ad = appOpenAd
        if (ad == null) {
            load(force = true)
            onDone()
            return
        }

        // ✅ evita show quando activity non è in stato valido
        if (activity.isFinishing || activity.isDestroyed) {
            onDone()
            return
        }

        isShowing = true

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                onShown()
            }

            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowing = false
                load(force = true)
                onDone()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appOpenAd = null
                isShowing = false
                load(force = true)
                onDone()
            }
        }

        try {
            activity.runOnUiThread {
                ad.show(activity)
            }
        } catch (_: Throwable) {
            appOpenAd = null
            isShowing = false
            load(force = true)
            onDone()
        }
    }
}
