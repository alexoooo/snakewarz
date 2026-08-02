package ao.snakewarz.ui.chrome

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.ui.model.UiModel
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.pointerevents.PointerEvent

/** A dedicated four-way control cluster sharing the keyboard's hold-repeat clock. */
internal class SteerPad(private val repeat: SteerRepeat) {
    private val root: HTMLElement = elementById("steer-pad")
    private val buttons: List<Pair<HTMLButtonElement, Direction>> = listOf(
        elementById<HTMLButtonElement>("steer-north") to Direction.NORTH,
        elementById<HTMLButtonElement>("steer-south") to Direction.SOUTH,
        elementById<HTMLButtonElement>("steer-west") to Direction.WEST,
        elementById<HTMLButtonElement>("steer-east") to Direction.EAST,
    )

    private var held: Direction? = null
    private var offered: Boolean = false

    init {
        for ((button, direction) in buttons) {
            button.addEventListener("pointerdown") { event -> press(event as PointerEvent, direction) }
            button.addEventListener("keydown") { event ->
                val key = event as KeyboardEvent
                if (key.key == "Enter" && !key.repeat) {
                    hold(direction)
                }
            }
            button.addEventListener("keyup") { event ->
                if ((event as KeyboardEvent).key == "Enter") {
                    release()
                }
            }
        }

        root.addEventListener("pointermove") { event -> aim(event as PointerEvent) }
        window.addEventListener("pointerup") { release() }
        window.addEventListener("pointercancel") { release() }
    }

    /** Present for a human board and enabled exactly while that board accepts steering. */
    fun render(model: UiModel) {
        offered = model.steering
        if (!offered) {
            release()
        }
        root.hidden = !model.steeringPad
        for ((button, _) in buttons) {
            button.disabled = !offered
        }
    }

    fun cancel(): Unit = release()

    override fun toString(): String = "SteerPad(${held ?: "up"})"

    private fun press(event: PointerEvent, direction: Direction) {
        event.preventDefault()
        if (!offered || held != null) {
            return
        }
        hold(direction)
    }

    private fun aim(event: PointerEvent) {
        if (held == null) {
            return
        }
        val direction = directionAt(event.clientX.toDouble(), event.clientY.toDouble())
        if (direction != null && direction != held) {
            hold(direction)
        }
    }

    private fun directionAt(clientX: Double, clientY: Double): Direction? =
        buttons.firstOrNull { (button, _) ->
            val box = button.getBoundingClientRect()
            clientX >= box.left && clientX < box.right && clientY >= box.top && clientY < box.bottom
        }?.second

    private fun hold(direction: Direction) {
        if (!offered) {
            return
        }
        for ((button, of) in buttons) {
            button.classList.toggle("held", of == direction)
        }
        held = direction
        repeat.press(direction)
    }

    private fun release() {
        val direction = held ?: return
        held = null
        for ((button, _) in buttons) {
            button.classList.remove("held")
        }
        repeat.release(direction)
    }
}
