package ru.ruscrafting.justteams

/**
 * Guards delayed UI work against a dialog visit that has already been replaced
 * or dismissed. All access is on the Paper primary thread.
 */
internal class DialogVisitGeneration {
    @JvmInline
    value class Token internal constructor(val value: Long)

    private var generation = 0L

    fun advance(): Token = Token(++generation)

    fun current(): Token = Token(generation)

    fun invalidate() {
        generation++
    }

    fun ifCurrent(token: Token, action: () -> Unit): Boolean {
        if (token.value != generation) return false
        action()
        return true
    }
}
