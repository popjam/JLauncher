package app.olauncher.helper

import android.content.Context

/** Medicines added for the user once, like any stat made in the editor; deleting one keeps it gone. */
object Medicines {

    fun ensure(context: Context) {
        AutoStats.ensure(context, AutoStats.BUSPIRONE, BUSPIRONE) { store ->
            val stat = store.addStat(BUSPIRONE, BUSPIRONE_COLOR, daily = true, scale = 5, question = "How much $BUSPIRONE?",
                labels = BUSPIRONE_DOSES, inverted = false, phrases = List(5) { "You took {} of $BUSPIRONE." },
                medicine = true, nextDay = false)
            // Next to the other medicines
            val order = context.allStats(store.load()).map { it.id }.filter { it != stat.id }.toMutableList()
            val after = store.load().stats.lastOrNull { it.medicine && it.name.equals("Wellbutrin", ignoreCase = true) }?.id
                ?: store.load().stats.lastOrNull { it.medicine && it.id != stat.id }?.id
            val place = after?.let { order.indexOf(it) + 1 }?.takeIf { it > 0 } ?: order.size
            order.add(place, stat.id)
            store.setOrder(order)
            stat
        }
    }

    private const val BUSPIRONE = "Buspirone"
    private val BUSPIRONE_DOSES = listOf("0mg", "10mg", "20mg", "30mg", "40mg")
    private const val BUSPIRONE_COLOR = 0xFF4DB6AC.toInt()
}
