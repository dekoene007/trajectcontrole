package be.koen.trajectcontrole

import android.os.Handler
import android.os.Looper
import be.koen.trajectcontrole.core.Status
import java.util.concurrent.CopyOnWriteArrayList

/** Eenvoudige doorgeefluik tussen de service en de schermen (telefoon en Android Auto). */
object StatusBus {

    data class State(
        val running: Boolean = false,
        val status: Status? = null,          // null = niet in een traject
        val lastResult: String? = null,      // tekst van het laatst afgesloten traject
        val gpsOk: Boolean = false,
        val segmentCount: Int = 0,
        val lat: Double? = null, val lon: Double? = null,   // laatste positie
        val heading: Double? = null,
        val gpsKmh: Double = 0.0,
        val rideFile: String? = null,        // naam van het lopende ritlogboek
        val dataVersion: Int = 0              // verhoogt als trajecten/markeringen wijzigen (kaart opnieuw tekenen)
    )

    @Volatile var state = State()
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(transform: (State) -> State) {
        state = transform(state)
        val s = state
        main.post { listeners.forEach { it(s) } }
    }

    fun listen(l: (State) -> Unit) { listeners.add(l); l(state) }
    fun unlisten(l: (State) -> Unit) { listeners.remove(l) }
}
