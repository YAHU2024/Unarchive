package com.unarchive.android.security

import android.content.Context
import com.unarchive.android.analyzer.ApiKeyStore
import com.unarchive.android.asr.SiliconFlowKeyStore
import com.unarchive.android.sync.ImaCredentialStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.EnumMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The individual secrets which can be edited from the security settings. */
enum class SecureCredential(val displayLabel: String) {
    DEEPSEEK_API_KEY("DeepSeek API Key"),
    SILICONFLOW_API_KEY("SiliconFlow API Key"),
    IMA_CLIENT_ID("ima Client ID"),
    IMA_API_KEY("ima API Key");

    val profile: SecurityProfile
        get() = when (this) {
            DEEPSEEK_API_KEY -> SecurityProfile.DEEPSEEK
            SILICONFLOW_API_KEY -> SecurityProfile.SILICONFLOW
            IMA_CLIENT_ID, IMA_API_KEY -> SecurityProfile.IMA
        }
}

/** A connection target and the credentials which must be present for it. */
enum class SecurityProfile(val displayLabel: String) {
    DEEPSEEK("DeepSeek"),
    SILICONFLOW("SiliconFlow"),
    IMA("ima");

    val credentials: List<SecureCredential>
        get() = when (this) {
            DEEPSEEK -> listOf(SecureCredential.DEEPSEEK_API_KEY)
            SILICONFLOW -> listOf(SecureCredential.SILICONFLOW_API_KEY)
            IMA -> listOf(SecureCredential.IMA_CLIENT_ID, SecureCredential.IMA_API_KEY)
        }
}

/** Only presence and transient-edit flags are exposed; the value is never kept here. */
data class CredentialPresence(
    val configured: Boolean = false,
    val hasPendingInput: Boolean = false,
)

/** Fixed failure categories keep provider exception text out of the UI and logs. */
enum class ConnectionFailureReason {
    UNAUTHORIZED,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    INVALID_RESPONSE,
    UNKNOWN,
}

sealed interface ConnectionCheckResult {
    data object Success : ConnectionCheckResult
    data class Failure(val reason: ConnectionFailureReason) : ConnectionCheckResult
}

/**
 * A checker is built by the integration layer with the currently configured
 * provider client. It deliberately has no credential argument: this keeps
 * secret values out of the settings state and out of UI callbacks.
 */
fun interface CredentialConnectionChecker {
    suspend fun check(): ConnectionCheckResult
}

enum class ConnectionStatus {
    IDLE,
    CHECKING,
    CONNECTED,
    MISSING_CREDENTIAL,
    CHECKER_UNAVAILABLE,
    UNAUTHORIZED,
    RATE_LIMITED,
    NETWORK_ERROR,
    SERVER_ERROR,
    INVALID_RESPONSE,
    UNKNOWN_ERROR,
}

/** Immutable state safe to expose to Compose. It contains no secret text. */
data class SecuritySettingsState(
    val credentials: Map<SecureCredential, CredentialPresence> =
        SecureCredential.entries.associateWith { CredentialPresence() },
    val connection: Map<SecurityProfile, ConnectionStatus> =
        SecurityProfile.entries.associateWith { ConnectionStatus.IDLE },
    val pendingClear: SecurityProfile? = null,
    val notice: SecurityNotice? = null,
) {
    fun presence(credential: SecureCredential): CredentialPresence =
        credentials[credential] ?: CredentialPresence()

    fun isConfigured(profile: SecurityProfile): Boolean =
        profile.credentials.all { presence(it).configured }
}

/** Safe fixed copy for a connection row; no provider response is interpolated. */
fun ConnectionStatus.safeMessage(): String = when (this) {
    ConnectionStatus.IDLE -> "尚未检查连接"
    ConnectionStatus.CHECKING -> "正在检查连接"
    ConnectionStatus.CONNECTED -> "连接成功"
    ConnectionStatus.MISSING_CREDENTIAL -> "请先配置凭据"
    ConnectionStatus.CHECKER_UNAVAILABLE -> "暂不支持连接检查"
    ConnectionStatus.UNAUTHORIZED -> "凭据无效或已失效"
    ConnectionStatus.RATE_LIMITED -> "请求过于频繁，请稍后重试"
    ConnectionStatus.NETWORK_ERROR -> "网络连接失败，请检查网络后重试"
    ConnectionStatus.SERVER_ERROR -> "服务暂时不可用，请稍后重试"
    ConnectionStatus.INVALID_RESPONSE -> "服务响应无法识别"
    ConnectionStatus.UNKNOWN_ERROR -> "连接检查失败，请稍后重试"
}

