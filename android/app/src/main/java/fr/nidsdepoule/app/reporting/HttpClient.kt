package fr.nidsdepoule.app.reporting

/**
 * Abstraction over HTTP transport.
 * Allows swapping OkHttp for Ktor or any other client.
 */
interface HttpClient {
    /**
     * POST JSON data to the given URL.
     * @return HttpResult with status code and response body, or error.
     */
    suspend fun postJson(url: String, jsonBody: String): HttpResult

    /**
     * GET the given URL.
     * @return HttpResult with status code and response body, or error.
     */
    suspend fun get(url: String): HttpResult
}

data class HttpResult(
    val success: Boolean,
    val statusCode: Int = 0,
    val body: String = "",
    val error: String? = null,
    val bytesSent: Int = 0,
)
