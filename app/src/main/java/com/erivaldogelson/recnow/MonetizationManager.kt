package com.erivaldogelson.recnow

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

class MonetizationManager(context: Context) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var productDetails: ProductDetails? = null
    private var entitlementListener: ((Boolean) -> Unit)? = null

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    val adsRemoved: Boolean
        get() = prefs.getBoolean(KEY_ADS_REMOVED, false)

    fun start(onEntitlementChanged: (Boolean) -> Unit) {
        entitlementListener = onEntitlementChanged
        entitlementListener?.invoke(adsRemoved)
        if (runCatching { billingClient.isReady }.getOrDefault(false)) {
            refreshProductsAndPurchases()
            return
        }
        runCatching {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        refreshProductsAndPurchases()
                    }
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    fun end() {
        entitlementListener = null
        runCatching {
            if (billingClient.isReady) billingClient.endConnection()
        }
    }

    fun buyRemoveAds(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        return runCatching {
            billingClient.launchBillingFlow(activity, flowParams).responseCode == BillingClient.BillingResponseCode.OK
        }.getOrDefault(false)
    }

    fun restorePurchases() {
        if (runCatching { billingClient.isReady }.getOrDefault(false)) queryOwnedPurchases()
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        }
    }

    private fun refreshProductsAndPurchases() {
        queryRemoveAdsProduct()
        queryOwnedPurchases()
    }

    private fun queryRemoveAdsProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(REMOVE_ADS_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        runCatching {
            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetails = productDetailsResult.productDetailsList.firstOrNull()
                }
            }
        }
    }

    private fun queryOwnedPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        runCatching {
            billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    handlePurchases(purchases)
                }
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val ownsRemoveAds = purchases.any { purchase ->
            purchase.products.contains(REMOVE_ADS_PRODUCT_ID) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (ownsRemoveAds) {
            setAdsRemoved(true)
            purchases
                .filter { it.products.contains(REMOVE_ADS_PRODUCT_ID) && !it.isAcknowledged }
                .forEach { acknowledgePurchase(it) }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        runCatching {
            billingClient.acknowledgePurchase(params) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    setAdsRemoved(true)
                }
            }
        }
    }

    private fun setAdsRemoved(value: Boolean) {
        prefs.edit().putBoolean(KEY_ADS_REMOVED, value).apply()
        entitlementListener?.invoke(value)
    }

    private companion object {
        const val PREFS_NAME = "monetization"
        const val KEY_ADS_REMOVED = "ads_removed"
        const val REMOVE_ADS_PRODUCT_ID = "remove_ads"
    }
}