/** User-facing actions are represented by safe categories, never provider text. */
enum class SecurityNotice {
    CREDENTIAL_SAVED,
    CLEAR_CONFIRMATION_REQUIRED,
    CREDENTIALS_CLEARED,
    SAVE_FAILED,
    CLEAR_FAILED,
    CONNECTION_SUCCEEDED,
    CREDENTIAL_REQUIRED,
    CONNECTION_CHECKER_UNAVAILABLE,
    CONNECTION_FAILED,
}

enum class CredentialInputError {
    EMPTY,
    TOO_LONG,
    WHITESPACE,
    CONTROL_CHARACTER,
}

sealed interface CredentialMutationResult {
    data class Saved(val credential: SecureCredential) : CredentialMutationResult
    data class Rejected(val error: CredentialInputError) : CredentialMutationResult
    data class Failed(val credential: SecureCredential) : CredentialMutationResult
}

sealed interface ClearResult {
    data class ConfirmationRequired(val profile: SecurityProfile) : ClearResult
    data class Cleared(val profile: SecurityProfile) : ClearResult
    data class Failed(val profile: SecurityProfile) : ClearResult
    data object NothingToClear : ClearResult
}

/**
 * Presence-only credential boundary. Implementations may read a secret in
 * order to answer [isConfigured], but must never return it to the caller.
 */
interface CredentialVault {
    fun isConfigured(credential: SecureCredential): Boolean
    fun save(credential: SecureCredential, value: String)
    fun clear(profile: SecurityProfile)
}

/**
 * Android adapter for the existing encrypted stores. Migration remains owned
 * by each store; this adapter only reports presence and delegates mutations.
 */
class AndroidCredentialVault(context: Context) : CredentialVault {
    private val deepSeek = ApiKeyStore(context)
    private val siliconFlow = SiliconFlowKeyStore(context)
    private val ima = ImaCredentialStore(context)

    override fun isConfigured(credential: SecureCredential): Boolean = when (credential) {
        SecureCredential.DEEPSEEK_API_KEY -> deepSeek.get().isPresent()
        SecureCredential.SILICONFLOW_API_KEY -> siliconFlow.get().isPresent()
        SecureCredential.IMA_CLIENT_ID -> ima.clientId().isPresent()
        SecureCredential.IMA_API_KEY -> ima.apiKey().isPresent()
    }

    override fun save(credential: SecureCredential, value: String) {
        when (credential) {
            SecureCredential.DEEPSEEK_API_KEY -> deepSeek.save(value)
            SecureCredential.SILICONFLOW_API_KEY -> siliconFlow.save(value)
            SecureCredential.IMA_CLIENT_ID -> ima.saveClientId(value)
            SecureCredential.IMA_API_KEY -> ima.saveApiKey(value)
        }
    }

    override fun clear(profile: SecurityProfile) {
        when (profile) {
            SecurityProfile.DEEPSEEK -> deepSeek.clear()
            SecurityProfile.SILICONFLOW -> siliconFlow.clear()
            SecurityProfile.IMA -> ima.clear()
        }
    }

    private fun String?.isPresent(): Boolean = !isNullOrBlank()
}

/** Optional provider-specific connection check supplied by the VM integration. */
fun interface CredentialConnectionCheckerProvider {
    fun checkerFor(profile: SecurityProfile): CredentialConnectionChecker?
}

/** A map-backed provider is convenient for production wiring and JVM tests. */
class MapCredentialConnectionCheckerProvider(
    checkers: Map<SecurityProfile, CredentialConnectionChecker>,
) : CredentialConnectionCheckerProvider {
    private val checkers = checkers.toMap()

    override fun checkerFor(profile: SecurityProfile): CredentialConnectionChecker? = checkers[profile]
}

