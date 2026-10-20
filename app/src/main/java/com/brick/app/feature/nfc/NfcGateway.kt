package com.brick.app.feature.nfc

/**
 * Narrow view of the NFC radio the scan controller needs, so the controller can be
 * exercised without an Activity or NFC hardware.
 */
interface NfcGateway {
    val isAvailable: Boolean
    val isEnabled: Boolean
    fun enableDispatch()
    fun disableDispatch()
}

class NfcManagerGateway(private val nfcManager: NfcManager) : NfcGateway {
    override val isAvailable: Boolean get() = nfcManager.isNfcAvailable
    override val isEnabled: Boolean get() = nfcManager.isNfcEnabled
    override fun enableDispatch() = nfcManager.enableForegroundDispatch()
    override fun disableDispatch() = nfcManager.disableForegroundDispatch()
}

object UnavailableNfcGateway : NfcGateway {
    override val isAvailable: Boolean = false
    override val isEnabled: Boolean = false
    override fun enableDispatch() = Unit
    override fun disableDispatch() = Unit
}
