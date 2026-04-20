package com.leica.cam.common.types

/**
 * A list that is guaranteed to have at least one element.
 * Constructed via [of] factory to enforce the non-empty invariant at the call site.
 */
@JvmInline
value class NonEmptyList<out T> private constructor(private val delegate: List<T>) : List<T> by delegate {

    val head: T get() = delegate.first()

    val tail: List<T> get() = delegate.drop(1)

    companion object {
        /**
         * Constructs a [NonEmptyList] from a head element and optional tail.
         * The resulting list always has at least one element.
         */
        fun <T> of(head: T, vararg tail: T): NonEmptyList<T> =
            NonEmptyList(listOf(head, *tail))

        /**
         * Constructs a [NonEmptyList] from an existing [List].
         * Returns `null` if the list is empty.
         */
        fun <T> fromList(list: List<T>): NonEmptyList<T>? =
            if (list.isNotEmpty()) NonEmptyList(list) else null

        /**
         * Constructs a [NonEmptyList] from an existing [List].
         * Throws [IllegalArgumentException] if the list is empty.
         */
        fun <T> fromListOrThrow(list: List<T>): NonEmptyList<T> =
            if (list.isNotEmpty()) NonEmptyList(list) else throw IllegalArgumentException("NonEmptyList cannot be empty")
    }

    override fun toString(): String = "NonEmptyList($delegate)"
}
