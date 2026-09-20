package com.meshwallet.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
    private var meshWallet: MeshWalletBle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 100, 40, 40)

        statusText = TextView(this)
        statusText.text = "Choose your role:"
        layout.addView(statusText)

        val payerButton = Button(this)
        payerButton.text = "I'm the Payer"
        payerButton.setOnClickListener { startAsRole(isPayer = true) }
        layout.addView(payerButton)

        val receiverButton = Button(this)
        receiverButton.text = "I'm the Receiver"
        receiverButton.setOnClickListener { startAsRole(isPayer = false) }
        layout.addView(receiverButton)

        setContentView(layout)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) {
                statusText.text = "Permissions granted. Tap a role button."
            } else {
                statusText.text = "Bluetooth permissions are required."
            }
        }

        checkAndRequestPermissions()
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            statusText.text = "Permissions already granted. Tap a role button."
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAsRole(isPayer: Boolean) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        meshWallet = MeshWalletBle(this, bluetoothManager, statusText)

        if (isPayer) {
            meshWallet?.startAsPayer()
        } else {
            meshWallet?.startAsReceiver()
        }
    }
}
