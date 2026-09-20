package com.meshwallet.app

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
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
import uniffi.mesh_wallet_core.WalletCore

class PaymentActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var amountInput: EditText

    private var myWallet: WalletCore? = null
    private var nextUnit: UInt = 0u
    private var scannedRecipientPubkey: ByteArray? = null

    // Mode tracks what the NEXT scan result should be used for
    private var pendingScanPurpose: ScanPurpose = ScanPurpose.NONE
    private enum class ScanPurpose { NONE, RECEIVER_PUBKEY, INCOMING_PAYMENT }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            statusText.text = "Scan cancelled."
            return@registerForActivityResult
        }
        when (pendingScanPurpose) {
            ScanPurpose.RECEIVER_PUBKEY -> handleScannedRecipientPubkey(result.contents)
            ScanPurpose.INCOMING_PAYMENT -> handleScannedPayment(result.contents)
            ScanPurpose.NONE -> {}
        }
        pendingScanPurpose = ScanPurpose.NONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 100, 40, 40)

        statusText = TextView(this)
        statusText.text = "Step 1: Receiver shows their QR. Payer scans it."
        layout.addView(statusText)

        val showMyPubkeyButton = Button(this)
        showMyPubkeyButton.text = "Show My Pay-To QR (Receiver)"
        showMyPubkeyButton.setOnClickListener { showMyPubkeyQr() }
        layout.addView(showMyPubkeyButton)

        val scanPubkeyButton = Button(this)
        scanPubkeyButton.text = "Scan Recipient's Pay-To QR (Payer)"
        scanPubkeyButton.setOnClickListener {
            pendingScanPurpose = ScanPurpose.RECEIVER_PUBKEY
            launchScanner("Scan recipient's pay-to QR")
        }
        layout.addView(scanPubkeyButton)

        amountInput = EditText(this)
        amountInput.hint = "Amount (e.g. 50)"
        layout.addView(amountInput)

        val payButton = Button(this)
        payButton.text = "Generate Payment QR"
        payButton.setOnClickListener {
            val amount = amountInput.text.toString().toIntOrNull()
            when {
                amount == null || amount <= 0 -> statusText.text = "Enter a valid amount."
                scannedRecipientPubkey == null -> statusText.text = "Scan recipient's pay-to QR first."
                else -> generatePaymentQr(amount)
            }
        }
        layout.addView(payButton)

        qrImage = ImageView(this)
        qrImage.layoutParams = LinearLayout.LayoutParams(600, 600)
        layout.addView(qrImage)

        val scanPaymentButton = Button(this)
        scanPaymentButton.text = "Scan to Receive Payment"
        scanPaymentButton.setOnClickListener {
            pendingScanPurpose = ScanPurpose.INCOMING_PAYMENT
            launchScanner("Scan payment QR")
        }
        layout.addView(scanPaymentButton)

        setContentView(layout)
    }

    private fun launchScanner(prompt: String) {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt(prompt)
        options.setBeepEnabled(true)
        scanLauncher.launch(options)
    }

    private fun getOrCreateWallet(): WalletCore {
        if (myWallet == null) {
            val signer = StrongBoxSigner("wallet_key")
            myWallet = WalletCore(signer, 10000u)
        }
        return myWallet!!
    }

    // ---------- Step 1: RECEIVER shows their real pubkey as a QR ----------
    private fun showMyPubkeyQr() {
        try {
            getOrCreateWallet() // ensures the key exists
            val signer = StrongBoxSigner("wallet_key")
            val pubkey = signer.publicKey()
            val payload = Base64.encodeToString(pubkey, Base64.NO_WRAP)
            qrImage.setImageBitmap(generateQrBitmap(payload))
            statusText.text = "This is your pay-to QR. Ask the payer to scan it."
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    // ---------- Step 2: PAYER scans that pubkey ----------
    private fun handleScannedRecipientPubkey(content: String) {
        try {
            scannedRecipientPubkey = Base64.decode(content, Base64.NO_WRAP)
            statusText.text = "Recipient pubkey captured. Enter amount and generate payment QR."
        } catch (e: Exception) {
            statusText.text = "Invalid pay-to QR: ${e.message}"
        }
    }

    // ---------- Step 3: PAYER generates the actual payment, bound to the real recipient ----------
    private fun generatePaymentQr(amount: Int) {
        try {
            statusText.text = "Signing $amount units..."
            val wallet = getOrCreateWallet()
            val recipientPubkey = scannedRecipientPubkey!!

            val payload = BatchSpend.createBatchPaymentQrPayload(
                wallet, nextUnit, amount, recipientPubkey
            )
            nextUnit += amount.toUInt()

            qrImage.setImageBitmap(generateQrBitmap(payload))
            statusText.text = "Payment QR ready: ₹$amount. Ask receiver to scan it."
        } catch (e: Exception) {
            statusText.text = "Error generating payment: ${e.message}\n${e.stackTraceToString()}"
        }
    }

    // ---------- Step 4: RECEIVER scans the payment and it verifies against THEIR real key ----------
    private fun handleScannedPayment(qrContent: String) {
        try {
            statusText.text = "Verifying payment..."
            val wallet = getOrCreateWallet()
            val creditedCount = BatchSpend.receiveBatchPayment(wallet, qrContent)
            statusText.text = "SUCCESS! Received and verified $creditedCount units (₹$creditedCount)."
        } catch (e: Exception) {
            statusText.text = "Payment verification failed: ${e.message}\n${e.stackTraceToString()}"
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
}
