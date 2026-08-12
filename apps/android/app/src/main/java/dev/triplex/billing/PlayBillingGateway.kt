package dev.triplex.billing

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
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Thin Play Billing wrapper for the Triplex line product.
 *
 * Until a real Play Console SKU exists, [queryProductDetails] returns empty and
 * the app uses the gateway stub claim path. When the product is published, the
 * same [launchPurchase] + purchase-token claim path works without API changes.
 */
@Singleton
class PlayBillingGateway @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { _, _ -> /* purchases delivered via query / launch callbacks */ }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    suspend fun ensureConnected(): Boolean = suspendCancellableCoroutine { cont ->
        if (client.isReady) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) {
                    cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
            }

            override fun onBillingServiceDisconnected() {
                Timber.w("Play Billing disconnected")
            }
        })
    }

    suspend fun queryProductDetails(productId: String): ProductDetails? {
        if (!ensureConnected()) return null
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
        val details: List<ProductDetails> = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                if (!cont.isActive) return@queryProductDetailsAsync
                val list: List<ProductDetails> =
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        productDetailsList
                    } else {
                        emptyList()
                    }
                cont.resume(list)
            }
        }
        return details.firstOrNull()
    }

    suspend fun queryOwnedPurchases(): List<Purchase> {
        if (!ensureConnected()) return emptyList()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ) { billingResult, purchases ->
                if (cont.isActive) {
                    cont.resume(
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            purchases
                        } else {
                            emptyList()
                        }
                    )
                }
            }
        }
    }

    fun launchPurchase(activity: Activity, productDetails: ProductDetails): BillingResult {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        return client.launchBillingFlow(activity, flowParams)
    }

    suspend fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        if (!ensureConnected()) return
        suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            ) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Timber.w("acknowledge failed: %s", result.debugMessage)
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}
