package io.github.iseaprojects.rivo.data.paging

/**
 * Explicit page window shared by every data screen.
 *
 * Backends return pages of their own size (NewPipe usually 20, yt-dlp the requested
 * count), while `CONFIG_PER_PAGE` defines the window the user sees. Incoming items are
 * buffered and re-chunked into pages of exactly [pageSize] (the last page may be
 * shorter), duplicates are dropped by key, and issued pages are kept so Previous is
 * instant and never re-fetches.
 */
class PageWindow<T>(
    val pageSize: Int,
    private val keyOf: (T) -> String,
) {
    private val issued = mutableListOf<List<T>>()
    private val pending = ArrayDeque<T>()
    private val seen = HashSet<String>()
    private var index = 0

    val pageIndex: Int get() = index
    val pageCount: Int get() = issued.size
    val hasPrevious: Boolean get() = index > 0
    val hasBuffered: Boolean get() = pending.isNotEmpty()

    fun bufferedCount(): Int = pending.size

    fun current(): List<T> = issued.getOrNull(index).orEmpty()

    fun append(items: List<T>) {
        for (item in items) {
            if (seen.add(keyOf(item))) pending.addLast(item)
        }
    }

    /** Issues the next page from the buffer; empty when the buffer cannot fill one. */
    fun issue(): List<T> {
        if (pending.isEmpty()) return emptyList()
        val page = ArrayList<T>(pageSize)
        while (page.size < pageSize && pending.isNotEmpty()) {
            page += pending.removeFirst()
        }
        issued += page
        index = issued.lastIndex
        return page
    }

    /** Moves within already issued pages. Returns null at the edges. */
    fun move(delta: Int): List<T>? {
        val target = index + delta
        if (target !in issued.indices) return null
        index = target
        return issued[target]
    }

    /**
     * True when another window exists: either an already issued page ahead of the
     * cursor, or buffered/fetchable items.
     */
    fun canAdvance(feedExhausted: Boolean): Boolean =
        index < issued.lastIndex || pending.isNotEmpty() || !feedExhausted
}
