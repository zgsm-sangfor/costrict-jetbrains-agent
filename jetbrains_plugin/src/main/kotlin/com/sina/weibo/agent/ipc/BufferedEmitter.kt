// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.ipc

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Buffered event emitter
 * Ensures messages are not lost when there are no event listeners
 * Corresponds to BufferedEmitter in VSCode
 * @param T Event data type
 */
class BufferedEmitter<T> {
    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()
    private val bufferedMessages = ConcurrentLinkedQueue<T>()
    @Volatile
    private var hasListeners = false
    @Volatile
    private var isDeliveringMessages = false
    
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        private val LOG = Logger.getInstance(BufferedEmitter::class.java)
    }

    /**
     * Event listener property, similar to the event property in TypeScript version
     */
    val event: EventListener<T> = this::onEvent
    
    /**
     * Add event listener
     * @param listener Event listener
     * @return Listener registration identifier for removing the listener
     */
    fun onEvent(listener: (T) -> Unit): Disposable {
        listeners.add(listener)
        hasListeners = true
        if (bufferedMessages.isNotEmpty()) {
            scope.launch { deliverMessages() }
        }
        
        return Disposable {
            synchronized(listeners) {
                listeners.remove(listener)
                if (listeners.isEmpty()) hasListeners = false
            }
        }
    }
    
    /**
     * Fire event
     * @param event Event data
     */
    fun fire(event: T) {
        if (hasListeners) {
            synchronized(listeners) {
                if (bufferedMessages.isNotEmpty()) {
                    bufferedMessages.offer(event)
                    return
                }
                ArrayList(listeners).forEach { listener ->
                    try {
                        listener(event)
                    } catch (e: Exception) {
                        LOG.warn("Event listener error: ${e.message}")
                    }
                }
            }
        } else {
            bufferedMessages.offer(event)
        }
    }
    
    /**
     * Clear buffer
     */
    fun flushBuffer() {
        bufferedMessages.clear()
    }
    
    /**
     * Deliver buffered messages
     */
    private suspend fun deliverMessages() {
        if (isDeliveringMessages) return
        isDeliveringMessages = true
        try {
            while (bufferedMessages.isNotEmpty()) {
                val event = bufferedMessages.poll() ?: break
                synchronized(listeners) {
                    ArrayList(listeners).forEach { listener ->
                        try {
                            listener(event)
                        } catch (e: Exception) {
                            LOG.warn("Event listener error: ${e.message}")
                        }
                    }
                }
            }
        } finally {
            isDeliveringMessages = false
        }
    }
}

/**
 * Event listener type alias
 */
typealias EventListener<T> = ((T) -> Unit) -> Disposable 
