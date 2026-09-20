package com.meshwallet.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import uniffi.mesh_wallet_core.HardwareSigner

class StrongBoxSigner(private val alias: String) : HardwareSigner {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var currentCounter: UInt = 0u
    private val spentUnits = mutableSetOf<UInt>()

    init {
        if (!keyStore.containsAlias(alias)) {
            generateKey()
        }
    }

    private fun generateKey() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val specBuilder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))

        try {
            specBuilder.setIsStrongBoxBacked(true)
            generator.initialize(specBuilder.build())
        } catch (e: Exception) {
            specBuilder.setIsStrongBoxBacked(false)
            generator.initialize(specBuilder.build())
        }
        generator.generateKeyPair()
    }

    override fun sign(payload: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(alias, null) as java.security.PrivateKey
        val sig = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(payload)
        }
        return sig.sign()
    }

    override fun publicKey(): ByteArray {
        val cert = keyStore.getCertificate(alias)
        return cert.publicKey.encoded
    }

    override fun advanceCounter(unitId: UInt): Boolean {
        if (spentUnits.contains(unitId)) return false
        if (unitId != currentCounter) return false
        spentUnits.add(unitId)
        currentCounter++
        return true
    }
}
