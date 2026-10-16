package com.trenching.bot.common

import java.util.Collections
import java.util.IdentityHashMap

/**
 * An exception's cause chain, reduced to what logs may carry: class names only. Messages are never read, because transport
 * errors quote the request URL, which can hold a provider credential.
 */
object ExceptionCauses {
    private const val MAX_DEPTH = 16

    /** [exception] followed by its causes, outermost first; stops at a cycle or after [MAX_DEPTH] links. */
    fun chain(exception: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = exception
        while (current != null && chain.size < MAX_DEPTH && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }

    /** The innermost cause's simple class name, such as `ConnectException`, `SocketTimeoutException` or `UnknownHostException`. */
    fun rootName(exception: Throwable): String = chain(exception).last().javaClass.simpleName
}
