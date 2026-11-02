package dev.ciel.core.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMillis: Int = 60_000,
)

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    val isSuccess: Boolean get() = code in 200..299
}

/** Classified network failures so the UI can show clear messages (requirement: clear errors). */
sealed class NetworkError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Offline(cause: Throwable?) : NetworkError("No internet connection or host unreachable.", cause)
    class Timeout(cause: Throwable?) : NetworkError("The request timed out.", cause)
    class Http(val code: Int, val responseBody: String) :
        NetworkError("Server returned HTTP $code: ${responseBody.take(300)}")
}

/** Abstraction over HTTP so adapters can be tested with fakes and the app can swap implementations. */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse

    /** Streams the response body line by line (used for Server-Sent Events). */
    fun streamLines(request: HttpRequest): Flow<String>
}

/**
 * Transport built on [HttpURLConnection], which exists on both the JVM and Android.
 * No third-party HTTP client is required.
 */
class UrlConnectionTransport : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(request)
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResponse(code, body, conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") })
        } catch (e: UnknownHostException) {
            throw NetworkError.Offline(e)
        } catch (e: SocketTimeoutException) {
            throw NetworkError.Timeout(e)
        } catch (e: java.net.ConnectException) {
            throw NetworkError.Offline(e)
        } finally {
            conn?.disconnect()
        }
    }

    override fun streamLines(request: HttpRequest): Flow<String> = flow {
        var conn: HttpURLConnection? = null
        try {
            conn = open(request)
            val code = conn.responseCode
            if (code >= 400) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw NetworkError.Http(code, err)
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive() // cancellation (incl. emergency stop) is honoured
                    val line = reader.readLine() ?: break
                    emit(line)
                }
            }
        } catch (e: UnknownHostException) {
            throw NetworkError.Offline(e)
        } catch (e: SocketTimeoutException) {
            throw NetworkError.Timeout(e)
        } catch (e: java.net.ConnectException) {
            throw NetworkError.Offline(e)
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(request: HttpRequest): HttpURLConnection {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        conn.requestMethod = request.method
        conn.connectTimeout = 15_000
        conn.readTimeout = request.timeoutMillis
        request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (request.body != null) {
            conn.doOutput = true
            if (request.headers.keys.none { it.equals("Content-Type", true) }) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }
}
