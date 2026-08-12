package dev.triplex.data.repository

import android.app.Activity
import dev.triplex.BuildConfig
import dev.triplex.billing.PlayBillingGateway
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.remote.GatewayApi
import dev.triplex.data.remote.LineAllocation
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claims the Triplex line entitlement and persists DID + SIP credentials.
 *
 * Debug / stub-mode gateways accept [BuildConfig.ENTITLEMENT_STUB_UNLOCK] or a
 * `stub.*` purchase token without a real Play Console product. Release builds
 * prefer an owned Play purchase token, then launch Billing when a product
 * exists; if Play has nothing yet they still fall back to the stub claim so
 * early production can allocate lines before Console setup lands.
 */
@Singleton
class EntitlementRepository @Inject constructor(
    private val api: GatewayApi,
    private val storage: SecureStorage,
    private val billing: PlayBillingGateway,
) {
    val productId: String get() = BuildConfig.ENTITLEMENT_PRODUCT_ID

    suspend fun getCachedLineDid(): String? = storage.getTriplexDid()

    suspend fun refreshLine(): Result<LineAllocation?> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")
        return try {
            val line = api.getDeviceLine(token)
            if (line != null) {
                persistAllocation(line)
            }
            Result.Success(line)
        } catch (e: Exception) {
            Timber.w(e, "Failed to refresh line")
            Result.Error("Failed to load line: ${e.message}", e)
        }
    }

    /**
     * Stub unlock for debug builds (and early prod while Play Console is empty).
     */
    suspend fun claimWithStubUnlock(): Result<LineAllocation> {
        return claim(
            purchaseToken = "stub.${UUID.randomUUID().toString().replace("-", "")}",
            stubUnlock = BuildConfig.ENTITLEMENT_STUB_UNLOCK.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Prefer an existing Play purchase; otherwise launch Billing when product
     * details exist. Returns null from Billing launch so the caller can wait
     * for a later [claimOwnedPlayPurchases] after the purchase completes.
     */
    suspend fun unlockLine(activity: Activity?): Result<LineAllocation?> {
        when (val owned = claimOwnedPlayPurchases()) {
            is Result.Success -> if (owned.data != null) return Result.Success(owned.data)
            is Result.Error -> Timber.w(owned.message)
        }

        val details = billing.queryProductDetails(productId)
        if (details != null && activity != null) {
            val launch = billing.launchPurchase(activity, details)
            if (launch.responseCode != com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                return Result.Error("Play Billing launch failed: ${launch.debugMessage}")
            }
            return Result.Success(null)
        }

        // No Play SKU yet — use the stub path so installs still get a line.
        return when (val stub = claimWithStubUnlock()) {
            is Result.Success -> Result.Success(stub.data)
            is Result.Error -> stub
        }
    }

    suspend fun claimOwnedPlayPurchases(): Result<LineAllocation?> {
        val purchases = billing.queryOwnedPurchases()
            .filter { purchase ->
                productId in purchase.products &&
                    purchase.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED
            }
        val purchase = purchases.firstOrNull() ?: return Result.Success(null)
        return when (
            val claimed = claim(
                purchaseToken = purchase.purchaseToken,
                stubUnlock = null,
            )
        ) {
            is Result.Success -> {
                billing.acknowledge(purchase)
                Result.Success(claimed.data)
            }
            is Result.Error -> claimed
        }
    }

    private suspend fun claim(
        purchaseToken: String?,
        stubUnlock: String?,
    ): Result<LineAllocation> {
        val token = storage.getDeviceToken() ?: return Result.Error("Not authenticated")
        return try {
            val allocation = api.claimEntitlement(
                productId = productId,
                purchaseToken = purchaseToken,
                stubUnlock = stubUnlock,
                deviceToken = token,
            )
            persistAllocation(allocation)
            Timber.i("Line entitlement claimed: did=%s status=%s", allocation.did, allocation.status)
            Result.Success(allocation)
        } catch (e: Exception) {
            Timber.e(e, "Entitlement claim failed")
            Result.Error("Failed to unlock line: ${e.message}", e)
        }
    }

    private fun persistAllocation(allocation: LineAllocation) {
        storage.setTriplexDid(allocation.did)
        storage.setPlivoUsername(allocation.sip.username)
        storage.setPlivoPassword(allocation.sip.password)
        storage.setPlivoDomain(allocation.sip.domain)
    }
}
