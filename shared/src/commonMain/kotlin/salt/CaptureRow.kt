package salt

import kotlinx.serialization.Serializable

@Serializable
data class RowOperation(val type: String, val name: String?)

/**
 * What the traffic list needs about one capture, without any bodies, so a full day of traffic stays cheap to list.
 * The server builds it once per capture; the details come from [NetworkApi.capture].
 */
@Serializable
data class CaptureRow(
    val id: Long,
    val time: Long,
    /** Local time of day ("14:32:05"), with the weekday when it is not today. Formatted by the server. */
    val clock: String,
    val method: String,
    val url: String,
    /** Null while in flight or when the request failed ([error] says why). */
    val status: Int? = null,
    val pending: Boolean = false,
    val error: String? = null,
    val durationMs: Long = 0,
    /** Characters in the response body. */
    val size: Int = 0,
    val tunnel: Boolean = false,
    val mocked: String? = null,
    val operations: List<RowOperation> = emptyList(),
    /** A GraphQL response that came back with `errors`, whatever the HTTP status. */
    val gqlErrors: Boolean = false,
)

fun Capture.toRow(clock: String): CaptureRow {
    val ops = if (tunnel) emptyList() else graphQlOperations(request)
    val r = response
    return CaptureRow(
        id = id, time = time, clock = clock, method = request.method, url = request.url,
        status = r?.status?.takeIf { it > 0 }, pending = r == null, error = r?.error,
        durationMs = r?.durationMs ?: 0, size = r?.body?.length ?: 0, tunnel = tunnel, mocked = mocked,
        operations = ops.map { RowOperation(it.type, it.name) },
        gqlErrors = r != null && ops.isNotEmpty() && graphQlResults(r, ops.size).any { it?.errors?.isNotEmpty() == true },
    )
}

/** "GetUser", or "GetUser +2" for a batch. */
fun List<RowOperation>.rowTitle(): String {
    val first = first().name ?: "anonymous"
    return if (size > 1) "$first +${size - 1}" else first
}
