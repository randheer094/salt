package salt

private val urlParts = Regex("""^(\w+)://([^/?#]+)([^?#]*)(\?[^#]*)?""")

/** True when [url] matches the wildcard [pattern]. See [MockRule.url] for the rules. */
fun urlMatches(pattern: String, url: String): Boolean {
    val full = if ("://" in pattern) pattern else "*://$pattern"
    val target = if ('?' in pattern) url else url.substringBefore('?')
    val regex = Regex(full.split("*").joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    return regex.matches(target)
}

/** Whether [req] is caught by this rule. Every non-blank field must match; a disabled rule matches nothing. */
fun MockRule.matches(req: RequestSpec): Boolean {
    if (!enabled) return false
    if (!method.equals("ANY", ignoreCase = true) && !method.equals(req.method, ignoreCase = true)) return false
    if (url.isNotBlank() && !urlMatches(url.trim(), req.url)) return false
    if (bodyContains.isNotBlank() && !req.body.contains(bodyContains, ignoreCase = true)) return false
    // GraphQL goes last: parsing the body is the costly check.
    if (operation.isNotBlank() && graphQlOperations(req).none { it.name.equals(operation.trim(), ignoreCase = true) }) return false
    return true
}

fun List<MockRule>.firstMatch(req: RequestSpec): MockRule? = firstOrNull { it.matches(req) }

/** Rewrites [url] to go to [target]: scheme and host always, the path only when [target] names one. Null if either is not a URL. */
fun mapRemoteUrl(url: String, target: String): String? {
    val from = urlParts.find(url) ?: return null
    val to = urlParts.find(target.trim()) ?: return null
    val path = to.groupValues[3].takeIf { it.isNotEmpty() && it != "/" } ?: from.groupValues[3]
    return "${to.groupValues[1]}://${to.groupValues[2]}$path${from.groupValues[4]}"
}

// Describe the hop between server and proxy, or the encoding of bytes we already decoded for display.
private val dropFromMock = setOf("content-length", "content-encoding", "transfer-encoding", "connection", "keep-alive", "date", "server")

/** A ready-to-edit rule that replays [c]: same method and URL (without the query), same operation and response. */
fun ruleFromCapture(c: Capture, id: String): MockRule {
    val ops = graphQlOperations(c.request)
    val op = ops.singleOrNull()?.name.orEmpty()
    val r = c.response
    val pattern = c.request.url.substringBefore('?').substringAfter("://")
    val label = if (op.isNotEmpty()) op else "${c.request.method} /${pattern.substringAfter('/', "")}"
    return MockRule(
        id = id,
        name = label,
        method = c.request.method,
        url = pattern,
        operation = op,
        status = r?.status?.takeIf { it > 0 } ?: 200,
        headers = r?.headers?.filter { it.name.lowercase() !in dropFromMock }.orEmpty().ifEmpty { listOf(Header("Content-Type", "application/json")) },
        // Binary and truncated bodies come back as a "<...>" placeholder; mocking with that would be a trap.
        body = r?.body?.takeUnless { it.startsWith("<binary") || it.startsWith("<undecodable") || it.endsWith("…truncated") }.orEmpty(),
    )
}
