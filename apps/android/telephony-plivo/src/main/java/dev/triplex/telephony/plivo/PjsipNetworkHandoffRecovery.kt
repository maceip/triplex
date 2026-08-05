package dev.triplex.telephony.plivo

internal fun interface NativeNetworkChangeDriver {
    fun handleNetworkChange(nativeHandle: Long): Int
}

/** Serial control-plane seam around PJSIP's pjsua_handle_ip_change(). */
internal class PjsipNetworkHandoffRecovery(
    private val driver: NativeNetworkChangeDriver,
) {
    fun recover(nativeHandle: Long): Int {
        require(nativeHandle != 0L) { "A live PJSIP handle is required for handoff" }
        return driver.handleNetworkChange(nativeHandle)
    }
}
