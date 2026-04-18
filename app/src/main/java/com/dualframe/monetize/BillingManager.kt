package com.dualframe.monetize

import android.app.Activity
import android.content.Context
import android.util.Log
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

/**
 * Google Play Billing — one-time purchase for permanent watermark removal.
 *
 * Product ID: pro_upgrade (configured in Google Play Console)
 * Type: INAPP (non-consumable, one-time purchase)
 *
 * Flow:
 * 1. connect() on app start → queries product + restores existing purchases
 * 2. launchPurchase() when user taps "Go Pro"
 * 3. On purchase success → acknowledge → grant entitlement
 * 4. On app restart → checkExistingPurchases() restores entitlement from Play
 */
class BillingManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BillingManager"

        // Product ID — must match Google Play Console in-app product
        const val PRODUCT_ID = "pro_upgrade"

        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context): BillingManager {
            return instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var onPurchaseComplete: ((Boolean) -> Unit)? = null
    private var isConnected = false

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase canceled")
                onPurchaseComplete?.invoke(false)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // User already owns this — restore entitlement
                Log.i(TAG, "Item already owned — restoring")
                ProEntitlement.grantPro(context)
                onPurchaseComplete?.invoke(true)
            }
            else -> {
                Log.e(TAG, "Purchase error ${result.responseCode}: ${result.debugMessage}")
                onPurchaseComplete?.invoke(false)
            }
        }
    }

    fun connect() {
        if (isConnected) return
        val client = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        billingClient = client

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    Log.i(TAG, "Billing connected")
                    queryProduct()
                    restorePurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.w(TAG, "Billing disconnected")
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient?.queryProductDetailsAsync(params) { _, detailsList ->
            productDetails = detailsList.firstOrNull()
            Log.i(TAG, if (productDetails != null)
                "Product loaded: ${productDetails?.title}"
            else
                "Product not found: $PRODUCT_ID"
            )
        }
    }

    /**
     * Restore purchases from Google Play. Called on app start and on
     * billing reconnect. This is the source of truth for entitlement —
     * even after app reinstall, the Play Store remembers the purchase.
     */
    fun restorePurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { _, purchases ->
            var found = false
            for (purchase in purchases) {
                if (PRODUCT_ID in purchase.products &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                ) {
                    found = true
                    ProEntitlement.grantPro(context)
                    // Acknowledge if not already (handles edge case where
                    // purchase succeeded but acknowledge failed previously)
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }
                    Log.i(TAG, "Restored PRO purchase")
                }
            }
            if (!found) {
                Log.i(TAG, "No existing PRO purchase found")
            }
        }
    }

    fun launchPurchase(activity: Activity, onComplete: (Boolean) -> Unit) {
        // If already PRO, no need to purchase again
        if (ProEntitlement.isProOwned(context)) {
            Log.i(TAG, "Already PRO — skipping purchase")
            onComplete(true)
            return
        }

        val details = productDetails
        if (details == null) {
            Log.e(TAG, "Product details not available — try reconnecting")
            if (!isConnected) connect()
            onComplete(false)
            return
        }

        onPurchaseComplete = onComplete

        // One-time product — no offerToken needed (that's for subscriptions)
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        val result = billingClient?.launchBillingFlow(activity, flowParams)
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result?.debugMessage}")
            onComplete(false)
        }
    }

    /**
     * Handle a completed purchase: acknowledge it (required by Google within
     * 3 days or the purchase is automatically refunded) and grant entitlement.
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (PRODUCT_ID !in purchase.products) return

        Log.i(TAG, "Purchase successful: ${purchase.products}")

        // Grant entitlement immediately (local cache)
        ProEntitlement.grantPro(context)
        onPurchaseComplete?.invoke(true)

        // Acknowledge the purchase — REQUIRED by Google Play policy
        if (!purchase.isAcknowledged) {
            acknowledgePurchase(purchase)
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged")
            } else {
                Log.e(TAG, "Acknowledge failed: ${result.debugMessage}")
            }
        }
    }

    fun release() {
        billingClient?.endConnection()
        billingClient = null
        isConnected = false
    }
}
