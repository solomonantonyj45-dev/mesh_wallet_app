package com.meshwallet.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import uniffi.mesh_wallet_core.WalletCore

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)

        try {
            val payerSigner = StrongBoxSigner("payer_key")
            val receiverSigner = StrongBoxSigner("receiver_key")

            val payerWallet = WalletCore(payerSigner, 10u)
            val receiverWallet = WalletCore(receiverSigner, 10u)

            val recipientPubkey = receiverSigner.publicKey()
            val spend = payerWallet.spendNextUnit(0u, recipientPubkey)
            val creditedUnit = receiverWallet.receiveSpend(spend)

            tv.text = "Wallet core loaded successfully!\nSpent+received unit: $creditedUnit"
        } catch (e: Exception) {
            tv.text = "Wallet core failed: ${e.message}\n${e.stackTraceToString()}"
        }

        setContentView(tv)
    }
}
