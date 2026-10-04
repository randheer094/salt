package salt

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** The request as a curl command that can be pasted into a terminal. */
fun RequestSpec.toCurl(): String = buildString {
    append("curl ")
    if (method != "GET" || body.isNotEmpty()) append("-X $method ")
    append(shellQuote(url))
    headers.forEach { append(" \\\n  -H ${shellQuote("${it.name}: ${it.value}")}") }
    if (body.isNotEmpty()) append(" \\\n  --data-raw ${shellQuote(body)}")
}

/** Splits a command line like a POSIX shell: quotes, backslashes, line continuations, and `$'...'` strings. */
private fun shellSplit(input: String): List<String> {
    val text = input.replace("\\\r\n", " ").replace("\\\n", " ")
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var started = false
    var quote: Char? = null // ' or " or A for $'...'
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quote == '\'' -> if (c == '\'') quote = null else cur.append(c)
            quote == 'A' -> when {
                c == '\'' -> quote = null
                c == '\\' && i + 1 < text.length -> {
                    val n = text[++i]
                    cur.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n })
                }
                else -> cur.append(c)
            }
            quote == '"' -> when {
                c == '"' -> quote = null
                c == '\\' && i + 1 < text.length && text[i + 1] in "\"\\$`" -> cur.append(text[++i])
                else -> cur.append(c)
            }
            c == '$' && i + 1 < text.length && text[i + 1] == '\'' -> { quote = 'A'; started = true; i++ }
            c == '\'' || c == '"' -> { quote = c; started = true }
            // A backslash before whitespace is a line continuation whose newline was already turned into a space.
            c == '\\' && i + 1 < text.length && text[i + 1].isWhitespace() -> { if (started) { out += cur.toString(); cur.clear(); started = false }; i++ }
            c == '\\' && i + 1 < text.length -> { cur.append(text[++i]); started = true }
            c.isWhitespace() -> if (started) { out += cur.toString(); cur.clear(); started = false }
            else -> { cur.append(c); started = true }
        }
        i++
    }
    if (started) out += cur.toString()
    return out
}

private val bodyFlags = setOf("-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode")

/** Reads a curl command (as copied from browser dev tools or docs) into a request, or null when it has no URL. */
@OptIn(ExperimentalEncodingApi::class)
fun parseCurl(command: String): RequestSpec? {
    val tokens = shellSplit(command.trim())
    if (tokens.firstOrNull() != "curl") return null
    var method: String? = null
    var url: String? = null
    val headers = mutableListOf<Header>()
    val bodies = mutableListOf<String>()
    var i = 1
    fun next() = tokens.getOrNull(++i).orEmpty()
    while (i < tokens.size) {
        val t = tokens[i]
        when {
            t == "-X" || t == "--request" -> method = next().uppercase()
            t == "-H" || t == "--header" -> next().split(":", limit = 2).takeIf { it.size == 2 }?.let { headers += Header(it[0].trim(), it[1].trim()) }
            t in bodyFlags -> bodies += next()
            t == "--url" -> url = next()
            t == "-u" || t == "--user" -> headers += Header("Authorization", "Basic " + Base64.encode(next().encodeToByteArray()))
            t == "-I" || t == "--head" -> method = "HEAD"
            t.startsWith("-") -> if (t in setOf("-A", "--user-agent", "-e", "--referer", "-b", "--cookie", "-o", "--output", "-m", "--max-time", "--connect-timeout", "-x", "--proxy")) {
                val v = next()
                when (t) {
                    "-A", "--user-agent" -> headers += Header("User-Agent", v)
                    "-e", "--referer" -> headers += Header("Referer", v)
                    "-b", "--cookie" -> headers += Header("Cookie", v)
                }
            }
            else -> if (url == null) url = t
        }
        i++
    }
    val target = url ?: return null
    val body = bodies.joinToString("&")
    return RequestSpec(method ?: if (body.isNotEmpty()) "POST" else "GET", if ("://" in target) target else "https://$target", headers, body)
}
