package dev.hcsummaries.export

import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.Base64

/**
 * Pure (Android-free) pieces of automatic sync: connection-code parsing and origin pinning,
 * API outcome classification, the worker retry policy, and the status shown in the app.
 */

/** A pasted dashboard connection code: the Site origin, its service access key, and a one-time pairing token. */
data class ConnectionCode(val origin: String, val serviceToken: String, val pairingToken: String, val expiresAt: Instant)

/** Credentials kept (encrypted) on the phone after pairing. */
data class SyncCredentials(
    val origin: String,
    val serviceToken: String,
    val deviceId: String,
    val deviceToken: String,
    val deviceName: String,
    val pairedAt: Instant,
)

object SyncOrigin {
    private val HOST_RE = Regex("^[A-Za-z0-9.-]+$")

    /** Returns "https://host[:port]" for an https origin with no path, query, fragment, or credentials; otherwise null. */
    fun normalize(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (!HOST_RE.matches(host) || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "https://${host.lowercase()}$port"
    }

    private val LABEL_RE = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val TLD_RE = Regex("^([a-z]{2,63}|xn--[a-z0-9-]{1,59})$")

    /** Special-use and private-network names that never belong to a hosted Site. */
    private val NON_PUBLIC_SUFFIXES = listOf(
        "localhost", "local", "localdomain", "internal", "intranet", "lan", "home", "corp", "private",
        "test", "example", "invalid", "onion", "alt", "arpa",
    )

    /**
     * For builds without a built-in Site: [normalize], plus a public DNS name on the default
     * HTTPS port. IP literals, single-label and special-use names, and explicit ports are refused,
     * so a pasted code cannot point the app at a local or internal host by accident.
     */
    fun publicHttps(url: String): String? {
        val origin = normalize(url) ?: return null
        val host = origin.removePrefix("https://")
        if (host.contains(':') || host.length > 253) return null
        val labels = host.split('.')
        if (labels.size < 2 || !labels.all { LABEL_RE.matches(it) } || !TLD_RE.matches(labels.last())) return null
        if (NON_PUBLIC_SUFFIXES.any { host == it || host.endsWith(".$it") }) return null
        return origin
    }

    fun host(origin: String): String = origin.removePrefix("https://")
}

/**
 * Which Site this build may pair with and send data to.
 *
 * - [Pinned]: built with -PhcSiteUrl. Codes for any other origin are refused, exactly as before.
 * - [Generic]: no built-in Site. A code may name any public HTTPS Site, but the user must
 *   confirm its hostname before the app sends anything. Afterwards every credentialed request
 *   goes only to the origin stored with the approved pairing.
 * - [Misconfigured]: a built-in value that is not a valid origin; nothing can pair.
 */
sealed interface SitePolicy {
    /** Whether credentials or a code for [origin] may be used. */
    fun accepts(origin: String): Boolean

    /** The origin of stored credentials, if this build may use them. Never another origin. */
    fun trustedOrigin(credentials: SyncCredentials?): String? =
        credentials?.origin?.let(SyncOrigin::normalize)?.takeIf { it == credentials.origin && accepts(it) }

    data class Pinned(val origin: String) : SitePolicy {
        override fun accepts(origin: String): Boolean = SyncOrigin.normalize(origin) == this.origin
    }

    data object Generic : SitePolicy {
        override fun accepts(origin: String): Boolean = SyncOrigin.publicHttps(origin) == origin
    }

    data object Misconfigured : SitePolicy {
        override fun accepts(origin: String): Boolean = false
    }

    companion object {
        fun fromBuildConfig(siteUrl: String): SitePolicy = when {
            siteUrl.isBlank() -> Generic
            else -> SyncOrigin.normalize(siteUrl)?.let(::Pinned) ?: Misconfigured
        }
    }
}

object ConnectionCodes {
    const val PREFIX = "HCS1."
    private const val MAX_LENGTH = 8192
    private val PAIRING_RE = Regex("^hcp_[A-Za-z0-9_-]{43}$")
    private val SERVICE_RE = Regex("^[\\x21-\\x7e]{8,4096}$")

