package kotlinx.coroutines.channels

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ChannelResult.Companion.closed
import kotlinx.coroutines.channels.ChannelResult.Companion.failure
import kotlinx.coroutines.channels.ChannelResult.Companion.success
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.selects.*
import kotlinx.coroutines.selects.TrySelectDetailedResult.*
import kotlin.coroutines.*
import kotlin.jvm.*
import kotlin.native.concurrent.*
import kotlin.reflect.*

/**
 * TODO huge documentation
 */
internal open class BufferedChannel<E>(
    /**
     * Channel capacity, `0` for rendezvous channel
     * and `Channel.UNLIMITED` for unlimited capacity.
     */
    capacity: Int,
    @JvmField
    protected val onUndeliveredElement: OnUndeliveredElement<E>? = null
) : Channel<E> {
    init {
        require(capacity >= 0) { "Invalid channel capacity: $capacity, should be >=0" }
    }

    /*
     * Instead of keeping the capacity, the implementation stores an information on whether
     * this channel is rendezvous or unlimited. In these cases, the [bufferEnd] and
     * [bufferEndSegment] are ignored.
     */
    private val rendezvous = capacity == Channel.RENDEZVOUS
    private val unlimited = capacity == Channel.UNLIMITED

    /*
     * The counters and the segments for send, receive, and buffer expansion operations.
     * The counters are incremented in the  beginning of the corresponding operation;
     * thus, acquiring a unique (for the operation type) cell to process.
     */
    private val sendersAndCloseStatus = atomic(0L)
    private val receivers = atomic(0L)
    private val bufferEnd = atomic(capacity.toLong())

    private val sendSegment: AtomicRef<ChannelSegment<E>>
    private val receiveSegment: AtomicRef<ChannelSegment<E>>
    private val bufferEndSegment: AtomicRef<ChannelSegment<E>?>

    init {
        val s = ChannelSegment<E>(0, null, 3)
        sendSegment = atomic(s)
        receiveSegment = atomic(s)
        bufferEndSegment = atomic(if (rendezvous || unlimited) null else s)
    }

    // ######################
    // ## Send and Receive ##
    // ######################

    protected open fun onReceiveEnqueued() {}
    protected open fun onReceiveDequeued() {}

    override fun trySend(element: E): ChannelResult<Unit> {
        // Is this channel is closed for send?
        val sendersAndCloseStatusCur = sendersAndCloseStatus.value
        if (sendersAndCloseStatusCur.isClosedForSend0) return onClosedTrySend()
        // Do not try to send the value if the plain `send(e)` operation should suspend.
        // Specifically, `send(e)` is bound to suspend if the channel is NOT unlimited,
        // the number of receivers is greater than then index of the working cell of the
        // considering `send(e)` (this index is `s` below), and the buffer does not cover
        // this cell in case of buffered channel.
        val s = sendersAndCloseStatusCur.counter
        val shouldSuspend = !unlimited && s >= receivers.value && (rendezvous || s >= bufferEnd.value)
        // This operation is bound to fail.
        if (shouldSuspend) return failure()
        // There are chances to send the element. Let's have a try!
        // The logic is similar to the plain `send(e)` operation, with
        // the only difference that we use a special `INTERRUPTED` token
        // as waiter. Intuitively, in case of suspension (the checks above
        // can become outdated), we insert an already cancelled waiter by
        // putting `INTERRUPTED` to the cell.
        return sendImpl(
            element = element,
            // Use a special token that represents a cancelled waiter.
            // Consumers cannot resume it and skip the corresponding cell.
            waiter = INTERRUPTED,
            // Finish successfully when a rendezvous happens
            // or the element has been buffered.
            onRendezvous = { success(Unit) },
            // On suspension, the `INTERRUPTED` token is installed,
            // and this `trySend(e)` fails. According to the contract,
            // we do not need to call [onUndeliveredElement] handler as
            // in the plain `send(e)` operation.
            onSuspend = { _, _ -> failure() },
            // When the channel is closed, return the corresponding result.
            onClosed = { onClosedTrySend() }
        )
    }

    override suspend fun send(element: E): Unit = sendImpl(
        element = element,
        // Do not create continuation until it is required,
        // it is later created via [onNoWaiter] below, under request.
        waiter = null,
        // Finish immediately when a rendezvous happens
        // or the element has been buffered.
        onRendezvous = {},
        // As no waiter is provided, suspension is impossible.
        onSuspend = { _, _ -> },
        // According to the `send(e)` contract, we need to call
        // [onUndeliveredElement] handler and throw exception
        // if the channel is already closed.
        onClosed = { onClosedSend(element, coroutineContext) },
        // When `send(e)` decides to suspend, the corresponding
        // `suspend` function is called -- the tail-call optimization
        // should be applied here.
        onNoWaiter = { segm, i, elem, s -> sendOnNoWaiterSuspend(segm, i, elem, s) }
    )

    private suspend fun sendOnNoWaiterSuspend(
        segm: ChannelSegment<E>,
        i: Int,
        element: E,
        s: Long
    ) = suspendCancellableCoroutineReusable<Unit> sc@{ cont ->
        sendImplOnNoWaiter(
            segm, i, element, s,
            waiter = cont,
            onRendezvous = { cont.resume(Unit) },
            onSuspend = { segment, i ->
                cont.prepareSenderForSuspension(segment, i)
            },
            onClosed = { onClosedSendSuspend(element, cont) },
        )
    }

    private fun onClosedSend(element: E, coroutineContext: CoroutineContext) {
        onUndeliveredElement?.callUndeliveredElement(element, coroutineContext)
        throw recoverStackTrace(sendException(getCause()))
    }

    private fun onClosedSendSuspend(element: E, cont: CancellableContinuation<Unit>) {
        onUndeliveredElement?.callUndeliveredElement(element, cont.context)
        throw recoverStackTrace(sendException(getCause()), cont)
    }

    private fun onClosedTrySend(): ChannelResult<Unit> {
        return closed(sendException(getCause()))
    }

    private fun CancellableContinuation<Unit>.prepareSenderForSuspension(
        segment: ChannelSegment<E>,
        i: Int
    ) {
        invokeOnCancellation { segment.onCancellation(i, onUndeliveredElement, context, this) }
    }

    protected fun shouldSendSuspend(): Boolean {
        val sendersAndCloseStatusCur = sendersAndCloseStatus.value
        val closed = sendersAndCloseStatusCur.isClosedForSend0
        if (closed) return false
        val s = sendersAndCloseStatusCur.counter
        return !(unlimited || s < bufferEnd.value || s < receivers.value)
    }

    private inline fun <W, R> sendImpl(
        element: E,
        waiter: W,
        onRendezvous: () -> R,
        onSuspend: (segm: ChannelSegment<E>, i: Int) -> R,
        onClosed: () -> R,
        onNoWaiter: (segm: ChannelSegment<E>, i: Int, element: E, s: Long) -> R
                    = { _, _, _, _ -> error("unreachable code") }
    ): R {
        var segm = sendSegment.value
        while (true) {
            val sendersAndCloseStatusCur = sendersAndCloseStatus.getAndIncrement()
            val closed = sendersAndCloseStatusCur.isClosedForSend0

            val s = sendersAndCloseStatusCur.counter
            val id = s / SEGMENT_SIZE
            val i = (s % SEGMENT_SIZE).toInt()
            if (segm.id != id) {
                segm = findSegmentSend(id, segm) ?:
                    if (closed) return onClosed() else continue
            }

            when(updateCellSend(segm, i, element, s, if (closed) INTERRUPTED else waiter)) {
                RESULT_RENDEZVOUS -> {
                    segm.cleanPrev()
                    return onRendezvous()
                }
                RESULT_BUFFERED -> {
                    return onRendezvous()
                }
                RESULT_SUSPEND -> {
                    if (closed) return onClosed()
                    return onSuspend(segm, i)
                }
                RESULT_FAILED -> {
                    if (closed) return onClosed()
                    continue
                }
                RESULT_NO_WAITER -> {
                    return onNoWaiter(segm, i, element, s)
                }
            }
        }
    }

    private inline fun <R, W : Any> sendImplOnNoWaiter(
        segm: ChannelSegment<E>,
        i: Int,
        element: E,
        s: Long,
        waiter: W,
        onRendezvous: () -> R,
        onSuspend: (segm: ChannelSegment<E>, i: Int) -> R,
        onClosed: () -> R,
    ): R {
        when(updateCellSend(segm, i, element, s, waiter)) {
            RESULT_RENDEZVOUS -> {
                segm.cleanPrev()
                return onRendezvous()
            }
            RESULT_BUFFERED -> {
                return onRendezvous()
            }
            RESULT_SUSPEND -> {
                return onSuspend(segm, i)
            }
            RESULT_FAILED -> {
                return sendImpl(
                    element = element,
                    waiter = waiter,
                    onRendezvous = onRendezvous,
                    onSuspend = onSuspend,
                    onClosed = onClosed,
                )
            }
            else -> error("enexpected")
        }
    }

    private fun <W> updateCellSend(
        segm: ChannelSegment<E>,
        i: Int,
        element: E,
        s: Long,
        waiter: W,
    ): Int {
        val curState = segm.getState(i)
        when {
            curState === null -> {
                segm.storeElement(i, element)
                val rendezvous = unlimited || s < bufferEnd.value || s < receivers.value
                if (rendezvous) {
                    if (segm.casState(i, null, BUFFERED)) {
                        return RESULT_BUFFERED
                    }
                } else {
                    if (waiter == null) return RESULT_NO_WAITER
                    if (segm.casState(i, null, waiter)) return RESULT_SUSPEND
                }
            }
            curState is Waiter -> {
                segm.setState(i, DONE) // we can safely avoid CAS here
                return if (curState.tryResumeReceiver(element)) {
                    onReceiveDequeued()
                    RESULT_RENDEZVOUS
                } else RESULT_FAILED
            }
        }
        return updateCellSendSlow(segm, i, element, s, waiter)
    }

    private fun <W> updateCellSendSlow(
        segm: ChannelSegment<E>,
        i: Int,
        element: E,
        s: Long,
        waiter: W,
    ): Int {
        segm.storeElement(i, element)
        while (true) {
            val state = segm.getState(i)
            when {
                state === null -> {
                    val rendezvous = unlimited || s < bufferEnd.value || s < receivers.value
                    if (rendezvous) {
                        if (segm.casState(i, null, BUFFERED)) {
                            return RESULT_BUFFERED
                        }
                    } else {
                        if (waiter === null) return RESULT_NO_WAITER
                        if (segm.casState(i, null, waiter)) return RESULT_SUSPEND
                    }
                }
                state === BUFFERING -> {
                    if (segm.casState(i, state, BUFFERED)) return RESULT_RENDEZVOUS
                }
                state === BROKEN || state === INTERRUPTED || state === INTERRUPTED_EB || state === INTERRUPTED_R || state === CHANNEL_CLOSED -> {
                    segm.setElementLazy(i, null)
                    return RESULT_FAILED
                }
                else -> {
                    segm.setState(i, DONE) // we can safely avoid CAS here
                    segm.setElementLazy(i, null)
                    val receiver = if (state is WaiterEB) state.waiter else state
                    return if (receiver.tryResumeReceiver(element)) {
                        onReceiveDequeued()
                        RESULT_RENDEZVOUS
                    } else RESULT_FAILED
                }
            }
        }
    }

    private fun Any.tryResumeReceiver(element: E): Boolean {
        return when(this) {
            is SelectInstance<*> -> {
                this.trySelect(this@BufferedChannel, element)
            }
            is ReceiveCatching<*> -> {
                this as ReceiveCatching<E>
                this.receiver.tryResume(success(element), null, onUndeliveredElement?.bindCancellationFun(element, receiver.context)).let {
                    if (it !== null) {
                        this.receiver.completeResume(it)
                        true
                    } else false
                }
            }
            is BufferedChannel<*>.BufferedChannelIterator -> {
                this as BufferedChannel<E>.BufferedChannelIterator
                this.tryResumeHasNext(element)
            }
            is CancellableContinuation<*> -> {
                this as CancellableContinuation<E>
                tryResume(element, null, onUndeliveredElement?.bindCancellationFun(element, context)).let {
                    if (it !== null) {
                        completeResume(it)
                        true
                    } else false
                }
            }
            else -> error("Unexpected waiter: $this")
        }
    }

    private fun findSegmentSend(id: Long, start: ChannelSegment<E>) =
        sendSegment.findSegmentAndMoveForward(id, start, ::createSegment).let {
            if (it.isClosed) {
                completeCloseOrCancel()
                null
            } else {
                val segm = it.segment
                if (segm.id != id) {
                    updateSendersIfLower(segm.id * SEGMENT_SIZE)
                    null
                } else segm
            }
        }

    private fun updateSendersIfLower(value: Long): Unit =
        sendersAndCloseStatus.loop { cur ->
            val curCounter = cur.counter
            if (curCounter >= value) return
            val update = constructSendersAndCloseStatus(curCounter, cur.closeStatus)
            if (sendersAndCloseStatus.compareAndSet(cur, update)) return
        }

    override suspend fun receive(): E = receiveImpl(
        waiter = null,
        onRendezvous = { element ->
            onReceiveSynchronizationCompletion()
            return element
        },
        onSuspend = { _, _ -> error("unexpected") },
        onClosed = {
            onClosedReceive()
        },
        onNoWaiter = { segm, i, r ->
            receiveOnNoWaiterSuspend(segm, i, r)
        }
    )

    private fun onClosedReceive(): E =
        throw recoverStackTrace(receiveException(getCause())).also { onReceiveSynchronizationCompletion() }

    private suspend fun receiveOnNoWaiterSuspend(
        segm: ChannelSegment<E>,
        i: Int,
        r: Long
    ) = suspendCancellableCoroutineReusable<E> { cont ->
        receiveImplOnNoWaiter(
            segm, i, r,
            waiter = cont,
            onRendezvous = { element ->
                onReceiveSynchronizationCompletion()
                cont.resume(element) {
                    onUndeliveredElement?.callUndeliveredElement(element, cont.context)
                }
            },
            onSuspend = { segm, i ->
                onReceiveEnqueued()
                onReceiveSynchronizationCompletion()
                cont.invokeOnCancellation {
                    segm.onCancellation(i);
                    onReceiveDequeued()
                }
            },
            onClosed = {
                onReceiveSynchronizationCompletion()
                cont.resumeWithException(receiveException(getCause()))
            },
        )
    }

    override suspend fun receiveCatching(): ChannelResult<E> = receiveImpl(
        waiter = null,
        onRendezvous = { element -> onReceiveSynchronizationCompletion(); success(element) },
        onSuspend = { _, _ -> error("unexcepted") },
        onClosed = { onClosedReceiveCatching() },
        onNoWaiter = { segm, i, r -> receiveCatchingOnNoWaiterSuspend(segm, i, r) }
    )

    private fun onClosedReceiveCatching(): ChannelResult<E> =
        closed<E>(getCause()).also { onReceiveSynchronizationCompletion() }

    private suspend fun receiveCatchingOnNoWaiterSuspend(
        segm: ChannelSegment<E>,
        i: Int,
        r: Long
    ) = suspendCancellableCoroutineReusable<ChannelResult<E>> { cont ->
        val waiter = ReceiveCatching(cont)
        receiveImplOnNoWaiter(
            segm, i, r,
            waiter = waiter,
            onRendezvous = { element ->
                onReceiveSynchronizationCompletion()
                cont.resume(success(element)) {
                    onUndeliveredElement?.callUndeliveredElement(element, cont.context)
                }
            },
            onSuspend = { segm, i ->
                onReceiveEnqueued()
                onReceiveSynchronizationCompletion()
                cont.invokeOnCancellation {
                    segm.onCancellation(i);
                    onReceiveDequeued()
                }
            },
            onClosed = {
                onReceiveSynchronizationCompletion()
                cont.resume(closed(getCause()))
            },
        )
    }

    override fun tryReceive(): ChannelResult<E> =
        tryReceiveInternal().also { onReceiveSynchronizationCompletion() }

    protected fun tryReceiveInternal(): ChannelResult<E> {
        // Read `receivers` counter first.
        val r = receivers.value
        val sendersAndCloseStatusCur = sendersAndCloseStatus.value
        // Is this channel is closed for send?
        if (sendersAndCloseStatusCur.isClosedForReceive0) return onClosedTryReceive()
        // COMMENTS
        val s = sendersAndCloseStatusCur.counter
        if (r >= s) return failure()
        return receiveImpl(
            waiter = INTERRUPTED,
            onRendezvous = { element -> success(element) },
            onSuspend = { _, _ -> failure() },
            onClosed = { onClosedTryReceive() }
        )
    }

    private fun onClosedTryReceive(): ChannelResult<E> =
        closed(getCause())

    private inline fun <R> receiveImpl(
        waiter: Any?,
        onRendezvous: (element: E) -> R,
        onSuspend: (segm: ChannelSegment<E>, i: Int) -> R,
        onClosed: () -> R,
        onNoWaiter: (
            segm: ChannelSegment<E>,
            i: Int,
            r: Long
        ) -> R = { _, _, _ -> error("unexpected") }
    ): R {
        var segm = receiveSegment.value
        while (true) {
            if (sendersAndCloseStatus.value.closeStatus == CLOSE_STATUS_CANCELLED)
                return onClosed()
            val r = this.receivers.getAndIncrement()
            val id = r / SEGMENT_SIZE
            val i = (r % SEGMENT_SIZE).toInt()
            if (segm.id != id) {
                val findSegmResult = findSegmentReceive(id, segm)
                if (findSegmResult.isClosed) {
                    return onClosed()
                }
                segm = findSegmResult.segment
                if (segm.id != id) continue
            }
            val result = updateCellReceive(segm, i, r, waiter)
            when {
                result === SUSPEND -> {
                    return onSuspend(segm, i)
                }
                result === FAILED -> {
                    continue
                }
                result !== NO_WAITER -> { // element
                    segm.cleanPrev()
                    return onRendezvous(result as E)
                }
                result === NO_WAITER -> {
                    return onNoWaiter(segm, i, r)
                }
            }
        }
    }

    private inline fun <W, R> receiveImplOnNoWaiter(
        segm: ChannelSegment<E>,
        i: Int,
        r: Long,
        waiter: W,
        onRendezvous: (element: E) -> R,
        onSuspend: (segm: ChannelSegment<E>, i: Int) -> R,
        onClosed: () -> R
    ): R {
        val result = updateCellReceive(segm, i, r, waiter)
        when {
            result === SUSPEND -> {
                return onSuspend(segm, i)
            }
            result === FAILED -> {
                return receiveImpl(
                    waiter = waiter,
                    onRendezvous = onRendezvous,
                    onSuspend = onSuspend,
                    onClosed = onClosed
                )
            }
            else -> {
                segm.cleanPrev()
                return onRendezvous(result as E)
            }
        }
    }

    private fun updateCellReceive(
        segment: ChannelSegment<E>,
        i: Int,
        r: Long,
        waiter: Any?,
    ): Any? {
        val curState = segment.getState(i)
        when {
            curState === BUFFERED -> {
                if (segment.casState(i, curState, DONE)) {
                    val element = segment.retrieveElement(i)
                    expandBuffer()
                    return element
                }
            }
            curState === null -> {
                if (waiter == null) return NO_WAITER
                val sendersAndCloseStatusCur = sendersAndCloseStatus.value
                if (sendersAndCloseStatusCur.closeStatus == CLOSE_STATUS_ACTIVE &&
                    r >= sendersAndCloseStatusCur.counter &&
                    segment.casState(i, curState, waiter)
                ) {
                    expandBuffer()
                    return SUSPEND
                }
            }
            curState is Waiter -> if (segment.casState(i, curState, RESUMING_R)) {
                return if (curState.tryResumeSender(segment, i)) {
                    segment.setState(i, DONE)
                    expandBuffer()
                    return segment.retrieveElement(i)
                } else {
                    onSenderResumptionFailure(segment, i, false)
                    FAILED
                }
            }
        }
        return updateCellReceiveSlow(segment, i, r, waiter)
    }

    private fun updateCellReceiveSlow(
        segment: ChannelSegment<E>,
        i: Int,
        r: Long,
        waiter: Any?,
    ): Any? {
        while (true) {
            val state = segment.getState(i)
            when {
                state === null || state === BUFFERING -> {
                    val sendersAndCloseStatusCur = sendersAndCloseStatus.value
                    if (sendersAndCloseStatusCur.isClosedForReceive0) {
                        segment.casState(i, state, CHANNEL_CLOSED)
                        continue
                    }
                    if (r < sendersAndCloseStatusCur.counter) {
                        if (segment.casState(i, state, BROKEN)) {
                            expandBuffer()
                            return FAILED
                        }
                    } else {
                        if (waiter === null) return NO_WAITER
                        if (segment.casState(i, state, waiter)) {
                            expandBuffer()
                            return SUSPEND
                        }
                    }
                }
                state === BUFFERED -> {
                    if (segment.casState(i, state, DONE)) {
                        val element = segment.retrieveElement(i)
                        expandBuffer()
                        return element
                    }
                }
                state === INTERRUPTED -> {
                    if (segment.casState(i, state, INTERRUPTED_R)) return FAILED
                }
                state === INTERRUPTED_EB -> {
                    expandBuffer()
                    return FAILED
                }
                state === INTERRUPTED_R -> return FAILED
                state === BROKEN -> return FAILED
                state === CHANNEL_CLOSED -> return FAILED
                state === RESUMING_EB -> continue // spin-wait
                else -> {
                    if (segment.casState(i, state, RESUMING_R)) {
                        val helpExpandBuffer = state is WaiterEB
                        val sender = if (state is WaiterEB) state.waiter else state
                        if (sender.tryResumeSender(segment, i)) {
                            segment.setState(i, DONE)
                            return segment.retrieveElement(i).also { expandBuffer() }
                        } else {
                            onSenderResumptionFailure(segment, i, helpExpandBuffer)
                            return FAILED
                        }
                    }
                }
            }
        }
    }

    private fun onSenderResumptionFailure(
        segment: ChannelSegment<E>,
        i: Int,
        helpExpandBuffer: Boolean
    ) {
        if (!segment.casState(i, RESUMING_R, INTERRUPTED_R) || helpExpandBuffer)
            expandBuffer()
    }

    private fun Any.tryResumeSender(segment: ChannelSegment<E>, i: Int): Boolean = when {
        this is SelectInstance<*> -> {
            this as SelectImplementation<*>
            when (this.trySelectDetailed(this@BufferedChannel, Unit)) {
                SUCCESSFUL -> true
                REREGISTER -> {
                    false
                }
                ALREADY_SELECTED, CANCELLED -> {
                    onUndeliveredElement?.invoke(segment.retrieveElement(i))
                    false
                }
            }
        }
        this is CancellableContinuation<*> -> {
            this as CancellableContinuation<Unit>
            tryResume(Unit).let {
                if (it !== null) {
                    completeResume(it)
                    true
                } else {
                    onUndeliveredElement?.invoke(segment.retrieveElement(i))
                    false
                }
            }
        }
        else -> error("Unexpected waiter: $this")
    }

    private fun findSegmentReceive(id: Long, start: ChannelSegment<E>): SegmentOrClosed<ChannelSegment<E>> =
        receiveSegment.findSegmentAndMoveForward(id, start, ::createSegment).also {
            if (it.isClosed) {
                completeCloseOrCancel()
            } else {
                if (it.segment.id != id)
                    updateReceiversIfLower(it.segment.id * SEGMENT_SIZE)
            }
        }

    private fun findSegmentHasElements(id: Long, start: ChannelSegment<E>) =
        receiveSegment.findSegmentAndMoveForward(id, start, ::createSegment)

    private fun updateReceiversIfLower(value: Long): Unit =
        receivers.loop { cur ->
            if (cur >= value) return
            if (receivers.compareAndSet(cur, value)) return
        }

    private fun expandBuffer() {
        if (rendezvous || unlimited) return
        var segm = bufferEndSegment.value!!
        try_again@ while (true) {
            val b = bufferEnd.getAndIncrement()
            val s = sendersAndCloseStatus.value.counter
            if (s <= b) return
            val id = b / SEGMENT_SIZE
            val i = (b % SEGMENT_SIZE).toInt()
            if (segm.id != id) {
                segm = findSegmentBuffer(id, segm).let {
                    if (it.isClosed) return else it.segment
                }
            }
            if (segm.id != id) {
                bufferEnd.compareAndSet(b + 1, segm.id * SEGMENT_SIZE)
                continue@try_again
            }
            if (updateCellExpandBuffer(segm, i, b)) return
        }
    }

    private fun updateCellExpandBuffer(
        segm: ChannelSegment<E>,
        i: Int,
        b: Long
    ): Boolean {
        while (true) {
            val state = segm.getState(i)
            when {
                state === null -> {
                    if (segm.casState(i, segm, BUFFERING)) return true
                }
                state === BUFFERED || state === BROKEN || state === DONE || state === CHANNEL_CLOSED -> return true
                state === RESUMING_R -> if (segm.casState(i, state, RESUMING_R_EB)) return true
                state === INTERRUPTED -> {
                    if (b >= receivers.value) return false
                    if (segm.casState(i, state, INTERRUPTED_EB)) return true
                }
                state === INTERRUPTED_R -> return false
                else -> {
                    if (b < receivers.value) {
                        if (segm.casState(i, state, WaiterEB(waiter = state))) return true
                    } else {
                        if (segm.casState(i, state, RESUMING_EB)) {
                            return if (state.tryResumeSender(segm, i)) {
                                segm.setState(i, BUFFERED)
                                true
                            } else {
                                segm.setState(i, INTERRUPTED)
                                false
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findSegmentBuffer(id: Long, start: ChannelSegment<E>) =
        (bufferEndSegment as AtomicRef<ChannelSegment<E>>).findSegmentAndMoveForward(id, start, ::createSegment)


    // #######################
    // ## Select Expression ##
    // #######################

    override val onSend: SelectClause2<E, BufferedChannel<E>>
        get() = SelectClause2Impl(
            clauseObject = this@BufferedChannel,
            regFunc = BufferedChannel<*>::registerSelectForSend as RegistrationFunction,
            processResFunc = BufferedChannel<*>::processResultSelectSend as ProcessResultFunction
        )

    override val onReceive: SelectClause1<E>
        get() = SelectClause1Impl(
            clauseObject = this@BufferedChannel,
            regFunc = BufferedChannel<*>::registerSelectForReceive as RegistrationFunction,
            processResFunc = BufferedChannel<*>::processResultSelectReceive as ProcessResultFunction,
            onCancellationConstructor = onUndeliveredElementReceiveCancellationConstructor
        )

    override val onReceiveCatching: SelectClause1<ChannelResult<E>>
        get() = SelectClause1Impl(
            clauseObject = this@BufferedChannel,
            regFunc = BufferedChannel<*>::registerSelectForReceive as RegistrationFunction,
            processResFunc = BufferedChannel<*>::processResultSelectReceiveCatching as ProcessResultFunction,
            onCancellationConstructor = onUndeliveredElementReceiveCancellationConstructor
        )

    protected open fun registerSelectForSend(select: SelectInstance<*>, element: Any?) {
        sendImpl(
            element = element as E,
            waiter = select,
            onRendezvous = { select.selectInRegistrationPhase(Unit) },
            onSuspend = { segm, i ->
                select.disposeOnCompletion {
                    segm.onCancellation(i, onUndeliveredElement, select.context, select)
                }
            },
            onClosed = {
                select.selectInRegistrationPhase(CHANNEL_CLOSED)
            }
        )
    }

    protected open fun registerSelectForReceive(select: SelectInstance<*>, ignoredParam: Any?) {
        receiveImpl(
            waiter = select,
            onRendezvous = { elem ->
                onReceiveSynchronizationCompletion()
                select.selectInRegistrationPhase(elem)
           },
            onSuspend = { segm, i ->
                onReceiveEnqueued()
                onReceiveSynchronizationCompletion()
                select.disposeOnCompletion { segm.onCancellation(i) }
            },
            onClosed = {
                onReceiveSynchronizationCompletion();
                select.selectInRegistrationPhase(CHANNEL_CLOSED)
            }
        )
    }

    protected open fun onReceiveSynchronizationCompletion() {}

    private fun processResultSelectSend(ignoredParam: Any?, selectResult: Any?): Any? =
        if (selectResult === CHANNEL_CLOSED) throw sendException(getCause())
        else this

    private fun processResultSelectReceive(ignoredParam: Any?, selectResult: Any?): Any? =
        if (selectResult === CHANNEL_CLOSED) throw receiveException(getCause())
        else selectResult

    private fun processResultSelectReceiveOrNull(ignoredParam: Any?, selectResult: Any?): Any? =
        if (selectResult === CHANNEL_CLOSED) {
            if (closeCause.value !== null) throw receiveException(getCause())
            null
        } else selectResult

    private fun processResultSelectReceiveCatching(ignoredParam: Any?, selectResult: Any?): Any? =
        if (selectResult === CHANNEL_CLOSED) closed(closeCause.value as Throwable?)
        else success(selectResult as E)

    private val onUndeliveredElementReceiveCancellationConstructor: OnCancellationConstructor? = onUndeliveredElement?.let {
        { select: SelectInstance<*>, ignoredParam: Any?, element: Any? ->
            { cause: Throwable -> if (element !== CHANNEL_CLOSED) onUndeliveredElement.callUndeliveredElement(element as E, select.context) }
        }
    }

    // ##############################
    // ## Closing and Cancellation ##
    // ##############################

    /**
     * Indicates whether this channel is cancelled. In case it is cancelled,
     * it stores either an exception if it was cancelled with or `null` if
     * this channel was cancelled without error. Stores [NO_CLOSE_CAUSE] if this
     * channel is not cancelled.
     */
    private val closeCause = atomic<Any?>(NO_CLOSE_CAUSE)

    private fun getCause() = closeCause.value as Throwable?

    private fun receiveException(cause: Throwable?) =
        cause ?: ClosedReceiveChannelException(DEFAULT_CLOSE_MESSAGE)
    protected fun sendException(cause: Throwable?) =
        cause ?: ClosedSendChannelException(DEFAULT_CLOSE_MESSAGE)

    // Stores the close handler.
    private val closeHandler = atomic<Any?>(null)

    private fun markClosed(): Unit =
        sendersAndCloseStatus.update { cur ->
            when (cur.closeStatus) {
                CLOSE_STATUS_ACTIVE ->
                    constructSendersAndCloseStatus(cur.counter, CLOSE_STATUS_CLOSED)
                CLOSE_STATUS_CANCELLATION_STARTED ->
                    constructSendersAndCloseStatus(cur.counter, CLOSE_STATUS_CANCELLED)
                else -> return
            }
        }

    private fun markCancelled(): Unit =
        sendersAndCloseStatus.update { cur ->
            constructSendersAndCloseStatus(cur.counter, CLOSE_STATUS_CANCELLED)
        }

    private fun markCancellationStarted(): Unit =
        sendersAndCloseStatus.update { cur ->
            if (cur.closeStatus == CLOSE_STATUS_ACTIVE)
                constructSendersAndCloseStatus(cur.counter, CLOSE_STATUS_CANCELLATION_STARTED)
            else return
        }

    private fun completeCloseOrCancel() {
        sendersAndCloseStatus.value.isClosedForSend0
    }

    override fun close(cause: Throwable?): Boolean = closeImpl(cause, false)

    protected open fun closeImpl(cause: Throwable?, cancel: Boolean): Boolean {
        if (cancel) markCancellationStarted()
        val closedByThisOperation = closeCause.compareAndSet(NO_CLOSE_CAUSE, cause)
        if (cancel) markCancelled() else markClosed()
        completeCloseOrCancel()
        return if (closedByThisOperation) {
            invokeCloseHandler()
            true
        } else false
    }

    private fun completeClose(sendersCur: Long) {
        val segm = closeQueue()
        removeWaitingRequests(segm, sendersCur)
        onClosedIdempotent()
    }

    private fun completeCancel(sendersCur: Long) {
        completeClose(sendersCur)
        removeRemainingBufferedElements()
    }

    private fun closeQueue(): ChannelSegment<E> {
        var segm = bufferEndSegment.value
        sendSegment.value.let {
            val segm0 = segm
            if (segm0 == null || it.id > segm0.id) segm = it
        }
        return segm!!.close()
    }

    private fun invokeCloseHandler() {
        val closeHandler = closeHandler.getAndUpdate {
            if (it === null) CLOSE_HANDLER_CLOSED
            else CLOSE_HANDLER_INVOKED
        } ?: return
        closeHandler as (cause: Throwable?) -> Unit
        val closeCause = closeCause.value as Throwable?
        closeHandler(closeCause)
    }

    override fun invokeOnClose(handler: (cause: Throwable?) -> Unit) {
        if (closeHandler.compareAndSet(null, handler)) {
            // Handler has been successfully set, finish the operation.
            return
        }
        // Either handler was set already or this channel is cancelled.
        // Read the value of [closeHandler] and either throw [IllegalStateException]
        // or invoke the handler respectively.
        when (val curHandler = closeHandler.value) {
            CLOSE_HANDLER_CLOSED -> {
                // In order to be sure that our handler is the only one, we have to change the
                // [closeHandler] value to `INVOKED`. If this CAS fails, another handler has been
                // executed and an [IllegalStateException] should be thrown.
                if (closeHandler.compareAndSet(CLOSE_HANDLER_CLOSED, CLOSE_HANDLER_INVOKED)) {
                    handler(closeCause.value as Throwable?)
                } else {
                    throw IllegalStateException("Another handler was already registered and successfully invoked")
                }
            }
            CLOSE_HANDLER_INVOKED -> {
                throw IllegalStateException("Another handler was already registered and successfully invoked")
            }
            else -> {
                throw IllegalStateException("Another handler was already registered: $curHandler")
            }
        }
    }

    /**
     * Invoked when channel is closed as the last action of [close] invocation.
     * This method should be idempotent and can be called multiple times.
     */
    protected open fun onClosedIdempotent() {}

    protected open fun onCancel(wasClosed: Boolean) {}

    final override fun cancel(cause: Throwable?): Boolean = cancelImpl(cause)
    final override fun cancel() { cancelImpl(null) }
    final override fun cancel(cause: CancellationException?) { cancelImpl(cause) }

    protected open fun cancelImpl(cause: Throwable?): Boolean {
        val cause = cause ?: CancellationException("Channel was cancelled")
        val wasClosed = closeImpl(cause, true)
        removeRemainingBufferedElements()
        onCancel(wasClosed)
        return wasClosed
    }

    private fun removeRemainingBufferedElements() {
        // clear buffer first, but do not wait for it in helpers
        val onUndeliveredElement = onUndeliveredElement
        var undeliveredElementException: UndeliveredElementException? = null // first cancel exception, others suppressed

        var segm: ChannelSegment<E> = sendSegment.value
        while (true) {
            segm = segm.next ?: break
        }
        while (true) {
            for (i in SEGMENT_SIZE - 1 downTo 0) {
                if (segm.id * SEGMENT_SIZE + i < receivers.value) return
                while (true) {
                    val state = segm.getState(i)
                    when {
                        state === BUFFERED -> if (segm.casState(i, state, CHANNEL_CLOSED)) {
                            if (onUndeliveredElement != null) {
                                undeliveredElementException = onUndeliveredElement.callUndeliveredElementCatchingException(segm.retrieveElement(i), undeliveredElementException)
                            }
                            segm.onCancellation(i)
                            break
                        }
                        state === BUFFERING || state === null -> if (segm.casState(i, state, CHANNEL_CLOSED)) {
                            segm.onCancellation(i)
                            break
                        }
                        state is WaiterEB -> {
                            if (segm.casState(i, state, CHANNEL_CLOSED)) {
                                state.waiter.closeSender()
                                break
                            }
                        }
                        state is CancellableContinuation<*> || state is SelectInstance<*>  -> {
                            if (segm.casState(i, state, CHANNEL_CLOSED)) {
                                state.closeSender()
                                break
                            }
                        }
                        else -> break
                    }
                }
            }
            segm = segm.prev ?: break
        }
        undeliveredElementException?.let { throw it } // throw UndeliveredElementException at the end if there was one
    }

    private fun removeWaitingRequests(lastSegment: ChannelSegment<E>, sendersCur: Long) {
        var segm: ChannelSegment<E>? = lastSegment
        while (segm != null) {
            for (i in SEGMENT_SIZE - 1 downTo 0) {
                if (segm.id * SEGMENT_SIZE + i < sendersCur) return
                cell@while (true) {
                    val state = segm.getState(i)
                    when {
                        state === null || state === BUFFERING -> {
                            if (segm.casState(i, state, CHANNEL_CLOSED)) break@cell
                        }
                        state is WaiterEB -> {
                            if (segm.casState(i, state, CHANNEL_CLOSED)) {
                                if (state.waiter.closeReceiver()) expandBuffer()
                                break@cell
                            }
                        }
                        state is Waiter -> {
                            if (segm.casState(i, state, CHANNEL_CLOSED)) {
                                if (state.closeReceiver()) expandBuffer()
                                break@cell
                            }
                        }
                        else -> break@cell
                    }
                }
            }
            segm = segm.prev
        }
    }

    private fun Any.closeReceiver() = closeWaiter(receiver = true)
    private fun Any.closeSender() = closeWaiter(receiver = false)

    private fun Any.closeWaiter(receiver: Boolean): Boolean {
        val cause = getCause()
        return when (this) {
            is CancellableContinuation<*> -> {
                val exception = if (receiver) receiveException(cause) else sendException(cause)
                this.tryResumeWithException(exception)?.also { this.completeResume(it) }.let { it !== null }
            }
            is ReceiveCatching<*> -> {
                this.receiver.tryResume(closed(cause))?.also { this.receiver.completeResume(it) }.let { it !== null }
            }
            is BufferedChannel<*>.BufferedChannelIterator -> {
                receiveResult = ClosedChannel(cause)
                val cont = this.cont!!
                if (cause == null) {
                    cont.tryResume(false)?.also { cont.completeResume(it); this.cont = null }.let { it !== null }
                } else {
                    cont.tryResumeWithException(cause)?.also { cont.completeResume(it); this.cont = null }.let { it !== null }
                }
            }
            is SelectInstance<*> -> this.trySelect(this@BufferedChannel, CHANNEL_CLOSED)
            else -> error("Unexpected waiter: $this")
        }
    }


    // ######################
    // ## Iterator Support ##
    // ######################

    override fun iterator(): ChannelIterator<E> = BufferedChannelIterator()

    internal open inner class BufferedChannelIterator : ChannelIterator<E>, CancelHandler(), Waiter {
        @JvmField
        var receiveResult: Any? = null
        @JvmField
        var cont: CancellableContinuation<Boolean>? = null

        private var segment: ChannelSegment<E>? = null
        private var i = -1
        // on cancellation
        override fun invoke(cause: Throwable?) {
            segment?.onCancellation(i)
            onReceiveDequeued()
        }

        override suspend fun hasNext(): Boolean = receiveImpl(
            waiter = null,
            onRendezvous = { element ->
                this.receiveResult = element.elementAsState()
                onReceiveSynchronizationCompletion()
                true
            },
            onSuspend = { _, _ -> error("unreachable") },
            onClosed = { onCloseHasNext() },
            onNoWaiter = { segm, i, r -> hasNextSuspend(segm, i, r) }
        )

        private fun onCloseHasNext(): Boolean {
            val cause = getCause()
            onReceiveSynchronizationCompletion()
            this.receiveResult = ClosedChannel(cause)
            if (cause == null) return false
            else throw recoverStackTrace(cause)
        }

        private suspend fun hasNextSuspend(
            segm: ChannelSegment<E>,
            i: Int,
            r: Long
        ): Boolean = suspendCancellableCoroutineReusable { cont ->
            this.cont = cont
            receiveImplOnNoWaiter(
                segm, i, r,
                waiter = this,
                onRendezvous = { element ->
                    this.receiveResult = element
                    this.cont = null
                    onReceiveSynchronizationCompletion()
                    cont.resume(true) {
                        onUndeliveredElement?.callUndeliveredElement(element, cont.context)
                    }
                },
                onSuspend = { segment, i ->
                    this.segment = segment
                    this.i = i
                    cont.invokeOnCancellation(this.asHandler)
                    onReceiveEnqueued()
                    onReceiveSynchronizationCompletion()
                },
                onClosed = {
                    this.cont = null
                    val cause = getCause()
                    this.receiveResult = ClosedChannel(cause)
                    onReceiveSynchronizationCompletion()
                    if (cause == null) {
                        cont.resume(false)
                    } else {
                        cont.resumeWithException(recoverStackTrace(cause))
                    }
                }
            )
        }

        @Suppress("UNCHECKED_CAST")
        override fun next(): E {
            // Read the already received result, or null if [hasNext] has not been invoked yet.
            val result = receiveResult ?: error("`hasNext()` has not been invoked")
            receiveResult = null
            // Is this channel closed?
            if (result is ClosedChannel) throw recoverStackTrace(receiveException(result.cause))
            // Return the element.
            return result.asElement()
        }

        fun tryResumeHasNext(element: E): Boolean {
            this.receiveResult = element
            val cont = this.cont!!
            this.cont = null
            return cont.tryResume(true, null, onUndeliveredElement?.bindCancellationFun(element, cont.context)).let {
                if (it !== null) {
                    cont.completeResume(it)
                    true
                } else false
            }
        }
    }
    private class ClosedChannel(@JvmField val cause: Throwable?)

    // #################################################
    // # isClosedFor[Send,Receive] and isEmpty SUPPORT #
    // #################################################

    @ExperimentalCoroutinesApi
    override val isClosedForSend: Boolean
        get() = sendersAndCloseStatus.value.isClosedForSend0

    private val Long.isClosedForSend0 get() =
        isClosed(this, sendersCur = this.counter, isClosedForReceive = false)

    @ExperimentalCoroutinesApi
    override val isClosedForReceive: Boolean
        get() = sendersAndCloseStatus.value.isClosedForReceive0

    private val Long.isClosedForReceive0 get() =
        isClosed(this, sendersCur = this.counter, isClosedForReceive = true)

    private fun isClosed(
        sendersAndCloseStatusCur: Long,
        sendersCur: Long,
        isClosedForReceive: Boolean
    ) = when (sendersAndCloseStatusCur.closeStatus) {
        // This channel is active and has not been closed.
        CLOSE_STATUS_ACTIVE -> false
        // The cancellation procedure has been started but
        // not linearized yet, so this channel should be
        // considered as active.
        CLOSE_STATUS_CANCELLATION_STARTED -> false
        // This channel has been successfully closed.
        // Help to complete the closing procedure to
        // guarantee linearizability, and return `true`
        // for senders or the flag whether there still
        // exist elements to retrieve for receivers.
        CLOSE_STATUS_CLOSED -> {
            completeClose(sendersCur)
            // When `isClosedForReceive` is `false`, always return `true`.
            // Otherwise, it is possible that the channel is closed but
            // still has elements to retrieve.
            if (isClosedForReceive) !hasElements() else true
        }
        // This channel has been successfully cancelled.
        // Help to complete the cancellation procedure to
        // guarantee linearizability and return `true`.
        CLOSE_STATUS_CANCELLED -> {
            completeCancel(sendersCur)
            true
        }
        else -> error("unexpected close status: ${sendersAndCloseStatusCur.closeStatus}")
    }

    @ExperimentalCoroutinesApi
    override val isEmpty: Boolean get() =
        // TODO: is it linearizable? If so,
        // TODO: I have no idea why.
        if (sendersAndCloseStatus.value.isClosedForReceive0) false
        else if (hasElements()) false
        else !sendersAndCloseStatus.value.isClosedForReceive0

    /**
     * Checks whether this channel contains elements to retrieve.
     * Unfortunately, simply comparing the counters is not sufficient,
     * as there can be cells in INTERRUPTED state due to cancellation.
     * Therefore, this function tries to fairly find the first element,
     * updating the `receivers` counter correspondingly.
     */
    private fun hasElements(): Boolean {
        // Read the segment before accessing `receivers` counter.
        var segm = receiveSegment.value
        while (true) {
            // Is there a chance that this channel has elements?
            val r = receivers.value
            val s = sendersAndCloseStatus.value.counter
            if (s <= r) return false // no elements
            // Try to access the `r`-th cell.
            // Get the corresponding segment first.
            val id = r / SEGMENT_SIZE
            if (segm.id != id) {
                // Find the required segment, and retry the operation when
                // the segment with the specified id has not been found
                // due to be full of cancelled cells. Also, when the segment
                // has not been found and the channel is already closed,
                // complete with `false`.
                segm = findSegmentHasElements(id, segm).let {
                    if (it.isClosed) return false
                    if (it.segment.id != id) {
                        updateReceiversIfLower(it.segment.id * SEGMENT_SIZE)
                        null
                    } else it.segment
                } ?: continue
            }
            // Does the `r`-th cell contain waiting sender or buffered element?
            val i = (r % SEGMENT_SIZE).toInt()
            if (!isCellEmpty(segm, i, r)) return true
            // The cell is empty. Update `receivers` counter and try again.
            receivers.compareAndSet(r, r + 1)
        }
    }

    /**
     * Checks whether this cell contains a buffered element
     * or a waiting sender, returning `false` in this case.
     * Otherwise, if this cell is empty (due to waiter cancellation,
     * channel closing, or marking it as `BROKEN`), the operation
     * returns `true`.
     */
    private fun isCellEmpty(
        segm: ChannelSegment<E>,
        i: Int, // the cell index in `segm`
        r: Long // the global cell index
    ): Boolean {
        // The logic is similar to `updateCellReceive` with the only difference
        // that this operation does not change the state and retrieve the element.
        // TODO: simplify the conditions and document them.
        while (true) {
            val state = segm.getState(i)
            when {
                state === null || state === BUFFERING -> {
                    if (segm.casState(i, state, BROKEN)) {
                        expandBuffer()
                        return true
                    }
                }
                state === BUFFERED -> {
                    return false
                }
                state === INTERRUPTED -> {
                    if (segm.casState(i, state, INTERRUPTED_R)) return true
                }
                state === INTERRUPTED_EB -> return true
                state === INTERRUPTED_R -> return true
                state === CHANNEL_CLOSED -> return true
                state === DONE -> return true
                state === BROKEN -> return true
                state === RESUMING_EB || state === RESUMING_R_EB -> continue // spin-wait
                else -> return receivers.value != r
            }
        }
    }

    // ##################
    // # FOR DEBUG INFO #
    // ##################

    internal val receiversCounter: Long get() = receivers.value
    internal val sendersCounter: Long get() = sendersAndCloseStatus.value.counter

    // Returns a debug representation of this channel,
    // which we actively use in Lincheck tests.
    override fun toString(): String {
        val data = arrayListOf<String>()
        val head = if (receiveSegment.value.id < sendSegment.value.id) receiveSegment.value else sendSegment.value
        var cur = head
        while (true) {
            repeat(SEGMENT_SIZE) { i ->
                val w = cur.getState(i)
                val e = cur.getElement(i)
                val wString = when (w) {
                    is CancellableContinuation<*> -> "cont"
                    is SelectInstance<*> -> "select"
                    is ReceiveCatching<*> -> "receiveCatching"
                    else -> w.toString()
                }
                val eString = e.toString()
                data += "($wString,$eString)"
            }
            cur = cur.next ?: break
        }
        var dataStartIndex = head.id * SEGMENT_SIZE
        while (data.isNotEmpty() && data.first() == "(null,null)") {
            data.removeFirst()
            dataStartIndex++
        }
        while (data.isNotEmpty() && data.last() == "(null,null)") data.removeLast()
        return "S=${sendersAndCloseStatus.value.counter},R=${receivers.value},B=${bufferEnd.value}," +
               "C=${sendersAndCloseStatus.value.closeStatus},data=${data},dataStartIndex=$dataStartIndex"
    }
}

/**
 * The channel is represented as a list of segments, which simulates an infinite array.
 * Each segment has its own [id], which increase from the beginning. These [id]s help
 * to update [BufferedChannel.sendSegment], [BufferedChannel.receiveSegment],
 * and [BufferedChannel.bufferEndSegment] correctly.
 */
internal class ChannelSegment<E>(id: Long, prev: ChannelSegment<E>?, pointers: Int) :
    Segment<ChannelSegment<E>>(id, prev, pointers) {
    private val data = atomicArrayOfNulls<Any?>(SEGMENT_SIZE * 2) // 2 registers per slot

    override val maxSlots: Int get() = SEGMENT_SIZE

    inline fun getElement(index: Int): Any? = data[index * 2].value
    inline fun setElementLazy(index: Int, value: Any?) {
        data[index * 2].lazySet(value)
    }

    inline fun getState(index: Int): Any? = data[index * 2 + 1].value
    inline fun setState(index: Int, value: Any?) {
        data[index * 2 + 1].value = value
    }
    inline fun setStateLazy(index: Int, value: Any?) {
        data[index * 2 + 1].lazySet(value)
    }

    inline fun casState(index: Int, from: Any?, to: Any?) = data[index * 2 + 1].compareAndSet(from, to)

    fun storeElement(i: Int, element: E) {
        val element: Any = if (element === null) NULL_ELEMENT else element
        setElementLazy(i, element)
    }

    fun retrieveElement(i: Int): E = readElement(i).also { setElementLazy(i, null) }

    private fun readElement(i: Int): E {
        val element = getElement(i)
        return (if (element === NULL_ELEMENT) null else element) as E
    }

    fun onCancellation(i: Int, onUndeliveredElement: OnUndeliveredElement<E>? = null, context: CoroutineContext? = null, expectedWaiter: Any? = null) {
        val element = getElement(i)
        val waiter = data[i * 2 + 1].getAndUpdate {
            if (it === RESUMING_R || it === RESUMING_EB || it === RESUMING_R_EB ||
                it === INTERRUPTED || it === INTERRUPTED_R || it === INTERRUPTED_EB ||
                it === CHANNEL_CLOSED || it is WaiterEB ||  it === BUFFERED
            ) return
            INTERRUPTED
        }
        if (waiter === expectedWaiter) {
            onUndeliveredElement?.callUndeliveredElement(element as E, context!!)
        }
        onSlotCleaned()
    }
}
private fun <E> createSegment(id: Long, prev: ChannelSegment<E>?) = ChannelSegment(id, prev, 0)
// Number of cells in each segment
private val SEGMENT_SIZE = systemProp("kotlinx.coroutines.bufferedChannel.segmentSize", 32)



// Cell states
@SharedImmutable
private val BUFFERING = Symbol("BUFFERING")
@SharedImmutable
private val BUFFERED = Symbol("BUFFERED")
@SharedImmutable
private val RESUMING_R = Symbol("RESUMING_R")
@SharedImmutable
private val RESUMING_EB = Symbol("RESUMING_EB")
@SharedImmutable
private val RESUMING_R_EB = Symbol("RESUMING_R_EB")
@SharedImmutable
private val BROKEN = Symbol("BROKEN")
@SharedImmutable
private val DONE = Symbol("DONE")
@SharedImmutable
private val INTERRUPTED = Symbol("INTERRUPTED")
@SharedImmutable
private val INTERRUPTED_R = Symbol("INTERRUPTED_R")
@SharedImmutable
private val INTERRUPTED_EB = Symbol("INTERRUPTED_EB")
private class WaiterEB(@JvmField val waiter: Any) {
    override fun toString() = "ExpandBufferDesc($waiter)"
}
private class ReceiveCatching<E>(
    @JvmField val receiver: CancellableContinuation<ChannelResult<E>>
) : Waiter

// Special values for `CLOSE_HANDLER`
@SharedImmutable
private val CLOSE_HANDLER_CLOSED = Symbol("CLOSE_HANDLER_CLOSED")
@SharedImmutable
private val CLOSE_HANDLER_INVOKED = Symbol("CLOSE_HANDLER_INVOKED")

// Specifies the absence of close cause
@SharedImmutable
private val NO_CLOSE_CAUSE = Symbol("NO_CLOSE_CAUSE")

// Senders should store this value when the element is null
@SharedImmutable
private val NULL_ELEMENT = Symbol("NULL")
@Suppress("UNCHECKED_CAST")
private fun <E> Any.asElement(): E = if (this === NULL_ELEMENT) null as E
                                      else this as E
private fun Any?.elementAsState(): Any = this ?: NULL_ELEMENT

// Special return values
@SharedImmutable
private val SUSPEND = Symbol("SUSPEND")
@SharedImmutable
private val NO_WAITER = Symbol("NO_WAITER")
@SharedImmutable
private val FAILED = Symbol("FAILED")

@SharedImmutable
internal val CHANNEL_CLOSED = Symbol("CHANNEL_CLOSED")

private const val RESULT_RENDEZVOUS = 0
private const val RESULT_BUFFERED = 1
private const val RESULT_SUSPEND = 2
private const val RESULT_NO_WAITER = 3
private const val RESULT_FAILED = 4

private const val CLOSE_STATUS_ACTIVE = 0
private const val CLOSE_STATUS_CANCELLATION_STARTED = 1
private const val CLOSE_STATUS_CLOSED = 2
private const val CLOSE_STATUS_CANCELLED = 3

private const val CLOSE_STATUS_SHIFT = 27
private const val COUNTER_MASK = (1L shl CLOSE_STATUS_SHIFT) - 1
private inline val Long.counter get() = this and COUNTER_MASK
private inline val Long.closeStatus: Int get() = (this shr CLOSE_STATUS_SHIFT).toInt()
private inline fun constructSendersAndCloseStatus(counter: Long, closeStatus: Int): Long =
    (closeStatus.toLong() shl CLOSE_STATUS_SHIFT) + counter

@InternalCoroutinesApi
public interface Waiter