/** Read-only authentication probe for OpenAI-compatible provider endpoints. */
class HttpApiConnectionChecker(
    private val apiKey: () -> String?,
    private val endpoint: String,
) : CredentialConnectionChecker {
    override suspend fun check(): ConnectionCheckResult = withContext(Dispatchers.IO) {
        val key = apiKey()?.trim().orEmpty()
        if (key.isBlank()) return@withContext ConnectionCheckResult.Failure(ConnectionFailureReason.UNKNOWN)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $key")
            val status = connection.responseCode
            when {
                status in 200..299 -> ConnectionCheckResult.Success
                status == 401 || status == 403 -> ConnectionCheckResult.Failure(ConnectionFailureReason.UNAUTHORIZED)
                status == 429 -> ConnectionCheckResult.Failure(ConnectionFailureReason.RATE_LIMITED)
                status >= 500 -> ConnectionCheckResult.Failure(ConnectionFailureReason.SERVER)
                else -> ConnectionCheckResult.Failure(ConnectionFailureReason.INVALID_RESPONSE)
            }
        } catch (error: IOException) {
            ConnectionCheckResult.Failure(classifyConnectionFailure(error))
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Presence-only settings controller. It owns confirmation and check state but
 * never stores an editable secret. Callers should keep text-field contents in
 * their local input state and pass it only to [saveCredential].
 */
class SecuritySettingsController(
    private val vault: CredentialVault,
    private val checkerProvider: CredentialConnectionCheckerProvider =
        CredentialConnectionCheckerProvider { null },
    loadOnInit: Boolean = true,
) {
    private val _state = MutableStateFlow(SecuritySettingsState())
    val state: StateFlow<SecuritySettingsState> = _state.asStateFlow()
    private val checkGenerations = EnumMap<SecurityProfile, Long>(SecurityProfile::class.java)

    init {
        if (loadOnInit) reload()
    }

    /** Refreshes presence flags after process restart or an external mutation. */
    fun reload(): SecuritySettingsState {
        invalidateChecks()
        val current = _state.value
        return publish(current.copy(
            credentials = readPresence(),
            connection = SecurityProfile.entries.associateWith { ConnectionStatus.IDLE },
            pendingClear = null,
            notice = null,
        ))
    }

    /** Records only whether a user has typed something, not what they typed. */
    fun setInputPresence(credential: SecureCredential, hasInput: Boolean): SecuritySettingsState {
        val next = _state.value.credentials.toMutableMap()
        val prior = next[credential] ?: CredentialPresence()
        next[credential] = prior.copy(hasPendingInput = hasInput)
        return publish(_state.value.copy(credentials = next, notice = null))
    }

    /**
     * Validates and saves one value. The value is not retained in the result,
     * state, notice, exception text, or log message.
     */
    fun saveCredential(credential: SecureCredential, value: String): CredentialMutationResult {
        val error = validateCredentialInput(value)
        if (error != null) {
            setNotice(SecurityNotice.SAVE_FAILED)
            return CredentialMutationResult.Rejected(error)
        }
        return runCatching {
            invalidateCheck(credential.profile)
            vault.save(credential, value.trim())
            val next = _state.value.credentials.toMutableMap()
            next[credential] = CredentialPresence(configured = true)
            val connection = _state.value.connection.toMutableMap()
            connection[credential.profile] = ConnectionStatus.IDLE
            publish(_state.value.copy(
                credentials = next,
                connection = connection,
                notice = SecurityNotice.CREDENTIAL_SAVED,
            ))
            CredentialMutationResult.Saved(credential)
        }.getOrElse {
            setNotice(SecurityNotice.SAVE_FAILED)
            CredentialMutationResult.Failed(credential)
        }
    }

    /** Starts the explicit confirmation step; no mutation happens here. */
    fun requestClear(profile: SecurityProfile): ClearResult {
        invalidateCheck(profile)
        publish(_state.value.copy(
            pendingClear = profile,
            notice = SecurityNotice.CLEAR_CONFIRMATION_REQUIRED,
        ))
        return ClearResult.ConfirmationRequired(profile)
    }

    fun cancelClear(): SecuritySettingsState = publish(_state.value.copy(
        pendingClear = null,
        notice = null,
    ))

    /** Clears the whole profile only after [requestClear] has been called. */
    fun confirmClear(): ClearResult {
        val profile = _state.value.pendingClear ?: return ClearResult.NothingToClear
        invalidateCheck(profile)
        return runCatching {
            vault.clear(profile)
            val next = readPresence()
            val connection = _state.value.connection.toMutableMap()
            connection[profile] = ConnectionStatus.IDLE
            publish(_state.value.copy(
                credentials = next,
                connection = connection,
                pendingClear = null,
                notice = SecurityNotice.CREDENTIALS_CLEARED,
            ))
            ClearResult.Cleared(profile)
        }.getOrElse {
            // Refresh after partial failures so presence reflects what the
            // vault actually removed, while keeping confirmation available.
            publish(_state.value.copy(
                credentials = readPresence(),
                pendingClear = profile,
                notice = SecurityNotice.CLEAR_FAILED,
            ))
            ClearResult.Failed(profile)
        }
    }

    /** Runs a target check without exposing credentials or provider errors. */
    suspend fun checkConnection(profile: SecurityProfile): SecuritySettingsState {
        val generation = beginCheck(profile)
        val initial = _state.value
        val connection = initial.connection.toMutableMap()
        connection[profile] = ConnectionStatus.CHECKING
        publish(initial.copy(connection = connection, notice = null))

        if (!profile.credentials.all { runCatching { vault.isConfigured(it) }.getOrDefault(false) }) {
            return publishIfCurrent(profile, generation, _state.value.copy(
                connection = _state.value.connection + (profile to ConnectionStatus.MISSING_CREDENTIAL),
                notice = SecurityNotice.CREDENTIAL_REQUIRED,
            ))
        }
        val checker = checkerProvider.checkerFor(profile)
        if (checker == null) {
            return publishIfCurrent(profile, generation, _state.value.copy(
                connection = _state.value.connection + (profile to ConnectionStatus.CHECKER_UNAVAILABLE),
                notice = SecurityNotice.CONNECTION_CHECKER_UNAVAILABLE,
            ))
        }
        return try {
            when (val result = checker.check()) {
                ConnectionCheckResult.Success -> publishIfCurrent(profile, generation, _state.value.copy(
                    connection = _state.value.connection + (profile to ConnectionStatus.CONNECTED),
                    notice = SecurityNotice.CONNECTION_SUCCEEDED,
                ))
                is ConnectionCheckResult.Failure -> publishIfCurrent(profile, generation, _state.value.copy(
                    connection = _state.value.connection + (profile to result.reason.toStatus()),
                    notice = SecurityNotice.CONNECTION_FAILED,
                ))
            }
        } catch (error: CancellationException) {
            publishIfCurrent(profile, generation, _state.value.copy(
                connection = _state.value.connection + (profile to ConnectionStatus.IDLE),
                notice = null,
            ))
            throw error
        } catch (_: Throwable) {
            publishIfCurrent(profile, generation, _state.value.copy(
                connection = _state.value.connection + (profile to ConnectionStatus.UNKNOWN_ERROR),
                notice = SecurityNotice.CONNECTION_FAILED,
            ))
        }
    }

    private fun setNotice(notice: SecurityNotice) {
        publish(_state.value.copy(notice = notice))
    }

    private fun readPresence(): Map<SecureCredential, CredentialPresence> =
        EnumMap<SecureCredential, CredentialPresence>(SecureCredential::class.java).also { presence ->
            SecureCredential.entries.forEach { credential ->
                presence[credential] = CredentialPresence(configured = runCatching {
                    vault.isConfigured(credential)
                }.getOrDefault(false))
            }
        }

    private fun beginCheck(profile: SecurityProfile): Long {
        val next = (checkGenerations[profile] ?: 0L) + 1L
        checkGenerations[profile] = next
        return next
    }

    private fun invalidateCheck(profile: SecurityProfile) {
        checkGenerations[profile] = (checkGenerations[profile] ?: 0L) + 1L
    }

    private fun invalidateChecks() {
        SecurityProfile.entries.forEach(::invalidateCheck)
    }

    private fun publishIfCurrent(
        profile: SecurityProfile,
        generation: Long,
        next: SecuritySettingsState,
    ): SecuritySettingsState = if (checkGenerations[profile] == generation) publish(next) else _state.value

    private fun publish(next: SecuritySettingsState): SecuritySettingsState {
        _state.value = next
        return next
    }

    private fun ConnectionFailureReason.toStatus(): ConnectionStatus = when (this) {
        ConnectionFailureReason.UNAUTHORIZED -> ConnectionStatus.UNAUTHORIZED
        ConnectionFailureReason.RATE_LIMITED -> ConnectionStatus.RATE_LIMITED
        ConnectionFailureReason.NETWORK -> ConnectionStatus.NETWORK_ERROR
        ConnectionFailureReason.SERVER -> ConnectionStatus.SERVER_ERROR
        ConnectionFailureReason.INVALID_RESPONSE -> ConnectionStatus.INVALID_RESPONSE
        ConnectionFailureReason.UNKNOWN -> ConnectionStatus.UNKNOWN_ERROR
    }
}

/** Input validation intentionally returns only a category, never the rejected value. */
fun validateCredentialInput(value: String): CredentialInputError? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return CredentialInputError.EMPTY
    if (trimmed.length > MAX_CREDENTIAL_LENGTH) return CredentialInputError.TOO_LONG
    if (trimmed.any { it.isISOControl() }) return CredentialInputError.CONTROL_CHARACTER
    if (trimmed.any { it.isWhitespace() }) return CredentialInputError.WHITESPACE
    return null
}

/** Converts provider exceptions to a fixed safe category for a checker adapter. */
fun classifyConnectionFailure(error: Throwable): ConnectionFailureReason {
    val message = error.message.orEmpty().lowercase()
    return when {
        "401" in message || "403" in message || "unauthorized" in message || "forbidden" in message ->
            ConnectionFailureReason.UNAUTHORIZED
        "429" in message || "rate" in message || "频率" in message ->
            ConnectionFailureReason.RATE_LIMITED
        "json" in message || "response" in message || "响应" in message ->
            ConnectionFailureReason.INVALID_RESPONSE
        "500" in message || "502" in message || "503" in message || "server" in message || "服务" in message ->
            ConnectionFailureReason.SERVER
        error is IOException || "timeout" in message || "timed out" in message || "网络" in message ->
            ConnectionFailureReason.NETWORK
        else -> ConnectionFailureReason.UNKNOWN
    }
}

private const val MAX_CREDENTIAL_LENGTH = 4_096