    /** [UNSAFE_SITE]: a build without a built-in Site got a code for a non-public or non-HTTPS origin. */
    enum class Problem { NO_SITE_CONFIGURED, MALFORMED, WRONG_SITE, UNSAFE_SITE, EXPIRED }

    sealed interface Result {
        data class Ok(val code: ConnectionCode) : Result
        data class Invalid(val problem: Problem) : Result
    }

    /**
     * Parses a code and pins it to [expectedOrigin] (BuildConfig.SITE_URL). Codes for any other
     * origin, non-https origins, malformed tokens, and expired codes are rejected before any request.
     */
    fun parse(text: String, expectedOrigin: String, now: Instant): Result {
        val pinned = SyncOrigin.normalize(expectedOrigin) ?: return Result.Invalid(Problem.NO_SITE_CONFIGURED)
        return parse(text, SitePolicy.Pinned(pinned), now)
    }

    /**
     * Parses a code under [policy]. Nothing here makes a request: a [SitePolicy.Generic] result
     * still has to be confirmed by the user (see [ConnectionCode.origin]) before pairing.
     */
    fun parse(text: String, policy: SitePolicy, now: Instant): Result {
        if (policy is SitePolicy.Misconfigured) return Result.Invalid(Problem.NO_SITE_CONFIGURED)
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX) || trimmed.length > MAX_LENGTH) return Result.Invalid(Problem.MALFORMED)
        val json = try {
            JSONObject(String(Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length)), Charsets.UTF_8))
        } catch (_: Exception) {
            return Result.Invalid(Problem.MALFORMED)
        }
        val origin = json.optString("origin", "")
        val serviceToken = json.optString("serviceToken", "")
        val pairingToken = json.optString("pairingToken", "")
        val expiresAt = try {
            Instant.parse(json.optString("expiresAt", ""))
        } catch (_: Exception) {
            null
        }
        if (json.optInt("v", -1) != 1 || !SERVICE_RE.matches(serviceToken) || !PAIRING_RE.matches(pairingToken) || expiresAt == null) {
            return Result.Invalid(Problem.MALFORMED)
        }
        val accepted = when (policy) {
            is SitePolicy.Pinned -> policy.origin.takeIf { SyncOrigin.normalize(origin) == it && origin.trimEnd('/') == it }
                ?: return Result.Invalid(Problem.WRONG_SITE)
            // The Site writes its own origin exactly; anything else (trailing slash, case, port 443) is refused.
            else -> SyncOrigin.publicHttps(origin)?.takeIf { it == origin } ?: return Result.Invalid(Problem.UNSAFE_SITE)
        }
        if (!now.isBefore(expiresAt)) return Result.Invalid(Problem.EXPIRED)
        return Result.Ok(ConnectionCode(accepted, serviceToken, pairingToken, expiresAt))
    }
}

/**
 * What Connect does with a pasted code. Parsing is local; no request is made here. A pinned build
 * pairs right away (its origin is built in). A generic build must first show the hostname and get
 * an explicit Connect; cancelling sends nothing.
 */
object PairingFlow {
    sealed interface Step {
        data class Reject(val problem: ConnectionCodes.Problem) : Step
        data class Pair(val code: ConnectionCode) : Step
        data class Confirm(val code: ConnectionCode) : Step {
            val host: String get() = SyncOrigin.host(code.origin)
            /** Sites are normally served from *.chatgpt.site; anything else gets an extra warning. */
            val chatgptSite: Boolean get() = host.endsWith(".chatgpt.site")
        }
    }

    fun start(text: String, policy: SitePolicy, now: Instant): Step =
        when (val parsed = ConnectionCodes.parse(text, policy, now)) {
            is ConnectionCodes.Result.Invalid -> Step.Reject(parsed.problem)
            is ConnectionCodes.Result.Ok -> if (policy is SitePolicy.Pinned) Step.Pair(parsed.code) else Step.Confirm(parsed.code)
        }
}

