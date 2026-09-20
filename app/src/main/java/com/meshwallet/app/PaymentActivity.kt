package com.meshwallet.app

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.result.contract.ActivityResultContracts
import uniffi.mesh_wallet_core.WalletCore

class PaymentActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var qrImage: ImageView
    private var payerWallet: WalletCore? = null
    private var nextUnit: UInt = 0u

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedPayment(result.contents)
        } else {
            statusText.text = "Scan cancelled."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 100, 40, 40)

        statusText = TextView(this)
        statusText.text = "Enter amount to pay, or scan to receive."
        layout.addView(statusText)

        val amountInput = EditText(this)
        amountInput.hint = "Amount (e.g. 50)"
        layout.addView(amountInput)

        val payButton = Button(this)
        payButton.text = "Generate Payment QR"
        payButton.setOnClickListener {
            val amount = amountInput.text.toString().toIntOrNull()
            if (amount == null || amount <= 0) {
                statusText.text = "Enter a valid amount."
            } else {
                generatePaymentQr(amount)
            }
        }
        layout.addView(payButton)

        qrImage = ImageView(this)
        qrImage.layoutParams = LinearLayout.LayoutParams(600, 600)
        layout.addView(qrImage)

        val scanButton = Button(this)
        scanButton.text = "Scan to Receive Payment"
        scanButton.setOnClickListener {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt("Scan payment QR")
            options.setBeepEnabled(true)
            scanLauncher.launch(options)
        }
        layout.addView(scanButton)

        setContentView(layout)
    }

    private fun generatePaymentQr(amount: Int) {
        try {
            statusText.text = "Signing $amount units..."

            if (payerWallet == null) {
                val signer = StrongBoxSigner("payer_key")
                payerWallet = WalletCore(signer, 10000u) // generous ceiling for demo purposes
            }

            // For a real two-party flow, the recipient's pubkey would come from their
            // own QR or a prior exchange. For this demo, we generate a fresh receiver
            // identity on this same device purely to produce a valid recipient pubkey.
            val recipientSigner = StrongBoxSigner("receiver_key")
            val recipientPubkey = recipientSigner.publicKey()

            val payload = BatchSpend.createBatchPaymentQrPayload(
                payerWallet!!, nextUnit, amount, recipientPubkey
            )
            nextUnit += amount.toUInt()

            val bitmap = generateQrBitmap(payload)
            qrImage.setImageBitmap(bitmap)
            statusText.text = "QR ready. Amount: ₹$amount. Ask receiver to scan."
        } catch (e: Exception) {
            statusText.text = "Error generating QR: ${e.message}\n${e.stackTraceToString()}"
        }
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 600, 600)
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.RGB_565)
        for (x in 0 until 600) {
            for (y in 0 until 600) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }

    private fun handleScannedPayment(qrContent: String) {
        try {
            statusText.text = "Verifying payment..."
            val signer = StrongBoxSigner("receiver_key")
            val wallet = WalletCore(signer, 10000u)

            val creditedCount = BatchSpend.receiveBatchPayment(wallet, qrContent)
            statusText.text = "SUCCESS! Received and verified $creditedCount units (₹$creditedCount)."
        } catch (e: Exception) {
            statusText.text = "Payment verification failed: ${e.message}\n${e.stackTraceToString()}"
        }
    }
}
