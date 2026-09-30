// BillingManager.kt
package com.mende.dontspoilerfyme.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*

class BillingManager(
    context: Context,
    private val productId: String = "premium_unlock",
    private val onPremium: (Boolean) -> Unit,
    private val onPrice: (String?) -> Unit,
    private val onError: (String?) -> Unit
) : PurchasesUpdatedListener {

    // Gli acquisti in sospeso restano abilitati per il prodotto Premium una tantum.
    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts() // INAPP (il tuo premium_unlock)
                .build()
        )
        .build()

    private var productDetails: ProductDetails? = null
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    onError("Billing setup failed: ${result.debugMessage}")
                    started = false
                    return
                }
                queryProduct()
                restore(silent = true) // ✅ non fa downgrade a false
            }

            override fun onBillingServiceDisconnected() {
                // Permetti retry su successivo start()
                started = false
            }
        })
    }

    fun end() {
        try { billingClient.endConnection() } catch (_: Throwable) {}
        started = false
    }

    private fun queryProduct() {
        if (!billingClient.isReady) return

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                productDetails = null
                onError("Product query failed: ${result.debugMessage}")
                onPrice(null)
                return@queryProductDetailsAsync
            }

            // Da Billing 8 il callback restituisce QueryProductDetailsResult, non una lista.
            val details = queryResult.productDetailsList.firstOrNull { it.productId == productId }
            val offer = details?.oneTimePurchaseOfferDetails
            if (details == null || offer == null) {
                productDetails = null
                onPrice(null)
                val unfetched = queryResult.unfetchedProductList.firstOrNull { it.productId == productId }
                if (unfetched != null) {
                    Log.w("BillingManager", "Product $productId unavailable: ${unfetched.statusCode}")
                }
                onError("Premium non disponibile per questo account. Riprova più tardi.")
                return@queryProductDetailsAsync
            }

            productDetails = details
            onPrice(offer.formattedPrice)
            onError(null)
        }
    }

    fun launchPurchase(activity: Activity) {
        if (!billingClient.isReady) {
            onError("Billing not ready. Retry in a moment.")
            start()
            return
        }

        val details = productDetails
        val offer = details?.oneTimePurchaseOfferDetails
        if (details == null || offer == null) {
            onError("Premium non disponibile (prodotto non trovato). Ricontrolla Play Console / testing.")
            queryProduct()
            return
        }

        val pdp = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply {
                // Usa la stessa offerta del prezzo mostrato; i prodotti legacy possono non avere token.
                offer.offerToken?.takeIf { it.isNotBlank() }?.let { setOfferToken(it) }
            }
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(pdp))
            .build()

        val res = billingClient.launchBillingFlow(activity, flowParams)
        if (res.responseCode != BillingClient.BillingResponseCode.OK) {
            onError("Launch flow error: ${res.debugMessage}")
        }
    }

    fun restoreManual() {
        restore(silent = false)
    }

    private fun restore(silent: Boolean) {
        if (!billingClient.isReady) {
            if (!silent) onError("Billing not ready. Retry in a moment.")
            start()
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                if (!silent) onError("Restore failed: ${result.debugMessage}")
                return@queryPurchasesAsync
            }
            handlePurchases(purchases, silent = silent)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases, silent = true)
            return
        }
        if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            onError("Billing error: ${result.debugMessage}")
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, silent: Boolean) {
        val match = purchases.firstOrNull { p ->
            p.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    p.products.contains(productId)
        }

        if (match == null) {
            // ✅ IMPORTANT: in restore silenzioso NON facciamo downgrade a false (evita flicker/ads)
            if (!silent) {
                onPremium(false)
                onError("Nessun acquisto Premium trovato su questo account.")
            }
            return
        }

        // ✅ Premium istantaneo: appena vedo PURCHASED, abilito subito
        onPremium(true)
        onError(null)

        // ✅ Acknowledge in background (compliance). Se fallisce, NON togliamo premium.
        if (!match.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(match.purchaseToken)
                .build()

            billingClient.acknowledgePurchase(ack) { ackRes ->
                if (ackRes.responseCode != BillingClient.BillingResponseCode.OK) {
                    // Non blocchiamo l'utente: log/errore solo se restore manuale
                    if (!silent) onError("Acknowledge failed: ${ackRes.debugMessage}")
                }
            }
        }
    }
}
