package salt

/** Parses the output of `adb devices -l`. */
fun parseDevices(output: String): List<Device> = output.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
    .mapNotNull { line ->
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 2) return@mapNotNull null
        val attrs = parts.drop(2).mapNotNull { token ->
            token.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        Device(serial = parts[0], state = parts[1], attributes = attrs)
    }
    .toList()
