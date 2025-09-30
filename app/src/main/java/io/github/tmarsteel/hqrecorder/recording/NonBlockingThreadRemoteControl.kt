package io.github.tmarsteel.hqrecorder.recording

import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Models and helps with one thread that is controlling another, where the thread that is
 * being controlled is given 100% discretion on when to process the commands (= not blocking)
 * and all the blocking happens in the controlling thread.
 *
 * This is used to assure a thread that is recording audio has all the CPU time it needs to not miss any audio samples.
 */
class NonBlockingThreadRemoteControl(
    private val controlledThread: Thread,
) {
    private val queuedCommand = AtomicReference<RcCommand<*>?>(null)
    private val resultQueue = AtomicReference<CommandResult?>(null)

    private val commMutex = Any()

    fun <Response, Command : RcCommand<Response>> executeCommand(
        command: Command,
        expectResponseAfter: Duration,
    ): Response {
        check(Thread.currentThread() !== controlledThread)

        synchronized(commMutex) {
            check(queuedCommand.get() == null)
            resultQueue.setRelease(null)
            queuedCommand.setRelease(command)

            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {}

            var timeoutInMillis = expectResponseAfter.inWholeMilliseconds
            while (true) {
                val result = resultQueue.getAcquire()
                when (result) {
                    is CommandResult.Response -> return result.response as Response
                    is CommandResult.Failed -> throw result.exception
                    null -> {
                        if (!controlledThread.isAlive) {
                            throw ControlledThreadIsDeadException(controlledThread)
                        }
                        try {
                            Thread.sleep(timeoutInMillis)
                        }
                        catch (_: InterruptedException) {}
                        timeoutInMillis = (timeoutInMillis / 2).coerceAtLeast(20)
                    }
                }
            }
        }
    }

    fun processNextCommand(processor: (RcCommand<*>) -> Any) {
        check(Thread.currentThread() === controlledThread)

        // this is non-blocking
        val command = queuedCommand.getAcquire()
            ?: return
        check(queuedCommand.compareAndExchange(command, null) == command)
        val result = try {
            CommandResult.Response(processor(command))
        } catch (ex: Throwable) {
            CommandResult.Failed(ex)
        }
        check(resultQueue.compareAndExchangeRelease(null, result) == null)
    }

    interface RcCommand<Response>

    private sealed interface CommandResult {
        class Response(val response: Any) : CommandResult
        class Failed(val exception: Throwable) : CommandResult
    }

    class ControlledThreadIsDeadException(val thread: Thread) : RuntimeException(thread.toString())
}