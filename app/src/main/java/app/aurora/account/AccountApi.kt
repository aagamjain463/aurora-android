package app.aurora.account

import app.aurora.data.AuroraJson
import app.aurora.plan.SignedEntitlement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/* The account service's HTTP API (server/src/worker.js). Sends only what each route needs (docs/ACCOUNT_SYSTEM.md). */

@Serializable data class RemoteAccount(val id: String = "", val email: String? = null, val displayName: String? = null, val deletionRequestedAt: Long? = null)
@Serializable data class RemoteQuota(val usedMs: Long = 0, val limitMs: Long = 0, val remainingMs: Long = 0, val resetAt: Long = 0)
@Serializable data class RemoteDevice(val id: String = "", val label: String? = null, val registeredAt: Long = 0, val lastSeenAt: Long = 0, val current: Boolean = false)
/** This installation as the server sees it. [attestNonce]: a Play Integrity check is due, for this nonce. */
@Serializable data class RemoteInstallation(val proved: Boolean = false, val integrity: String = "unknown", val attestNonce: String? = null)
/** The 14-day trial as the server sees this account: available, active, ended or unavailable ([reason]). */
@Serializable data class RemoteTrial(val status: String = "unavailable", val reason: String? = null, val startedAt: Long? = null, val endsAt: Long? = null, val days: Int? = null)
/** Founding Lifetime for this account: off, available, held, owned or sold_out. Never a count. */
@Serializable data class RemoteLifetime(val offer: String = "off")
/** The answer to a trial claim, granted or not: the server sends one shape for both (server/src/worker.js, claimTrial). */
@Serializable data class TrialResponse(
    val trial: RemoteTrial? = null,
    val entitlement: SignedEntitlement? = null,
    val error: String? = null,
    val reason: String? = null,
    val attestNonce: String? = null,
)
@Serializable data class HoldResponse(val held: Boolean = false, val offer: String = "off", val expiresAt: Long? = null)
@Serializable data class SyncResponse(
    val account: RemoteAccount = RemoteAccount(),
    val entitlement: SignedEntitlement = SignedEntitlement(),
    val quota: RemoteQuota? = null,
    val devices: List<RemoteDevice> = emptyList(),
    val device: RemoteInstallation? = null,
    val trial: RemoteTrial? = null,
    val lifetime: RemoteLifetime? = null,
)
@Serializable data class ReserveResponse(
    val allowed: Boolean = false,
    val tier: String = "free",
    val authorization: SignedEntitlement? = null,
    val usedMs: Long = 0,
    val limitMs: Long = 0,
    val remainingMs: Long = 0,
    val resetAt: Long = 0,
)
@Serializable data class DevicesResponse(val devices: List<RemoteDevice> = emptyList())
@Serializable data class DeletionResponse(val deletionAt: Long = 0)
@Serializable data class Empty(val ok: Boolean = true)
@Serializable data class ErrorBody(val error: String = "")

/** The signed answer to a reservation. [reservationId] is null on Plus, which reserves nothing. */
@Serializable
data class Reservation(
    val type: String = "",
    val reservationId: String? = null,
    val uid: String = "",
    val installId: String = "",
    val commitmentId: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val tier: String = "free",
    val usedMs: Long = 0,
    val limitMs: Long = 0,
    val remainingMs: Long = 0,
    val resetAt: Long = 0,
    val packageName: String = "",
)

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** The server answered no. [code] is the error it gave. */
    data class Refused(val status: Int, val code: String) : ApiResult<Nothing>
    /** No answer, or one that makes no sense. Nothing was decided. */
    data object Unreachable : ApiResult<Nothing>
}

data class HttpReply(val status: Int, val body: String)

/** Sends one request, with [headers] besides the token. Null when the server can't be reached. */
typealias Transport = suspend (path: String, token: String, body: String, headers: Map<String, String>) -> HttpReply?

/** The installation's proof for one request: headers that sign [body] for [path] under [token]. */
typealias DeviceProof = (path: String, token: String, body: String) -> Map<String, String>

