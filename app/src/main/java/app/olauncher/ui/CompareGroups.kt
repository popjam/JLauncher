package app.olauncher.ui

/**
 * How Compare splits days by an amount: one group per value when there are only a few (doses, cups),
 * otherwise the days' own lowest, middle and highest thirds (two halves when too many days share a value).
 */
internal object CompareGroups {

    /** A group of amounts: [exact] alone, or above [from] (exclusive) up to [to] (inclusive); null ends are open */
    data class Bucket(val exact: Float? = null, val from: Float? = null, val to: Float? = null) {
        fun contains(x: Float) = if (exact != null) x == exact else (from == null || x > from) && (to == null || x <= to)
    }

    const val MAX_EXACT = 5

    fun amountBuckets(xs: List<Float>): List<Bucket> {
        val distinct = xs.distinct().sorted()
        if (distinct.size <= MAX_EXACT) return distinct.map { Bucket(exact = it) }
        val sorted = xs.sorted()
        val low = sorted[sorted.size / 3]
        val high = sorted[sorted.size * 2 / 3]
        return if (high <= low) listOf(Bucket(to = low), Bucket(from = low))
        else listOf(Bucket(to = low), Bucket(from = low, to = high), Bucket(from = high))
    }
}
