package app.aurora.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.coroutines.resume

/*
 * This installation's own credential (docs/ANTI_ABUSE.md). Separate from every account on purpose: switching,
 * signing out or deleting an account leaves it as it is, so the phone's Free allowance stays the phone's.
 *
 * The key is ECDSA P-256, made in the Android Keystore and never exportable. The server binds the first key it
 * sees for an install id and from then on counts that id only with its signature. It is not a hardware id: cleared
 * data, a reinstall or a factory reset make a new id and a new key, and nothing here pretends otherwise.
 */

private const val Keystore = "AndroidKeyStore"
private const val ProofVersion = "aurora-device-v1"

private fun sha256Hex(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** The message a request's proof signs: its route, the sign-in it rides on and its exact body. Mirrors server/src/device.js. */
fun proofMessage(path: String, token: String, body: String) = "$ProofVersion\n$path\n${sha256Hex(token)}\n${sha256Hex(body)}"

class DeviceCredential(installId: String) {
    private val alias = "aurora_device_$installId"

    /** Null where there is no Keystore (Robolectric, a broken vendor Keystore): requests then go unproved. */
    private val entry: KeyStore.PrivateKeyEntry? by lazy { runCatching { load() ?: create() }.getOrNull() }

    private fun keyStore() = KeyStore.getInstance(Keystore).apply { load(null) }
    private fun load() = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry

    private fun create(): KeyStore.PrivateKeyEntry? {
        // The TEE, not StrongBox: every request is signed, and StrongBox can take a noticeable moment per signature.
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, Keystore).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKeyPair()
        return load()
    }

    /** Headers proving this request comes from this installation. Empty when there is no key. */
    fun headers(path: String, token: String, body: String): Map<String, String> {
        val key = entry ?: return emptyMap()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key.privateKey)
            update(proofMessage(path, token, body).toByteArray())
        }.sign()
        val encoder = Base64.getEncoder()
        return mapOf(
            "X-Aurora-Device-Key" to encoder.encodeToString(key.certificate.publicKey.encoded),
            "X-Aurora-Device-Signature" to encoder.encodeToString(signature),
        )
    }

    fun forget() {
        runCatching { keyStore().deleteEntry(alias) }
    }
}

/**
 * The server no longer accepts this install id (its key is gone, or another key holds it): the next start is a new
 * installation with a new key. Nothing about the account changes, and its ledger stays with it.
 */
fun resetInstallation(context: Context, installId: String) {
    DeviceCredential(installId).forget()
    File(context.noBackupFilesDir, "install_id").delete()
}

/**
 * A Play Integrity token for the server's [nonce], or null when there is none to be had: no cloud project in this
 * build, no Play services, or no answer in time. The server never holds a missing verdict against anyone.
 */
suspend fun playIntegrityToken(context: Context, cloudProjectNumber: Long, nonce: String): String? {
    if (cloudProjectNumber <= 0L) return null
    return runCatching {
        withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { c ->
                IntegrityManagerFactory.create(context.applicationContext)
                    .requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(cloudProjectNumber).build())
                    .addOnSuccessListener { c.resume(it.token()) }
                    .addOnFailureListener { c.resume(null) }
            }
        }
    }.getOrNull()
}
