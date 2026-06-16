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

class BillingManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID = "dualframe_pro"

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
    private var connectionRetryCount = 0

    val formattedPrice: String?
        get() = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        Log.i(TAG, "purchasesUpdated: code=${result.responseCode} msg=${result.debugMessage}")
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase canceled by user")
                onPurchaseComplete?.invoke(false)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "Item already owned — restoring")
                ProEntitlement.grantPro(context)
                onPurchaseComplete?.invoke(true)
            }
            else -> {
                Log.e(TAG, "Purchase error code=${result.responseCode}: ${result.debugMessage}")
                onPurchaseComplete?.invoke(false)
            }
        }
    }

    fun connect() {
        if (isConnected) {
            Log.d(TAG, "Already connected")
            return
        }
        Log.i(TAG, "Connecting billing client... (packageName=${context.packageName})")

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
                Log.i(TAG, "Billing setup: code=${result.responseCode} msg=${result.debugMessage}")
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    connectionRetryCount = 0
                    queryProduct()
                    restorePurchases()
                } else {
                    isConnected = false
                    Log.e(TAG, "Billing setup FAILED: code=${result.responseCode} " +
                        "msg=${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.w(TAG, "Billing disconnected (retry=$connectionRetryCount)")
                if (connectionRetryCount < 3) {
                    connectionRetryCount++
                    connect()
                }
            }
        })
    }

    private fun queryProduct() {
        Log.i(TAG, "Querying product: $PRODUCT_ID (type=INAPP)")
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

        billingClient?.queryProductDetailsAsync(params) { result, detailsList ->
            Log.i(TAG, "queryProductDetails: code=${result.responseCode} " +
                "msg=${result.debugMessage} count=${detailsList.size}")
            productDetails = detailsList.firstOrNull()
            if (productDetails != null) {
                Log.i(TAG, "Product loaded: id=${productDetails?.productId} " +
                    "title=${productDetails?.title} " +
                    "price=${productDetails?.oneTimePurchaseOfferDetails?.formattedPrice}")
            } else {
                Log.e(TAG, "Product NOT FOUND: '$PRODUCT_ID'. " +
                    "Check: 1) Play Console product ID matches exactly " +
                    "2) App is published (at least internal test) " +
                    "3) Package name matches (${context.packageName})")
            }
        }
    }

    fun restorePurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            Log.i(TAG, "restorePurchases: code=${result.responseCode} count=${purchases.size}")
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "restorePurchases query failed — keeping current state")
                return@queryPurchasesAsync
            }
            var found = false
            for (purchase in purchases) {
                if (PRODUCT_ID in purchase.products &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                ) {
                    found = true
                    ProEntitlement.grantPro(context)
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }
                    Log.i(TAG, "Restored PRO purchase")
                }
            }
            if (!found) {
                Log.i(TAG, "No existing PRO purchase found — revoking PRO")
                ProEntitlement.revokePro(context)
            }
        }
    }

    fun launchPurchase(activity: Activity, onComplete: (Boolean) -> Unit) {
        if (ProEntitlement.isProOwned(context)) {
            Log.i(TAG, "Already PRO — skipping purchase")
            onComplete(true)
            return
        }

        val details = productDetails
        if (details == null) {
            Log.e(TAG, "productDetails is NULL — cannot launch purchase. " +
                "isConnected=$isConnected billingClient=${billingClient != null}")
            if (!isConnected) {
                Log.i(TAG, "Reconnecting billing...")
                connect()
            }
            onComplete(false)
            return
        }

        onPurchaseComplete = onComplete
        Log.i(TAG, "Launching purchase flow for: ${details.productId}")

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
        Log.i(TAG, "launchBillingFlow: code=${result?.responseCode} msg=${result?.debugMessage}")
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "launchBillingFlow FAILED: code=${result?.responseCode} " +
                "msg=${result?.debugMessage}")
            onComplete(false)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        Log.i(TAG, "handlePurchase: products=${purchase.products} " +
            "state=${purchase.purchaseState} acknowledged=${purchase.isAcknowledged}")
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (PRODUCT_ID !in purchase.products) return

        ProEntitlement.grantPro(context)
        onPurchaseComplete?.invoke(true)

        if (!purchase.isAcknowledged) {
            acknowledgePurchase(purchase)
        }
    }

    private fun acknowledgePurchase(purchase: Purchase, retryCount: Int = 0) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.acknowledgePurchase(params) { result ->
            Log.i(TAG, "acknowledge: code=${result.responseCode} msg=${result.debugMessage}")
            if (result.responseCode != BillingClient.BillingResponseCode.OK && retryCount < 3) {
                Log.w(TAG, "Acknowledge failed, retrying (${retryCount + 1}/3)")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    acknowledgePurchase(purchase, retryCount + 1)
                }, (retryCount + 1) * 2000L)
            }
        }
    }

    fun release() {
        billingClient?.endConnection()
        billingClient = null
        isConnected = false
    }
}
