/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.atomicfu.locks.*
import kotlinx.coroutines.channels.BufferOverflow.*
import kotlinx.coroutines.channels.ChannelResult.Companion.success
import kotlinx.coroutines.internal.callUndeliveredElement
import kotlinx.coroutines.internal.OnUndeliveredElement
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*

/**
 * Channel with array buffer of a fixed capacity.
 * Sender suspends only when buffer is full and receiver suspends only when buffer is empty.
 *
 * This channel is created by `Channel(capacity)` factory function invocation.
 *
 * This implementation is blocking and uses a lock to protect send and receive operations.
 * Removing a cancelled sender or receiver from a list of waiters is lock-free.
 **/
internal open class ConflatedBufferedChannel<E>(
    /**
     * Buffer capacity.
     */
    private val capacity: Int,
    private val onBufferOverflow: BufferOverflow,
    onUndeliveredElement: OnUndeliveredElement<E>?
) : BufferedChannel<E>(capacity = capacity, onUndeliveredElement = onUndeliveredElement) {
    private val lock = reentrantLock()

    init {
        require(onBufferOverflow !== SUSPEND) {
            "This implementation does not support suspension for senders, use ${BufferedChannel::class.simpleName} instead"
        }
        require(capacity >= 1) {
            "Buffered channel capacity must be at least 1, but $capacity was specified"
        }
    }

    /*
 * Modification for receive operations: all of them should be protected by lock.
 * Thus, each receive operation starts with acquiring the lock. Once the
 * synchronization is completed, [onReceiveSynchronizationCompletion] is invoked,
 * the implementation of which releases the lock.
 */

    override suspend fun receive(): E {
        lock.lock()
        return super.receive()
    }

    override suspend fun receiveCatching(): ChannelResult<E> {
        lock.lock()
        return super.receiveCatching()
    }

    override fun tryReceive(): ChannelResult<E> {
        lock.lock()
        return super.tryReceive()
    }

    override fun registerSelectForReceive(select: SelectInstance<*>, ignoredParam: Any?) {
        lock.lock()
        super.registerSelectForReceive(select, ignoredParam)
    }

    override fun iterator(): ChannelIterator<E> = ConflatedChannelIterator()

    private inner class ConflatedChannelIterator : BufferedChannelIterator() {
        override suspend fun hasNext(): Boolean {
            lock.lock()
            return super.hasNext()
        }
    }

    override fun onReceiveSynchronizationCompletion() {
        lock.unlock()
    }

    /*
     * Modification for send operations: all of them should be protected by lock
     * and never suspend; the [onBufferOverflow] strategy is used instead of suspension.
     *
     */

    override suspend fun send(element: E) {
        val attempt = trySend(element)
        if (attempt.isClosed) {
            onUndeliveredElement?.callUndeliveredElement(element, coroutineContext)
            throw sendException(attempt.exceptionOrNull())
        }
    }

    override fun trySend(element: E): ChannelResult<Unit> = lock.withLock {
        while (true) {
            if (!shouldSendSuspend()) return super.trySend(element)
            if (onBufferOverflow === DROP_LATEST) {
                onUndeliveredElement?.invoke(element)
            } else { // DROP_OLDEST
                val tryReceiveResult = tryReceiveInternal()
                if (tryReceiveResult.isFailure) continue
                check(!shouldSendSuspend())
                super.trySend(element)
                onUndeliveredElement?.invoke(tryReceiveResult.getOrThrow())
            }
            return success(Unit)
        }
        error("unreachable")
    }

    override fun registerSelectForSend(select: SelectInstance<*>, element: Any?) {
        trySend(element as E).let {
            it.onSuccess {
                select.selectInRegistrationPhase(Unit)
            }.onClosed {
                select.selectInRegistrationPhase(CHANNEL_CLOSED)
            }
        }
    }
}