package dev.yurie.display.ui

class UiState<T>(initial: T) {
    private val listeners = mutableMapOf<Int, () -> Unit>()
    private var nextId = 0

    var value: T = initial
        set(next) {
            if (field == next) return
            field = next
            listeners.values.toList().forEach { it() }
        }

    fun update(transform: (T) -> T) {
        value = transform(value)
    }

    fun observe(onChange: () -> Unit): () -> Unit {
        val id = nextId++
        listeners[id] = onChange
        return { listeners.remove(id) }
    }
}
