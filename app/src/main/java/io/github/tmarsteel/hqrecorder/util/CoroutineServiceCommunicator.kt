package io.github.tmarsteel.hqrecorder.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CoroutineServiceCommunicator private constructor(
    private val serviceConnection: ServiceConnection,
    private val outMessenger: Messenger,
    immediateHandler: ImmediateHandler,
    looper: Looper,
) {
    private val inbox = LinkedBlockingQueue<Message>()
    private val inboxMessageStrategy = object : MessageStrategy {
        override fun handleMessage(msg: Message) {
            inbox.put(msg)
        }

        override fun handleDisconnection() {
            // nothing to do
        }
    }
    @Volatile
    private var nextMessageStrategy: MessageStrategy = inboxMessageStrategy
    private val responseHandler = object : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (!immediateHandler.tryHandleImmediately(msg)) {
                nextMessageStrategy.handleMessage(msg)
            }
        }
    }
    private val responseMessenger = Messenger(responseHandler)

    @Volatile
    private var disconnected: Boolean = false

    private fun onDisconnected() {
        disconnected = true
        nextMessageStrategy.handleDisconnection()
    }

    private fun checkNotDisconnected() {
        if (disconnected) {
            throw ServiceIsDisconnectedException()
        }
    }

    fun sendToService(message: Message) {
        checkNotDisconnected()

        if (message.replyTo == null) {
            message.replyTo = responseMessenger
        }
        outMessenger.send(message)
    }

    suspend fun getNextMessage(): Message {
        inbox.poll()?.let {
            return it
        }

        checkNotDisconnected()

        return suspendCoroutine<Message> { continuation ->
            val handlerForNextMessage = ContinuationMessageStrategy(continuation)
            nextMessageStrategy = handlerForNextMessage
            val messageDuringRaceCondition = inbox.peek()
            if (messageDuringRaceCondition != null) {
                // we got a message while setting up the listener, revert
                nextMessageStrategy = inboxMessageStrategy
                if (handlerForNextMessage.handled) {
                    // and the handler also got a message while it was active...
                    // nothing to do, though, the continuation was already executed
                } else {
                    continuation.resume(inbox.take())
                }
            }
        }
    }

    suspend fun exchangeWithService(messageOut: Message): Message {
        sendToService(messageOut)
        return getNextMessage()
    }

    private interface MessageStrategy {
        fun handleMessage(msg: Message)
        fun handleDisconnection()
    }

    private inner class ContinuationMessageStrategy(val continuation: Continuation<Message>) : MessageStrategy {
        @Volatile
        var handled: Boolean = false
            private set

        override fun handleMessage(msg: Message) {
            if (handled) {
                return
            }
            handled = true

            nextMessageStrategy = inboxMessageStrategy
            continuation.resume(msg)
        }

        override fun handleDisconnection() {
            if (handled) {
                return
            }
            handled = true

            continuation.resumeWithException(ServiceIsDisconnectedException().also {
                it.fillInStackTrace()
            })
        }
    }

    companion object {
        suspend fun coDoWithService(
            context: Context,
            intent: Intent,
            flags: Int,
            startBeforeBind: Boolean = false,
            immediateHandler: ImmediateHandler = ImmediateHandler.ROUTE_ALL_MESSAGES_TO_COROUTINE,
            jobWithService: ActionWithService,
        ) {
            if (startBeforeBind) {
                if (context.startService(intent) == null) {
                    throw BindException("The service doesn't exist (${context::startService.name} returned null)")
                }
            }

            val outerCommunicator = suspendCoroutine<CoroutineServiceCommunicator> { connectionContinuation ->
                val connection = object : ServiceConnection {
                    private var communicator: CoroutineServiceCommunicator? = null
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        val messenger = Messenger(service)
                        communicator = CoroutineServiceCommunicator(this, messenger, immediateHandler, context.mainLooper)
                        connectionContinuation.resume(communicator!!)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        val localCommunicator = communicator
                        if (localCommunicator == null) {
                            connectionContinuation.resumeWithException(ServiceIsDisconnectedException())
                        } else {
                            localCommunicator.onDisconnected()
                        }
                    }
                }

                if (!context.bindService(intent, connection, flags)) {
                    context.unbindService(connection)
                    throw BindException()
                }
            }

            try {
                jobWithService.run(outerCommunicator)
            } finally {
                context.unbindService(outerCommunicator.serviceConnection)
            }
        }
    }

    fun interface ActionWithService {
        suspend fun run(comm: CoroutineServiceCommunicator)
    }

    fun interface ImmediateHandler {
        /**
         * tries to handle this message immediately, circumventing the coroutine mechanism.
         * @return true if the message was handled and can be discarded, false if it should be returned from [CoroutineServiceCommunicator.getNextMessage]
         */
        fun tryHandleImmediately(msg: Message): Boolean

        companion object {
            val ROUTE_ALL_MESSAGES_TO_COROUTINE = object : ImmediateHandler {
                override fun tryHandleImmediately(msg: Message): Boolean {
                    return false
                }
            }
        }
    }

    class BindException(message: String? = null) : RuntimeException(message)
    class ServiceIsDisconnectedException : RuntimeException()
}