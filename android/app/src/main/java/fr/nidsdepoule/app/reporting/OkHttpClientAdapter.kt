package fr.nidsdepoule.app.reporting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp adapter implementing the HttpClient interface.
 */
class OkHttpClientAdapter : HttpClient {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun postJson(url: String, jsonBody: String): HttpResult {
        return withContext(Dispatchers.IO) {
            try {
                val mediaType = "application/json".toMediaType()
                val body = jsonBody.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                HttpResult(
                    success = response.isSuccessful,
                    statusCode = response.code,
                    body = responseBody,
                    bytesSent = jsonBody.toByteArray().size,
                )
            } catch (e: IOException) {
                HttpResult(
                    success = false,
                    error = e.message ?: "network error",
                )
            }
        }
    }

    override suspend fun get(url: String): HttpResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                HttpResult(
                    success = response.isSuccessful,
                    statusCode = response.code,
                    body = responseBody,
                )
            } catch (e: IOException) {
                HttpResult(
                    success = false,
                    error = e.message ?: "network error",
                )
            }
        }
    }
}
