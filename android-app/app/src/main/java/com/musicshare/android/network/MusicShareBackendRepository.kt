package com.musicshare.android.network

import android.content.Context
import android.net.Uri
import com.musicshare.android.data.AppStateStore
import com.musicshare.android.data.PersistedAppState
import com.musicshare.android.data.SessionSnapshot
import com.musicshare.android.util.UserVisibleException
import com.musicshare.android.util.normalizeBaseUrl
import com.musicshare.android.util.nowIso
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class MusicShareBackendRepository(
    private val context: Context,
    private val stateStore: AppStateStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val httpClient = OkHttpClient.Builder().build()

    suspend fun ensureSession(preferAdmin: Boolean = false): SessionSnapshot {
        val current = stateStore.read()
        if (current.server.authMode != "basic") {
            return current.session
        }
        if (current.session.isValid() && (!preferAdmin || current.session.role == "admin")) {
            return current.session
        }
        val password = resolvePassword(current, preferAdmin)
        stateStore.update {
            it.copy(
                authLog = it.authLog.copy(lastAuthRequestedAt = nowIso()),
            )
        }
        val response = executeLogin(current, password)
        val session = SessionSnapshot(
            authType = response.authType,
            accessKey = response.sessionKey,
            expiresAt = response.expiresAt,
            role = response.role,
        )
        stateStore.update {
            it.copy(
                session = session,
                authLog = it.authLog.copy(
                    lastAuthRequestedAt = nowIso(),
                    lastAuthSucceededAt = nowIso(),
                    lastRole = response.role,
                    lastAuthType = response.authType,
                    lastExpiresAt = response.expiresAt,
                ),
            )
        }
        return session
    }

    suspend fun upload(preparedUpload: PreparedUpload): ShareItemDto =
        withAuthorizedSession { state, session ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "file",
                    filename = preparedUpload.audioFile.name,
                    body = preparedUpload.audioFile.asRequestBody(preparedUpload.audioMimeType.toMediaType()),
                )
                .addFormDataPart("title", preparedUpload.title)
                .addFormDataPart("artist", preparedUpload.artist)
                .addFormDataPart("album", preparedUpload.album)
                .addFormDataPart("duration_ms", preparedUpload.durationMs.toString())
                .addFormDataPart("audio_mime", preparedUpload.audioMimeType)
                .addFormDataPart("client_created_at", preparedUpload.clientCreatedAt)
                .addFormDataPart("expire_after_seconds", preparedUpload.expireAfterSeconds.toString())
            if (preparedUpload.coverFile != null && preparedUpload.coverMimeType != null) {
                body.addFormDataPart(
                    name = "cover",
                    filename = preparedUpload.coverFile.name,
                    body = preparedUpload.coverFile.asRequestBody(preparedUpload.coverMimeType.toMediaType()),
                )
            }
            executeJson(
                request = Request.Builder()
                    .url(resolveBaseUrl(state).newBuilder().addPathSegment("upload").build())
                    .header("X-Client-Install-Id", state.clientInstallId)
                    .applySession(session)
                    .post(body.build())
                    .build(),
            )
        }

    suspend fun listClientShares(): List<ShareItemDto> =
        withAuthorizedSession { state, session ->
            executeJson<ShareListResponse>(
                Request.Builder()
                    .url(resolveBaseUrl(state).newBuilder().addPathSegments("client/shares").build())
                    .header("X-Client-Install-Id", state.clientInstallId)
                    .applySession(session)
                    .get()
                    .build(),
            ).items
        }

    suspend fun terminateClientShare(shareCode: String): ShareItemDto =
        withAuthorizedSession { state, session ->
            executeJson(
                Request.Builder()
                    .url(
                        resolveBaseUrl(state).newBuilder()
                            .addPathSegments("client/shares")
                            .addPathSegment(shareCode)
                            .addPathSegment("terminate")
                            .build(),
                    )
                    .header("X-Client-Install-Id", state.clientInstallId)
                    .applySession(session)
                    .post("".toRequestBody())
                    .build(),
            )
        }

    suspend fun listAdminTracks(): List<ShareItemDto> =
        withAuthorizedSession(preferAdmin = true) { state, session ->
            executeJson<ShareListResponse>(
                Request.Builder()
                    .url(resolveBaseUrl(state).newBuilder().addPathSegments("admin/tracks").build())
                    .applySession(session)
                    .get()
                    .build(),
            ).items
        }

    suspend fun terminateAdminShare(shareCode: String): ShareItemDto =
        withAuthorizedSession(preferAdmin = true) { state, session ->
            executeJson(
                Request.Builder()
                    .url(
                        resolveBaseUrl(state).newBuilder()
                            .addPathSegments("admin/tracks")
                            .addPathSegment(shareCode)
                            .addPathSegment("terminate")
                            .build(),
                    )
                    .applySession(session)
                    .post("".toRequestBody())
                    .build(),
            )
        }

    suspend fun getAdminBackground(): AdminBackgroundDto =
        withAuthorizedSession(preferAdmin = true) { state, session ->
            executeJson(
                Request.Builder()
                    .url(resolveBaseUrl(state).newBuilder().addPathSegments("admin/background").build())
                    .applySession(session)
                    .get()
                    .build(),
            )
        }

    suspend fun uploadAdminBackground(imageUri: Uri): AdminBackgroundDto =
        withAuthorizedSession(preferAdmin = true) { state, session ->
            val staged = stageImageUpload(imageUri)
            try {
                executeJson(
                    Request.Builder()
                        .url(
                            resolveBaseUrl(state).newBuilder()
                                .addPathSegments("admin/background")
                                .build(),
                        )
                        .applySession(session)
                        .post(
                            MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart(
                                    name = "background",
                                    filename = staged.file.name,
                                    body = staged.file.asRequestBody(staged.mimeType.toMediaType()),
                                )
                                .build(),
                        )
                        .build(),
                )
            } finally {
                staged.file.delete()
            }
        }

    suspend fun clearSession() {
        stateStore.update {
            it.copy(session = SessionSnapshot())
        }
    }

    private suspend fun executeLogin(state: PersistedAppState, password: String): LoginResponse {
        val requestBody = json.encodeToString(LoginRequest.serializer(), LoginRequest(password))
            .toRequestBody("application/json".toMediaType())
        return executeJson(
            Request.Builder()
                .url(resolveBaseUrl(state).newBuilder().addPathSegments("auth/login").build())
                .post(requestBody)
                .build(),
        )
    }

    private suspend fun <T> withAuthorizedSession(
        preferAdmin: Boolean = false,
        block: suspend (PersistedAppState, SessionSnapshot) -> T,
    ): T {
        val initialState = stateStore.read()
        val initialSession = if (initialState.server.authMode == "basic") {
            ensureSession(preferAdmin)
        } else {
            initialState.session
        }
        return try {
            block(stateStore.read(), initialSession)
        } catch (error: SessionExpiredException) {
            clearSession()
            val refreshed = ensureSession(preferAdmin)
            block(stateStore.read(), refreshed)
        }
    }

    private suspend inline fun <reified T> executeJson(request: Request): T = withContext(Dispatchers.IO) {
        val response = runCatching { httpClient.newCall(request).execute() }.getOrElse { error ->
            throw mapCallError(error, request.url)
        }
        response.use { call ->
            val bodyText = call.body?.string().orEmpty()
            if (!call.isSuccessful) {
                if (call.code == 401) {
                    throw SessionExpiredException()
                }
                throw UserVisibleException(parseErrorMessage(bodyText) ?: "请求失败: HTTP ${call.code}")
            }
            runCatching { json.decodeFromString<T>(bodyText) }.getOrElse {
                throw UserVisibleException("服务端返回了无法解析的数据。")
            }
        }
    }

    private fun Request.Builder.applySession(session: SessionSnapshot): Request.Builder {
        if (session.accessKey.isNotBlank()) {
            header("X-Session-Key", session.accessKey)
        }
        return this
    }

    private fun resolvePassword(state: PersistedAppState, preferAdmin: Boolean): String {
        if (preferAdmin && state.admin.enabled && state.admin.password.isNotBlank()) {
            return state.admin.password
        }
        if (state.server.basicAuthPassword.isNotBlank()) {
            return state.server.basicAuthPassword
        }
        if (state.admin.enabled && state.admin.password.isNotBlank()) {
            return state.admin.password
        }
        throw UserVisibleException("未配置可用的认证密码。")
    }

    private fun resolveBaseUrl(state: PersistedAppState): HttpUrl {
        val normalized = normalizeBaseUrl(state.server.baseUrl)
        if (normalized.isBlank()) {
            throw UserVisibleException("请先配置后端 base_url。")
        }
        return normalized.toHttpUrlOrNull()
            ?: throw UserVisibleException("base_url 格式无效。")
    }

    private fun parseErrorMessage(bodyText: String): String? {
        return runCatching {
            json.parseToJsonElement(bodyText).jsonObject["detail"]?.toString()?.trim('"')
        }.getOrNull()
    }

    private fun stageImageUpload(imageUri: Uri): StagedImageUpload {
        val mimeType = context.contentResolver.getType(imageUri)?.lowercase(Locale.US)
            ?.takeIf { it == "image/jpeg" || it == "image/png" || it == "image/webp" }
            ?: throw UserVisibleException("仅支持 JPEG、PNG 或 WebP 图片。")
        val suffix = when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            else -> ".webp"
        }
        val file = File.createTempFile("music-share-background-${UUID.randomUUID()}", suffix, context.cacheDir)
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        } ?: throw UserVisibleException("无法读取所选图片。")
        return StagedImageUpload(file = file, mimeType = mimeType)
    }

    private fun mapCallError(error: Throwable, requestUrl: HttpUrl): UserVisibleException {
        return when (error) {
            is UserVisibleException -> error
            is IOException -> UserVisibleException(buildNetworkErrorMessage(error, requestUrl))
            else -> UserVisibleException(error.message ?: "请求失败。")
        }
    }

    private fun buildNetworkErrorMessage(error: IOException, requestUrl: HttpUrl): String {
        val rawMessage = error.message?.trim().takeUnless { it.isNullOrBlank() } ?: "无法连接后端"
        val hint = buildNetworkHint(error, requestUrl)
        return if (hint == null) {
            "网络请求失败：$rawMessage"
        } else {
            "网络请求失败：$rawMessage。$hint"
        }
    }

    private fun buildNetworkHint(error: IOException, requestUrl: HttpUrl): String? {
        if (!requestUrl.isHttps) {
            return null
        }
        val diagnostic = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase(Locale.US)
        val looksLikeClosedOrTlsFailure = listOf(
            "connection closed",
            "unexpected end of stream",
            "connection reset",
            "broken pipe",
            "ssl",
            "tls",
            "handshake",
            "protocol exception",
        ).any { it in diagnostic }
        if (!looksLikeClosedOrTlsFailure) {
            return null
        }
        return "如果后端是直接运行在 ${requestUrl.port} 端口的 HTTP 服务，请把 base_url 显式写成 http://${requestUrl.host}；只有挂了 HTTPS 反向代理或证书时才用 https://。"
    }

    private class SessionExpiredException : IOException()

    private data class StagedImageUpload(
        val file: File,
        val mimeType: String,
    )
}
