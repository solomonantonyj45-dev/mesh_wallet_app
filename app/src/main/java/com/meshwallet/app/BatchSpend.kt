package com.meshwallet.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import uniffi.mesh_wallet_core.WalletCore
import uniffi.mesh_wallet_core.signedSpendToBytes
import uniffi.mesh_wallet_core.signedSpendFromBytes

object BatchSpend {

    fun createBatchPaymentQrPayload(
        wallet: WalletCore,
        startUnit: UInt,
        amount: Int,
        recipientPubkey: ByteArray
    ): String {
        val array = JSONArray()
        for (i in 0 until amount) {
            val unitId = startUnit + i.toUInt()
            val spend = wallet.spendNextUnit(unitId, recipientPubkey)
            val bytes = signedSpendToBytes(spend)
            array.put(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        val obj = JSONObject()
        obj.put("amount", amount)
        obj.put("spends", array)
        return obj.toString()
    }

    fun receiveBatchPayment(wallet: WalletCore, qrPayload: String): Int {
        val obj = JSONObject(qrPayload)
        val array = obj.getJSONArray("spends")
        var creditedCount = 0
        for (i in 0 until array.length()) {
            val bytes = Base64.decode(array.getString(i), Base64.NO_WRAP)
            val spend = signedSpendFromBytes(bytes)
            wallet.receiveSpend(spend)
            creditedCount++
        }
        return creditedCount
    }
}