/** Why the Site refused or failed a request, as far as the app needs to know. */
enum class Rejection {
    /** The one-time code was unknown, expired, or already used. */
    PAIRING_INVALID,
    /** The device credential was revoked (disconnected on the dashboard, re-paired, or data deleted). */
    DEVICE_REVOKED,
    /** The private Site's gate refused the service access key (HTTP 401/403 without an app code, or a redirect to sign-in). */
    ACCESS_DENIED,
    /** The Site already has newer data for these dates. */
    STALE,
    /** The Site rejected the request body. Retrying the same request will not help. */
    INVALID,
    /** Offline, timeout, rate limit, or server error. Worth retrying later. */
    TRANSIENT,
    UNEXPECTED,
}

/**
 * The owner's ChatGPT read-access switch as the Site reported it, and when the Site read it.
 * Never stored or shown without its timestamp, so an old value is never presented as current.
 */
data class SiteAccess(val enabled: Boolean, val checkedAt: Instant)

object SiteAccessJson {
    /** Null when the Site did not report the switch (an older Site version) or reported it without a valid time. */
    fun parse(json: JSONObject): SiteAccess? {
        val enabled = json.opt("chatgptAccessEnabled") as? Boolean ?: return null
        val checkedAt = instantOrNull(json, "checkedAt") ?: return null
        return SiteAccess(enabled, checkedAt)
    }

    fun instantOrNull(json: JSONObject, key: String): Instant? {
        val text = json.opt(key) as? String ?: return null
        return try {
            Instant.parse(text)
        } catch (_: Exception) {
            null
        }
    }
}

sealed interface ApiResult {
    data class Paired(val deviceId: String, val deviceToken: String, val deviceName: String) : ApiResult
    /** [access] is null when the Site did not report its ChatGPT access switch. */
    data class Uploaded(val duplicate: Boolean, val rangeStart: String, val rangeEnd: String, val access: SiteAccess? = null) : ApiResult
    /** GET /api/sync/status: the device is connected. [access] is null when the Site did not report it. */
    data class Status(val lastSuccessfulSyncAt: Instant?, val access: SiteAccess?) : ApiResult
    data object Disconnected : ApiResult
    data class Failed(val rejection: Rejection, val httpStatus: Int?) : ApiResult
}

object ApiResponses {
    fun classify(status: Int, body: String, headerCode: String? = null): Rejection {
        val code = headerCode?.takeIf { it.isNotEmpty() } ?: try {
            JSONObject(body).optString("code", "")
        } catch (_: Exception) {
            ""
        }
        return when {
            status in 300..399 -> Rejection.ACCESS_DENIED
            status == 401 && code == "device_revoked" -> Rejection.DEVICE_REVOKED
            status == 401 && code == "pairing_invalid" -> Rejection.PAIRING_INVALID
            status == 401 || status == 403 -> Rejection.ACCESS_DENIED
            status == 409 -> Rejection.STALE
            status == 400 || status == 413 || status == 415 || status == 422 -> Rejection.INVALID
            status == 408 || status == 425 || status == 429 || status in 500..599 -> Rejection.TRANSIENT
            else -> Rejection.UNEXPECTED
        }
    }
}

enum class SyncTrigger(val header: String) {
    SCHEDULED("scheduled"),
    MANUAL("manual");

    companion object {
        fun fromName(name: String?): SyncTrigger = entries.firstOrNull { it.name == name } ?: SCHEDULED
    }
}

/** Conditions that need the user's action; sync stops (no retry loop) until they are fixed. */
enum class PauseReason {
    NOT_PAIRED,
    DEVICE_REVOKED,
    SITE_ACCESS_DENIED,
    HEALTH_CONNECT_UNAVAILABLE,
    BACKGROUND_UNSUPPORTED,
    BACKGROUND_PERMISSION_MISSING,
    PERMISSION_MISSING,
    NO_CATEGORIES;

    /** Resolved automatically when the app is opened and the condition no longer holds. */
    val resumable: Boolean
        get() = this == HEALTH_CONNECT_UNAVAILABLE || this == BACKGROUND_PERMISSION_MISSING || this == PERMISSION_MISSING || this == NO_CATEGORIES
}

enum class FailReason(val retryable: Boolean) {
    OFFLINE_OR_SERVER(true),
    HEALTH_CONNECT_ERROR(true),
    STALE(false),
    REJECTED(false),
    TIMEZONE_UNSUPPORTED(false),
    OUT_OF_BOUNDS(false),
    READ_INTERRUPTED(false),
    UNEXPECTED(false),
}

