package au.com.shiftyjelly.pocketcasts.servers.sync

import android.content.Context
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTrackerWrapper
import au.com.shiftyjelly.pocketcasts.localization.R
import au.com.shiftyjelly.pocketcasts.models.entity.Folder
import au.com.shiftyjelly.pocketcasts.models.entity.Playlist
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode
import au.com.shiftyjelly.pocketcasts.models.to.HistorySyncRequest
import au.com.shiftyjelly.pocketcasts.models.to.HistorySyncResponse
import au.com.shiftyjelly.pocketcasts.models.to.StatsBundle
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServerCache
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServerRetrofit
import au.com.shiftyjelly.pocketcasts.utils.extensions.parseIsoDate
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class SyncServerManager @Inject constructor(
    @SyncServerRetrofit retrofit: Retrofit,
    val settings: Settings,
    @SyncServerCache val cache: Cache,
    private val analyticsTracker: AnalyticsTrackerWrapper,
    @ApplicationContext private val context: Context
) {

    val server = retrofit.create(SyncServer::class.java)

    companion object {
        const val SCOPE = "mobile"
    }

    sealed class SyncServerResponse<T>(val data: T?, val message: String?) {
        class Success<T>(data: T) : SyncServerResponse<T>(data, null)
        class Error<T>(message: String) : SyncServerResponse<T>(null, message)
    }

    suspend fun userRegister(email: String, password: String): SyncServerResponse<UserRegisterResponse> {
        return try {
            val userResponse = server.userRegister(UserRegisterRequest(email = email, password = password, scope = SCOPE))
            SyncServerResponse.Success(userResponse)
        } catch (e: Exception) {
            val errorMessage = (e as? HttpException)?.parseErrorMessageLocalized(context) ?: context.resources.getString(R.string.error_login_failed)
            SyncServerResponse.Error(errorMessage)
        }
    }

    suspend fun loginPocketCasts(email: String, password: String): LoginResponse {
        val request = LoginRequest(email = email, password = password)
        return server.loginPocketCasts(request)
    }

    suspend fun tokenUsingAuthorizationCode(code: String, clientId: String): TokenResponse {
        return server.loginToken(
            request = TokenRequest.buildAuthorizationRequest(code = code, clientId = clientId)
        )
    }

    suspend fun tokenUsingRefreshToken(refreshToken: String, clientId: String): TokenResponse {
        return server.loginToken(
            request = TokenRequest.buildRefreshRequest(refreshToken = refreshToken, clientId = clientId)
        )
    }

    suspend fun userUuid(): String {
        val accessToken = settings.getSyncTokenSuspend() ?: throw SecurityException("Access token could not available.")
        return server.userId(addBearer(accessToken)).userUuid
    }

    fun emailChange(newEmail: String, password: String): Single<UserChangeResponse> {
        return getCacheTokenOrLogin { token ->
            val request = EmailChangeRequest(
                newEmail,
                password,
                "mobile"
            )
            server.emailChange(addBearer(token), request)
        }.doOnSuccess {
            if (it.success == true) {
                analyticsTracker.track(AnalyticsEvent.USER_EMAIL_UPDATED)
            }
        }
    }

    fun deleteAccount(): Single<UserChangeResponse> =
        getCacheTokenOrLogin { token ->
            server.deleteAccount(addBearer(token))
        }.doOnSuccess {
            if (it.success == true) {
                analyticsTracker.track(AnalyticsEvent.USER_ACCOUNT_DELETED)
            }
        }

    fun pwdChange(pwdNew: String, pwdOld: String): Single<PwdChangeResponse> {
        return getCacheTokenOrLogin { token ->
            val request = PwdChangeRequest(pwdNew, pwdOld, "mobile")
            server.pwdChange(addBearer(token), request)
        }.doOnSuccess {
            if (it.success == true) {
                analyticsTracker.track(AnalyticsEvent.USER_PASSWORD_UPDATED)
            }
        }
    }

    fun redeemPromoCode(code: String): Single<PromoCodeResponse> {
        return getCacheTokenOrLogin { token ->
            val request = PromoCodeRequest(code)
            server.redeemPromoCode(addBearer(token), request)
        }
    }

    fun validatePromoCode(code: String): Single<PromoCodeResponse> {
        return server.validatePromoCode(PromoCodeRequest(code))
    }

    suspend fun namedSettings(request: NamedSettingsRequest): NamedSettingsResponse {
        return getCacheTokenOrLoginSuspend { token ->
            server.namedSettings(addBearer(token), request)
        }
    }

    fun upNextSync(request: UpNextSyncRequest): Single<UpNextSyncResponse> {
        return getCacheTokenOrLogin { token ->
            server.upNextSync(addBearer(token), request)
        }
    }

    fun getLastSyncAt(): Single<String> {
        return getCacheTokenOrLogin<String> { token ->
            server.getLastSyncAt(addBearer(token), buildBasicRequest())
                .map { response -> response.lastSyncAt ?: "" }
        }
    }

    fun getHomeFolder(): Single<PodcastListResponse> {
        return getCacheTokenOrLogin { token ->
            server.getPodcastList(addBearer(token), buildBasicRequest()).map { response ->
                response.copy(podcasts = removeHomeFolderUuid(response.podcasts), folders = response.folders)
            }
        }
    }

    private fun removeHomeFolderUuid(podcasts: List<PodcastResponse>?): List<PodcastResponse>? {
        return podcasts?.map { podcast ->
            if (podcast.folderUuid != null && podcast.folderUuid == Folder.homeFolderUuid) {
                podcast.copy(folderUuid = null)
            } else {
                podcast
            }
        }
    }

    fun getPodcastEpisodes(podcastUuid: String): Single<PodcastEpisodesResponse> {
        return getCacheTokenOrLogin { token ->
            val request = PodcastEpisodesRequest(podcastUuid)
            server.getPodcastEpisodes(addBearer(token), request)
        }
    }

    fun getFilters(): Single<List<Playlist>> {
        return getCacheTokenOrLogin<List<Playlist>> { token ->
            server.getFilterList(addBearer(token), buildBasicRequest())
                .map { response -> response.filters?.mapNotNull { it.toFilter() } ?: emptyList() }
        }
    }

    fun historySync(request: HistorySyncRequest): Single<HistorySyncResponse> {
        return getCacheTokenOrLogin<HistorySyncResponse> { token ->
            server.historySync(addBearer(token), request)
        }
    }

    fun episodeSync(request: EpisodeSyncRequest): Completable {
        return getCacheTokenOrLogin { token ->
            server.episodeProgressSync(addBearer(token), request)
        }.ignoreElement()
    }

    fun subscriptionStatus(): Single<SubscriptionStatusResponse> {
        return getCacheTokenOrLogin { token ->
            server.subscriptionStatus(addBearer(token))
        }
    }

    fun subscriptionPurchase(request: SubscriptionPurchaseRequest): Single<SubscriptionStatusResponse> {
        return getCacheTokenOrLogin { token ->
            server.subscriptionPurchase(addBearer(token), request)
        }
    }

    fun getFiles(): Single<Response<FilesResponse>> {
        return getCacheTokenOrLogin { token ->
            server.getFiles(addBearer(token))
        }
    }

    fun postFiles(files: List<FilePost>): Single<Response<Void>> {
        return getCacheTokenOrLogin { token ->
            server.postFiles(
                addBearer(token),
                FilePostBody(files = files)
            )
        }
    }

    fun getUploadUrl(file: FileUploadData): Single<String> {
        return getCacheTokenOrLogin { token ->
            server.getUploadUrl(addBearer(token), file).map { it.url }
        }
    }

    fun getFileUploadStatus(episodeUuid: String): Single<Boolean> {
        return getCacheTokenOrLogin { token ->
            server.getFileUploadStatus(addBearer(token), episodeUuid).map { it.success }
        }
    }

    fun getImageUploadUrl(imageData: FileImageUploadData): Single<String> {
        return getCacheTokenOrLogin { token ->
            server.getImageUploadUrl(addBearer(token), imageData).map { it.url }
        }
    }

    fun uploadToServer(episode: UserEpisode, url: String): Flowable<Float> {
        val path = episode.downloadedFilePath ?: throw IllegalStateException("File is not downloaded")
        val file = File(path)

        return Flowable.create(
            { emitter ->
                try {
                    val requestBody = ProgressRequestBody.create((episode.fileType ?: "audio/mp3").toMediaType(), file, emitter)
                    val call = server.uploadFile(url, requestBody)
                    emitter.setCancellable { call.cancel() }

                    call.execute()
                    if (!emitter.isCancelled) {
                        emitter.onComplete()
                    }
                } catch (e: java.lang.Exception) {
                    emitter.tryOnError(e)
                }
            },
            BackpressureStrategy.LATEST
        )
    }

    fun uploadImageToServer(imageFile: File, url: String): Single<Response<Void>> {
        val requestBody = imageFile.asRequestBody("image/png".toMediaType())
        return server.uploadFileNoProgress(url, requestBody)
    }

    fun deleteImageFromServer(episode: UserEpisode): Single<Response<Void>> {
        return getCacheTokenOrLogin { token ->
            server.deleteImageFile(addBearer(token), episode.uuid)
        }
    }

    fun deleteFromServer(episode: UserEpisode): Single<Response<Void>> {
        return getCacheTokenOrLogin { token ->
            server.deleteFile(addBearer(token), episode.uuid)
        }
    }

    fun getPlaybackUrl(episode: UserEpisode): Single<String> {
        return getCacheTokenOrLogin { token ->
            Single.just("${Settings.SERVER_API_URL}/files/url/${episode.uuid}?token=$token")
        }
    }

    fun getUserEpisode(uuid: String): Maybe<ServerFile> {
        return getCacheTokenOrLogin { token ->
            server.getFile(addBearer(token), uuid)
        }.flatMapMaybe {
            if (it.isSuccessful) {
                Maybe.just(it.body())
            } else if (it.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                Maybe.empty()
            } else {
                Maybe.error(HttpException(it))
            }
        }
    }

    suspend fun loadStats(): StatsBundle {
        return getCacheTokenOrLoginSuspend { token ->
            val response = server.loadStats(addBearer(token), StatsSummaryRequest(deviceId = settings.getUniqueDeviceId()))
            // Convert the strings to a map of longs
            val values = response.filter { (it.value as? String)?.toLongOrNull() != null }.mapValues { (it.value as? String)?.toLong() ?: 0 }
            val startedAt = (response[StatsBundle.SERVER_KEY_STARTED_AT] as? String)?.parseIsoDate()
            StatsBundle(values, startedAt)
        }
    }

    fun getFileUsage(): Single<FileAccount> {
        return getCacheTokenOrLogin { token ->
            server.getFilesUsage(addBearer(token))
        }
    }

    fun signOut() {
        cache.evictAll()
    }

    private suspend fun <T : Any> getCacheTokenOrLoginSuspend(serverCall: suspend (token: String) -> T): T {
        if (settings.isLoggedIn()) {
            return try {
                val token = settings.getSyncTokenSuspend() ?: refreshTokenSuspend()
                serverCall(token)
            } catch (ex: Exception) {
                // refresh invalid
                if (isInvalidTokenError(ex)) {
                    val token = refreshTokenSuspend()
                    serverCall(token)
                } else {
                    throw ex
                }
            }
        } else {
            val token = refreshTokenSuspend()
            return serverCall(token)
        }
    }

    private fun <T : Any> getCacheTokenOrLogin(serverCall: (token: String) -> Single<T>): Single<T> {
        if (settings.isLoggedIn()) {
            return Single.fromCallable { settings.getSyncToken() ?: throw RuntimeException("Failed to get token") }
                .flatMap { token -> serverCall(token) }
                // refresh invalid
                .onErrorResumeNext { throwable ->
                    return@onErrorResumeNext if (isInvalidTokenError(throwable)) {
                        refreshToken().flatMap { token -> serverCall(token) }
                    }
                    // re-throw this error because it's not recoverable from here
                    else {
                        Single.error(throwable)
                    }
                }
        }

        return refreshToken().flatMap { token -> serverCall(token) }
    }

    private fun buildBasicRequest(): BasicRequest {
        return BasicRequest(
            model = Settings.SYNC_API_MODEL,
            version = Settings.SYNC_API_VERSION
        )
    }

    private suspend fun refreshTokenSuspend(): String {
        settings.invalidateToken()
        return settings.getSyncTokenSuspend() ?: throw Exception("Failed to get refresh token")
    }

    private fun refreshToken(): Single<String> {
        settings.invalidateToken()
        return Single.fromCallable { settings.getSyncToken() ?: throw RuntimeException("Failed to get token") }
            .doOnError {
                LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, it, "Refresh token threw an error.")
            }
    }

    private fun isInvalidTokenError(throwable: Throwable?): Boolean {
        return throwable is HttpException && throwable.code() == 401
    }

    private fun addBearer(token: String): String {
        return "Bearer $token"
    }
}
