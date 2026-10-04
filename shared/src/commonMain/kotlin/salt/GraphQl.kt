package salt

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One GraphQL operation found in a request. A batched request carries several. */
data class GqlOperation(
    /** query, mutation or subscription. */
    val type: String,
    val name: String?,
    /** Null for a persisted query that only sends a hash. */
    val query: String?,
    /** Pretty-printed JSON, null when there are none. */
    val variables: String?,
    val persisted: Boolean = false,
)

/** The data and error messages of one operation's response. */
data class GqlResult(val data: String?, val errors: List<String>)

private val pretty = Json { prettyPrint = true }
private val operationHead = Regex("""(?m)^\s*(query|mutation|subscription)\b[ \t]*([A-Za-z_]\w*)?""")
private val looksLikeGraphQl = Regex("""^\s*(#[^\n]*\n\s*)*(query|mutation|subscription|fragment|\{)""")

/**
 * Finds the GraphQL operations in [req], or an empty list when it is not GraphQL. Covers the shapes clients send:
 * JSON POST, batched JSON arrays, `application/graphql` bodies, GET with `?query=`, and persisted queries (hash only).
 */
fun graphQlOperations(req: RequestSpec): List<GqlOperation> {
    val contentType = req.headers.firstOrNull { it.name.equals("content-type", ignoreCase = true) }?.value.orEmpty()
    val objects: List<JsonObject> = when {
        req.method.equals("GET", ignoreCase = true) -> runCatching {
            val p = Url(req.url).parameters
            if (p["query"] == null && p["extensions"] == null) emptyList() else listOf(buildJsonObject {
                p["query"]?.let { put("query", it) }
                p["operationName"]?.let { put("operationName", it) }
                p["variables"]?.let { v -> put("variables", runCatching { Json.parseToJsonElement(v) }.getOrDefault(JsonNull)) }
                p["extensions"]?.let { e -> runCatching { Json.parseToJsonElement(e) }.getOrNull()?.let { put("extensions", it) } }
            })
        }.getOrDefault(emptyList())
        contentType.startsWith("application/graphql") && !contentType.contains("json") -> listOf(buildJsonObject { put("query", req.body) })
        else -> when (val root = runCatching { Json.parseToJsonElement(req.body) }.getOrNull()) {
            is JsonObject -> listOf(root)
            is JsonArray -> root.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }
    val ops = objects.mapNotNull(::operationOf)
    // A batch counts only when every item is GraphQL; otherwise it is some other JSON API.
    return if (ops.size == objects.size) ops else emptyList()
}

private fun operationOf(o: JsonObject): GqlOperation? {
    val query = (o["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val operationName = (o["operationName"] as? JsonPrimitive)?.contentOrNull
    val variables = o["variables"]
        ?.takeIf { it !is JsonNull && !(it is JsonObject && it.isEmpty()) }
        ?.let { pretty.encodeToString(JsonElement.serializer(), it) }
    val hash = (((o["extensions"] as? JsonObject)?.get("persistedQuery")) as? JsonObject)?.get("sha256Hash")
        ?.let { (it as? JsonPrimitive)?.contentOrNull }
    if (query == null) return if (hash != null) GqlOperation("query", operationName ?: hash.take(8), null, variables, persisted = true) else null
    if (!looksLikeGraphQl.containsMatchIn(query)) return null
    val head = operationHead.find(query)
    return GqlOperation(
        head?.groupValues?.get(1) ?: "query",
        operationName ?: head?.groupValues?.get(2)?.ifEmpty { null },
        query, variables, persisted = hash != null,
    )
}

/** Short label for a request's operations: "GetUser", or "GetUser +2" for a batch. */
fun List<GqlOperation>.title(): String {
    val first = first().name ?: "anonymous"
    return if (size > 1) "$first +${size - 1}" else first
}

/** Splits a response into one [GqlResult] per operation; null where the body has no `data` or `errors`. */
fun graphQlResults(resp: ResponseSpec, count: Int): List<GqlResult?> {
    val items = when (val root = runCatching { Json.parseToJsonElement(resp.body) }.getOrNull()) {
        is JsonObject -> listOf(root)
        is JsonArray -> root.toList()
        else -> emptyList()
    }
    return List(count) { i ->
        (items.getOrNull(i) as? JsonObject)?.takeIf { "data" in it || "errors" in it }?.let(::resultOf)
    }
}

private fun resultOf(o: JsonObject) = GqlResult(
    data = o["data"]?.takeIf { it !is JsonNull }?.let { pretty.encodeToString(JsonElement.serializer(), it) },
    errors = (o["errors"] as? JsonArray).orEmpty().map { e ->
        val fields = e as? JsonObject
        val message = (fields?.get("message") as? JsonPrimitive)?.contentOrNull ?: e.toString()
        val path = (fields?.get("path") as? JsonArray)?.joinToString(".") { (it as? JsonPrimitive)?.content.orEmpty() }
        if (path.isNullOrEmpty()) message else "$message ($path)"
    },
)

/** Indents a minified query (as Apollo clients send them). A query that already has line breaks is left alone. */
fun formatGraphQl(query: String): String {
    val src = query.trim()
    if ('\n' in src) return src
    val out = StringBuilder()
    var depth = 0
    var parens = 0
    var space = false
    var afterClose = false
    var inString = false
    fun newline() { out.append('\n').append("  ".repeat(depth)) }
    for ((i, c) in src.withIndex()) {
        if (inString) { out.append(c); if (c == '"' && src[i - 1] != '\\') inString = false; continue }
        when {
            c.isWhitespace() || c == ',' -> space = true
            c == '{' && parens == 0 -> {
                if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                out.append('{'); depth++; newline(); space = false; afterClose = false
            }
            c == '}' && parens == 0 -> {
                depth = maxOf(0, depth - 1); newline(); out.append('}'); space = false; afterClose = true
            }
            else -> {
                val last = out.lastOrNull()
                if (afterClose) newline()
                else if (space && last != null && !last.isWhitespace()) {
                    val sameLine = depth == 0 || parens > 0 || c == '@' || c == '(' || c == ':' ||
                        last == ':' || out.endsWith("...") || out.endsWith("... on")
                    if (sameLine) { if (last != '(' && c != ')') out.append(' ') } else newline()
                }
                out.append(c)
                if (c == '"') inString = true
                if (c == '(') parens++ else if (c == ')') parens = maxOf(0, parens - 1)
                space = false; afterClose = false
            }
        }
    }
    return out.toString()
}