sealed interface SyncOutcome {
    data class Success(
        val duplicate: Boolean,
        val rangeStart: String,
        val rangeEnd: String,
        val days: Int,
        /** ChatGPT access as reported in the upload response, when the Site reports it. */
        val access: SiteAccess? = null,
    ) : SyncOutcome
    data class Paused(val reason: PauseReason, val metrics: List<Metric> = emptyList()) : SyncOutcome
    data class Failed(val reason: FailReason) : SyncOutcome
    /** The app was disconnected or re-paired while this run was in progress; nothing was sent. */
    data object Superseded : SyncOutcome
}

object WorkerPolicy {
    /** Attempts per scheduled run (first try plus exponential-backoff retries) before waiting for the next period. */
    const val MAX_ATTEMPTS = 4

    enum class Action { SUCCESS, RETRY, FAILURE, PAUSE }

    fun decide(outcome: SyncOutcome, runAttemptCount: Int): Action = when (outcome) {
        is SyncOutcome.Success, SyncOutcome.Superseded -> Action.SUCCESS
        is SyncOutcome.Paused -> Action.PAUSE
        is SyncOutcome.Failed -> if (outcome.reason.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) Action.RETRY else Action.FAILURE
    }
}

/** Persisted result of the latest sync attempt (non-secret; health values are never stored here). */
data class SyncState(
    val lastSuccessAt: Instant? = null,
    val lastSuccessRange: String? = null,
    val lastAttemptAt: Instant? = null,
    val lastFailure: FailReason? = null,
    val willRetry: Boolean = false,
    val paused: PauseReason? = null,
)

/** What the sync card shows, independent of how it is rendered. */
enum class SyncPhase {
    NOT_CONFIGURED,
    NOT_PAIRED,
    AUTO_UNAVAILABLE,
    AUTO_OFF,
    PAUSED,
    RUNNING,
    WAITING_FOR_NETWORK,
    RETRYING,
    ERROR,
    SCHEDULED,
}

/** [oneTimeQueued]: a "Sync now" (or first) run is waiting to start, e.g. for a network or backoff. */
data class WorkSnapshot(val periodicEnqueued: Boolean, val running: Boolean, val nextRunAt: Instant?, val oneTimeQueued: Boolean = false)

/** Why the latest connection-status check did not produce a current answer. */
enum class StatusCheckFailure {
    /** No network, timeout, rate limit, or server error. */
    OFFLINE,
    /** The Site does not report ChatGPT access yet (it runs an older version). */
    SITE_OUTDATED,
    /** The private Site's gate refused the access key. */
    ACCESS_DENIED,
    UNEXPECTED,
}

/** What the phone last learned from the Site about its connection (non-secret, no health data). */
data class SiteStatus(
    val access: SiteAccess? = null,
    /** This device's last successful sync as recorded by the Site. */
    val siteLastSyncAt: Instant? = null,
    /** When the phone last tried to check, successful or not. */
    val lastCheckAt: Instant? = null,
    /** Set when the latest check failed; [access] is then the last known value, not a current one. */
    val lastCheckFailure: StatusCheckFailure? = null,
)

object SyncStatusModel {
    fun phase(
        siteConfigured: Boolean,
        paired: Boolean,
        backgroundSupported: Boolean,
        autoSyncOn: Boolean,
        state: SyncState,
        work: WorkSnapshot,
        online: Boolean,
    ): SyncPhase = when {
        !siteConfigured -> SyncPhase.NOT_CONFIGURED
        !paired -> SyncPhase.NOT_PAIRED
        work.running -> SyncPhase.RUNNING
        state.paused != null -> SyncPhase.PAUSED
        !backgroundSupported -> SyncPhase.AUTO_UNAVAILABLE
        !autoSyncOn -> SyncPhase.AUTO_OFF
        !online -> SyncPhase.WAITING_FOR_NETWORK
        state.lastFailure != null && state.willRetry -> SyncPhase.RETRYING
        state.lastFailure != null -> SyncPhase.ERROR
        else -> SyncPhase.SCHEDULED
    }
}
