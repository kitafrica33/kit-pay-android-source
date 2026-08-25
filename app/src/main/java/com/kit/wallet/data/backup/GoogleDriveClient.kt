package com.kit.wallet.data.backup

import com.kit.wallet.di.GoogleHttpClient
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/**
 * Where Google lives. Overridable so the client can be pointed at a local server under test —
 * every request Kit Pay makes to Google is exercised for real rather than mocked out.
 */
data class GoogleDriveEndpoints(
    val revocation: HttpUrl = "https://oauth2.googleapis.com/revoke".toHttpUrl(),
    val drive: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl(),
    val upload: HttpUrl = "https://www.googleapis.com/upload/drive/v3/".toHttpUrl(),
)

/** A file as Drive describes it. Kit Pay only ever has one of these. */
data class DriveFile(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long?,
)

/**
 * Drive, over its REST interface and nothing else.
 *
 * Everything lives in `appDataFolder` — a per-application hidden folder. Kit Pay can see the one
 * file it wrote there and literally nothing else in the user's Drive: not their documents, not
 * their photos, not files another app put in its own app folder. The user does not see it either,
 * except as an entry in Drive's storage settings, which is also where they can delete it.
 *
 * Uploads are resumable. A backup on a phone using mobile data will lose its connection halfway
 * through, and starting again from zero every time is how a feature ends up never completing at
 * all on the connections most Kit Pay users actually have.
 */
@Singleton
class GoogleDriveClient @Inject constructor(
    @GoogleHttpClient private val client: OkHttpClient,
    private val endpoints: GoogleDriveEndpoints,
    moshi: Moshi,
) {
    private val fileAdapter = moshi.adapter(DriveFileResponse::class.java)
    private val listAdapter = moshi.adapter(DriveFileListResponse::class.java)
    private val metadataAdapter = moshi.adapter(DriveFileMetadata::class.java)

    /**
     * Hands the grant back to Google. Revoking an access token revokes the whole grant behind it,
     * which is what "disconnect" has to mean — Play Services would otherwise keep handing out
     * tokens for a scope the user believes they took away.
     *
     * Best effort by design: a user who disconnects on a plane should still see the app forget the
     * account, and the local state is dropped either way.
     */
    suspend fun revoke(token: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoints.revocation)
            .post(FormBody.Builder().add("token", token).build())
            .build()
        runCatching {
            client.newCall(request).execute().use(Response::isSuccessful)
        }.getOrDefault(false)
    }

    suspend fun findFile(accessToken: String, name: String): DriveFile? =
        withContext(Dispatchers.IO) {
            val url = endpoints.drive.newBuilder()
                .addPathSegment("files")
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("q", "name = '${name.escapedForQuery()}' and trashed = false")
                .addQueryParameter("fields", "files($FILE_FIELDS)")
                .addQueryParameter("orderBy", "modifiedTime desc")
                .addQueryParameter("pageSize", "10")
                .build()
            val request = Request.Builder().url(url).get().authorized(accessToken).build()
            client.newCall(request).execute().use { response ->
                val body = response.readOrThrow()
                listAdapter.fromJson(body)?.files.orEmpty().firstNotNullOfOrNull { it.toDriveFile() }
            }
        }

    /**
     * Uploads [source] as the app-folder file [name], replacing [fileId] when one is already there.
     *
     * Replacing in place rather than uploading-then-deleting means there is never a moment where
     * the user has two backups, and never a moment where they have none.
     */
    suspend fun upload(
        accessToken: String,
        fileId: String?,
        name: String,
        source: File,
    ): DriveFile = withContext(Dispatchers.IO) {
        val total = source.length()
        require(total > 0) { "Refusing to upload an empty backup" }
        val session = beginUploadSession(accessToken, fileId, name, total)
        var offset = 0L
        var stalled = 0
        while (stalled <= MAX_RESUME_ATTEMPTS) {
            val request = Request.Builder()
                .url(session)
                .put(source.slice(offset, total))
                .header("Content-Range", "bytes $offset-${total - 1}/$total")
                .build()
            val outcome = client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadOutcome.Done(
                        fileAdapter.fromJson(response.readOrThrow())?.toDriveFile(),
                    )

                    response.code == HTTP_RESUME_INCOMPLETE ->
                        UploadOutcome.Incomplete(response.nextOffset())

                    else -> throw response.toFailure()
                }
            }
            if (outcome is UploadOutcome.Done) {
                return@withContext outcome.file
                    ?: throw IOException("Google did not confirm the backup upload")
            }
            // Only real forward progress resets the budget, so a server that keeps reporting the
            // same offset ends the attempt instead of looping forever.
            val resumeFrom = (outcome as UploadOutcome.Incomplete).offset
            stalled = if (resumeFrom > offset) 0 else stalled + 1
            offset = maxOf(offset, resumeFrom)
        }
        throw IOException("The backup upload stopped making progress")
    }

    suspend fun download(accessToken: String, fileId: String, into: OutputStream): Long =
        withContext(Dispatchers.IO) {
            val url = endpoints.drive.newBuilder()
                .addPathSegment("files")
                .addPathSegment(fileId)
                .addQueryParameter("alt", "media")
                .build()
            val request = Request.Builder().url(url).get().authorized(accessToken).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw response.toFailure()
                val body = response.body ?: throw IOException("The backup download was empty")
                body.byteStream().copyTo(into)
            }
        }

    suspend fun delete(accessToken: String, fileId: String): Unit = withContext(Dispatchers.IO) {
        val url = endpoints.drive.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .build()
        val request = Request.Builder().url(url).delete().authorized(accessToken).build()
        client.newCall(request).execute().use { response ->
            // A backup that is already gone is the outcome the caller wanted.
            if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) throw response.toFailure()
        }
    }

    private fun beginUploadSession(
        accessToken: String,
        fileId: String?,
        name: String,
        total: Long,
    ): HttpUrl {
        val url = endpoints.upload.newBuilder()
            .addPathSegment("files")
            .apply { if (fileId != null) addPathSegment(fileId) }
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val metadata = DriveFileMetadata(
            name = name,
            // Drive rejects a parent on update, and requires one on create.
            parents = if (fileId == null) listOf(APP_DATA_FOLDER) else null,
        )
        val body: RequestBody = metadataAdapter.toJson(metadata).toRequestBody(JSON)
        val builder = Request.Builder()
            .url(url)
            .authorized(accessToken)
            .header("X-Upload-Content-Type", OCTET_STREAM.toString())
            .header("X-Upload-Content-Length", total.toString())
        val request = if (fileId == null) builder.post(body).build()
        else builder.patch(body).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toFailure()
            response.header("Location")?.toHttpUrl()
                ?: throw IOException("Google did not open an upload session")
        }
    }

    private fun Request.Builder.authorized(accessToken: String) =
        header("Authorization", "Bearer $accessToken")

    private fun Response.readOrThrow(): String {
        if (!isSuccessful) throw toFailure()
        return body?.string().orEmpty()
    }

    private fun Response.toFailure(): IOException {
        val detail = runCatching { body?.string() }.getOrNull().orEmpty()
        return when {
            code == HTTP_UNAUTHORIZED -> GoogleAuthorizationException(
                "Google Drive needs you to sign in again",
                requiresSignIn = true,
            )

            code == HTTP_FORBIDDEN && detail.contains("storageQuotaExceeded") ->
                IOException("There is no room left in this Google account")

            code == HTTP_FORBIDDEN -> GoogleAuthorizationException(
                "This Google account did not allow Kit Pay to save backups",
                requiresSignIn = true,
            )

            code == HTTP_TOO_MANY_REQUESTS || code >= 500 ->
                IOException("Google Drive is busy — Kit Pay will try again")

            else -> IOException("Google Drive refused the request")
        }
    }

    private fun Response.nextOffset(): Long {
        // "bytes=0-262143" means everything up to and including 262143 is stored.
        val range = header("Range") ?: return 0
        val last = range.substringAfterLast('-', "").toLongOrNull() ?: return 0
        return last + 1
    }

    private sealed interface UploadOutcome {
        data class Done(val file: DriveFile?) : UploadOutcome
        data class Incomplete(val offset: Long) : UploadOutcome
    }

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"
        const val FILE_FIELDS = "id,name,size,modifiedTime"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_RESUME_INCOMPLETE = 308
        const val MAX_RESUME_ATTEMPTS = 3
        val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
    }
}