fun httpTransport(baseUrl: String): Transport = { path, token, body, headers ->
    withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                // A commitment waits on this. Short enough that a dead network is a quick, honest answer.
                c.connectTimeout = 8_000
                c.readTimeout = 12_000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $token")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                val status = c.responseCode
                val stream = if (status in 200..299) c.inputStream else c.errorStream
                HttpReply(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}

class AccountApi(private val transport: Transport, private val prove: DeviceProof = { _, _, _ -> emptyMap() }) {
    private suspend fun <T> call(path: String, token: String, body: JsonObject, serializer: KSerializer<T>): ApiResult<T> {
        val text = body.toString()
        val headers = withContext(Dispatchers.Default) { runCatching { prove(path, token, text) }.getOrDefault(emptyMap()) }
        val reply = transport(path, token, text, headers) ?: return ApiResult.Unreachable
        if (reply.status == 503 || reply.status >= 500) return ApiResult.Unreachable
        if (reply.status !in 200..299) return ApiResult.Refused(reply.status, runCatching { AuroraJson.decodeFromString(ErrorBody.serializer(), reply.body).error }.getOrDefault(""))
        return runCatching { ApiResult.Ok(AuroraJson.decodeFromString(serializer, reply.body)) }.getOrDefault(ApiResult.Unreachable)
    }

    /** Like [call], but a 4xx body is the answer too (a trial refusal says why). 5xx and no answer are [ApiResult.Unreachable]. */
    private suspend fun <T> answer(path: String, token: String, body: JsonObject, serializer: KSerializer<T>): ApiResult<T> {
        val text = body.toString()
        val headers = withContext(Dispatchers.Default) { runCatching { prove(path, token, text) }.getOrDefault(emptyMap()) }
        val reply = transport(path, token, text, headers) ?: return ApiResult.Unreachable
        if (reply.status >= 500) return ApiResult.Unreachable
        if (reply.status == 401 || reply.status == 403 && reply.body.contains("device_proof_required")) {
            return ApiResult.Refused(reply.status, runCatching { AuroraJson.decodeFromString(ErrorBody.serializer(), reply.body).error }.getOrDefault(""))
        }
        return runCatching { ApiResult.Ok(AuroraJson.decodeFromString(serializer, reply.body)) }.getOrDefault(ApiResult.Unreachable)
    }

    suspend fun claimTrial(token: String, installId: String, integrityToken: String?) =
        answer("/v1/trial/claim", token, buildJsonObject {
            put("installId", installId); put("platform", "android")
            if (integrityToken != null) put("integrityToken", integrityToken)
        }, TrialResponse.serializer())

    suspend fun holdLifetime(token: String, installId: String) =
        call("/v1/lifetime/hold", token, buildJsonObject { put("installId", installId) }, HoldResponse.serializer())

    suspend fun sync(token: String, installId: String, label: String, zone: String) =
        call("/v1/account/sync", token, buildJsonObject { put("installId", installId); put("label", label); put("zone", zone); put("platform", "android") }, SyncResponse.serializer())

    suspend fun verify(token: String, purchaseToken: String, productId: String, installId: String, packageName: String) =
        call("/v1/verify", token, buildJsonObject { put("purchaseToken", purchaseToken); put("productId", productId); put("installId", installId); put("packageName", packageName) }, SignedEntitlement.serializer())

    suspend fun reserve(token: String, installId: String, commitmentId: String, mode: String, minutes: Int, startAt: Long?, zone: String) =
        call("/v1/quota/reserve", token, buildJsonObject {
            put("installId", installId); put("idempotencyKey", commitmentId); put("commitmentId", commitmentId)
            put("mode", mode); put("minutes", minutes); put("zone", zone)
            if (startAt != null) put("startAt", startAt)
        }, ReserveResponse.serializer())

    suspend fun release(token: String, reservationId: String) =
        call("/v1/quota/release", token, buildJsonObject { put("reservationId", reservationId) }, Empty.serializer())

    /** [entries] are start, end and zone. */
    suspend fun importLedger(token: String, installId: String, entries: List<Triple<Long, Long, String>>) =
        call("/v1/quota/import", token, buildJsonObject {
            put("installId", installId)
            put("entries", JsonArray(entries.map { (start, end, zone) -> buildJsonObject { put("start", JsonPrimitive(start)); put("end", JsonPrimitive(end)); put("zone", zone) } }))
        }, Empty.serializer())

    suspend fun attest(token: String, installId: String, integrityToken: String) =
        call("/v1/device/attest", token, buildJsonObject { put("installId", installId); put("token", integrityToken) }, Empty.serializer())

    suspend fun removeDevice(token: String, installId: String, currentInstallId: String) =
        call("/v1/installations/remove", token, buildJsonObject { put("installId", installId); put("currentInstallId", currentInstallId) }, DevicesResponse.serializer())

    suspend fun requestDeletion(token: String) = call("/v1/account/delete", token, buildJsonObject { }, DeletionResponse.serializer())

    suspend fun cancelDeletion(token: String) = call("/v1/account/delete/cancel", token, buildJsonObject { }, Empty.serializer())
}
