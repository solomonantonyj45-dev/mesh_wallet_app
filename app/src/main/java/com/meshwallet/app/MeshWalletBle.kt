package com.meshwallet.app

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.widget.TextView
import uniffi.mesh_wallet_core.WalletCore
import uniffi.mesh_wallet_core.signedSpendToBytes
import uniffi.mesh_wallet_core.signedSpendFromBytes
import java.util.UUID

class MeshWalletBle(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val statusText: TextView
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val SPEND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000abce-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null

    private fun log(msg: String) {
        (context as? androidx.appcompat.app.AppCompatActivity)?.runOnUiThread {
            statusText.text = msg
        }
    }

    // ---------- PAYER: advertises, waits for a receiver, signs, sends ----------

    fun startAsPayer() {
        log("Payer: generating identity...")
        val signer = StrongBoxSigner("payer_key")
        val wallet = WalletCore(signer, 10u)

        val characteristic = BluetoothGattCharacteristic(
            SPEND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray
            ) {
                // Receiver writes their public key here to request a spend
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                log("Payer: received recipient pubkey, signing spend...")
                try {
                    val spend = wallet.spendNextUnit(0u, value)
                    val bytes = signedSpendToBytes(spend)
                    characteristic.value = bytes
                    log("Payer: spend signed (${bytes.size} bytes). Ready to be read.")
                } catch (e: Exception) {
                    log("Payer error: ${e.message}")
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    characteristic.value
                )
            }
        }

        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)

        val advertiser = adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                log("Payer: advertise failed, code $errorCode")
            }
        })

        log("Payer: advertising. Waiting for receiver...")
    }

    // ---------- RECEIVER: scans, connects, sends pubkey, reads signed spend ----------

    fun startAsReceiver() {
        log("Receiver: generating identity...")
        val signer = StrongBoxSigner("receiver_key")
        val wallet = WalletCore(signer, 10u)
        val myPubkey = signer.publicKey()

        val scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        log("Receiver: scanning for payer...")

        scanner.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                connectAndRequestSpend(result.device, wallet, myPubkey)
            }
        })
    }

    private fun connectAndRequestSpend(device: BluetoothDevice, wallet: WalletCore, myPubkey: ByteArray) {
        log("Receiver: connecting to payer...")
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.getService(SERVICE_UUID)
                    ?.getCharacteristic(SPEND_CHARACTERISTIC_UUID) ?: return

                log("Receiver: sending our pubkey to request spend...")
                characteristic.value = myPubkey
                gatt.writeCharacteristic(characteristic)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                log("Receiver: pubkey sent. Reading signed spend...")
                gatt.readCharacteristic(characteristic)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
            ) {
                try {
                    val bytes = characteristic.value
                    val spend = signedSpendFromBytes(bytes)
                    val creditedUnit = wallet.receiveSpend(spend)
                    log("SUCCESS! Received and verified unit: $creditedUnit")
                } catch (e: Exception) {
                    log("Receiver error: ${e.message}\n${e.stackTraceToString()}")
                }
            }
        })
    }
}