/** Drive's `q` grammar is single-quoted, so a quote or backslash in a name has to be escaped. */
private fun String.escapedForQuery(): String =
    replace("\\", "\\\\").replace("'", "\\'")

/** Streams part of a file without reading it into memory — a backup can be tens of megabytes. */
private fun File.slice(offset: Long, total: Long): RequestBody {
    val file = this
    return object : RequestBody() {
        override fun contentType(): MediaType = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = total - offset

        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { stream ->
                var skipped = 0L
                while (skipped < offset) {
                    val step = stream.skip(offset - skipped)
                    if (step <= 0) throw IOException("The backup file changed while uploading")
                    skipped += step
                }
                sink.writeAll(stream.source())
            }
        }
    }
}

@JsonClass(generateAdapter = true)
internal data class DriveFileMetadata(
    val name: String,
    val parents: List<String>?,
)

@JsonClass(generateAdapter = true)
internal data class DriveFileResponse(
    val id: String?,
    val name: String?,
    val size: String?,
    val modifiedTime: String?,
) {
    fun toDriveFile(): DriveFile? {
        val id = id?.takeIf(String::isNotEmpty) ?: return null
        return DriveFile(
            id = id,
            name = name.orEmpty(),
            sizeBytes = size?.toLongOrNull(),
            modifiedAtEpochMillis = modifiedTime?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            },
        )
    }
}

@JsonClass(generateAdapter = true)
internal data class DriveFileListResponse(val files: List<DriveFileResponse>?)
