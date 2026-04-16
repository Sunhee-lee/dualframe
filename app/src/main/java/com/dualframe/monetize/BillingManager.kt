package com.dualframe.monetize

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing wrapper for PRO watermark-removal purchase.
 *
 * Handles connection, product query, purchase flow, and entitlement check.
 * Uses a single in-app product for permanent watermark removal.
 */
class BillingManager(private val context: Context) {

    companion object {
        private const val TAG = "BillingManager"

        // *** PLACEHOLDER PRODUCT ID ***
        // Replace with your real Google Play Console in-app product ID before release.
        // This must match the product ID configured in Google Play Console.
        const val PRO_REMOVE_WATERMARK_PRODUCT_ID = "pro_remove_watermark"
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    Log.i(TAG, "Purchase successful: ${purchase.products}")
                    ProEntitlement.grantPro(context)
                    onPurchaseComplete?.invoke(true)
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "Purchase canceled by user")
            onPurchaseComplete?.invoke(false)
        } else {
            Log.e(TAG, "Purchase error: ${billingResult.debugMessage}")
            onPurchaseComplete?.invoke(false)
        }
    }

    private var onPurchaseComplete: ((Boolean) -> Unit)? = null

    fun connect() {
        val client = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
        billingClient = client

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing connected")
                    queryProduct()
                    checkExistingPurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected")
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_REMOVE_WATERMARK_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient?.queryProductDetailsAsync(params) { _, detailsList ->
            productDetails = detailsList.firstOrNull()
            if (productDetails != null) {
                Log.i(TAG, "Product details loaded: ${productDetails?.title}")
            } else {
                Log.w(TAG, "Product not found: $PRO_REMOVE_WATERMARK_PRODUCT_ID")
            }
        }
    }

    /** Check if the user already owns the PRO product. */
    private fun checkExistingPurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { _, purchases ->
            for (purchase in purchases) {
                if (PRO_REMOVE_WATERMARK_PRODUCT_ID in purchase.products &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                ) {
                    Log.i(TAG, "Existing PRO purchase found")
                    ProEntitlement.grantPro(context)
                }
            }
        }
    }

    /**
     * Launch the Google Play purchase flow for PRO.
     * [onComplete] is called with true on success, false on cancel/error.
     */
    fun launchPurchase(activity: Activity, onComplete: (Boolean) -> Unit) {
        val details = productDetails
        if (details == null) {
            Log.e(TAG, "Product details not available")
            onComplete(false)
            return
        }

        onPurchaseComplete = onComplete

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .apply { if (offerToken != null) setOfferToken(offerToken) }
                .build()
        )

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient?.launchBillingFlow(activity, flowParams)
    }

    fun release() {
        billingClient?.endConnection()
        billingClient = null
    }
}
