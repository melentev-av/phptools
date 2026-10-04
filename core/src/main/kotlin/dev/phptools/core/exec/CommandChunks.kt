package dev.phptools.core.exec

/** Разбиение списка путей на пачки, чтобы командная строка не превысила лимит ОС. Чистая логика. */
object CommandChunks {
    /** Windows: ~32K символов на всю командную строку; берём с запасом. */
    const val WINDOWS_LIMIT = 30_000

    /** macOS/Linux: ARG_MAX — сотни КБ и больше; держимся скромнее. */
    const val UNIX_LIMIT = 200_000

    /**
     * @param fixedLength длина неизменной части команды (бинарник, compose, опции).
     * @param items пути; каждый занимает `length + 1` (разделитель).
     * Путь длиннее лимита всё равно попадает в отдельную пачку.
     */
    fun split(fixedLength: Int, items: List<String>, limit: Int): List<List<String>> {
        if (items.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var length = fixedLength
        for (item in items) {
            val itemLength = item.length + 1
            if (current.isNotEmpty() && length + itemLength > limit) {
                chunks += current
                current = mutableListOf()
                length = fixedLength
            }
            current += item
            length += itemLength
        }
        chunks += current
        return chunks
    }
}
