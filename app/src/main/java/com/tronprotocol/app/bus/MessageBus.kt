package com.tronprotocol.app.bus

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MessageBus - NanoBot-inspired async pub-sub for decoupled component communication.
 *
 * Inspired by NanoBot's lightweight message passing architecture:
 * - Async publish/subscribe with topic-based routing
 * - Wildcard topic matching (e.g., "plugin.*" matches "plugin.calculator")
 * - Dead letter queue for undeliverable messages
 * - Message replay for late subscribers
 * - Backpressure via bounded queues
 * - Thread-safe concurrent operations
 *
 * Topics use dot-separated hierarchies:
 *   system.heartbeat, plugin.execute.calculator, agent.react.step,
 *   training.epoch.complete, npu.inference.result
 */
class MessageBus(
    private val asyncThreads: Int = DEFAULT_ASYNC_THREADS,
    private val historyCapacity: Int = DEFAULT_HISTORY_CAPACITY,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE
) {

    /** A message on the bus. */
    data class Message(
        val id: Long,
        val topic: String,
        val payload: Any?,
        val sender: String,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, Any> = emptyMap()
    )

    /** Subscription handle for unsubscribing. */
    data class Subscription(
        val id: Long,
        val topicPattern: String,
        val subscriber: String,
        val isWildcard: Boolean
    )

    /** Callback for message delivery. */
    fun interface MessageHandler {
        fun onMessage(message: Message)
    }

    /** Dead letter entry for undeliverable messages. */
    data class DeadLetter(
        val message: Message,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Exact topic -> handlers
    private val exactSubscriptions = ConcurrentHashMap<String, CopyOnWriteArrayList<HandlerEntry>>()

    // Wildcard patterns -> handlers
    private val wildcardSubscriptions = CopyOnWriteArrayList<WildcardEntry>()

    // Message history for replay (bounded ring buffer)
    private val messageHistory = LinkedBlockingQueue<Message>(historyCapacity)

    // Dead letter queue
    private val deadLetters = LinkedBlockingQueue<DeadLetter>(MAX_DEAD_LETTERS)

    // Async dispatch
    private val executor: ExecutorService = Executors.newFixedThreadPool(asyncThreads)
    private val running = AtomicBoolean(true)

    // Counters
    private val messageIdCounter = AtomicLong(0)
    private val subscriptionIdCounter = AtomicLong(0)
    private val totalPublished = AtomicLong(0)
    private val totalDelivered = AtomicLong(0)
    private val totalDropped = AtomicLong(0)

    // All subscriptions for management
    private val allSubscriptions = ConcurrentHashMap<Long, Subscription>()

    /**
     * Subscribe to an exact topic.
     */
    fun subscribe(topic: String, subscriber: String, handler: MessageHandler): Subscription {
        val subId = subscriptionIdCounter.incrementAndGet()
        val entry = HandlerEntry(subId, subscriber, handler)

        exactSubscriptions.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(entry)

        val subscription = Subscription(subId, topic, subscriber, isWildcard = false)
        allSubscriptions[subId] = subscription

        Log.d(TAG, "[$subscriber] subscribed to '$topic' (id=$subId)")
        return subscription
    }

    /**
     * Subscribe to a wildcard topic pattern.
     * Supports:
     *   "plugin.*"      -> matches "plugin.calculator", "plugin.notes"
     *   "system.**"     -> matches "system.heartbeat", "system.heartbeat.metrics"
     *   "*.execute"     -> matches "plugin.execute", "agent.execute"
     */
    fun subscribePattern(pattern: String, subscriber: String, handler: MessageHandler): Subscription {
        val subId = subscriptionIdCounter.incrementAndGet()
        val regex = patternToRegex(pattern)
        val entry = WildcardEntry(subId, subscriber, pattern, regex, handler)

        wildcardSubscriptions.add(entry)

        val subscription = Subscription(subId, pattern, subscriber, isWildcard = true)
        allSubscriptions[subId] = subscription

        Log.d(TAG, "[$subscriber] subscribed to pattern '$pattern' (id=$subId)")
        return subscription
    }

    /**
     * Unsubscribe by subscription handle.
     */
    fun unsubscribe(subscription: Subscription) {
        allSubscriptions.remove(subscription.id)

        if (subscription.isWildcard) {
            wildcardSubscriptions.removeAll { it.subId == subscription.id }
        } else {
            exactSubscriptions[subscription.topicPattern]?.removeAll { it.subId == subscription.id }
        }

        Log.d(TAG, "[${subscription.subscriber}] unsubscribed from '${subscription.topicPattern}'")
    }

    /**
     * Unsubscribe all handlers for a given subscriber name.
     */
    fun unsubscribeAll(subscriber: String) {
        val toRemove = allSubscriptions.values.filter { it.subscriber == subscriber }
        for (sub in toRemove) {
            unsubscribe(sub)
        }
    }

    /**
     * Publish a message synchronously - delivers to all matching handlers on the calling thread.
     */
    fun publish(topic: String, payload: Any?, sender: String, metadata: Map<String, Any> = emptyMap()): Message {
        val message = Message(
            id = messageIdCounter.incrementAndGet(),
            topic = topic,
            payload = payload,
            sender = sender,
            metadata = metadata
        )

        totalPublished.incrementAndGet()

        // Store in history (drop oldest if full)
        if (!messageHistory.offer(message)) {
            messageHistory.poll()
            messageHistory.offer(message)
        }

        deliver(message)
        return message
    }

    /**
     * Publish a message asynchronously - delivery happens on the thread pool.
     */
    fun publishAsync(topic: String, payload: Any?, sender: String, metadata: Map<String, Any> = emptyMap()): Message {
        val message = Message(
            id = messageIdCounter.incrementAndGet(),
            topic = topic,
            payload = payload,
            sender = sender,
            metadata = metadata
        )

        totalPublished.incrementAndGet()

        if (!messageHistory.offer(message)) {
            messageHistory.poll()
            messageHistory.offer(message)
        }

        if (running.get()) {
            executor.execute { deliver(message) }
        }

        return message
    }

    /**
     * Replay recent messages matching a topic pattern to a handler.
     * Useful for late subscribers that need to catch up.
     */
    fun replay(topicPattern: String, handler: MessageHandler): Int {
        val regex = patternToRegex(topicPattern)
        var count = 0
        for (message in messageHistory) {
            if (regex.matches(message.topic)) {
                try {
                    handler.onMessage(message)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Error during replay for topic '${message.topic}'", e)
                }
            }
        }
        return count
    }

    /**
     * Request-reply pattern: publish and wait for a response on a reply topic.
     * The responder should publish to the reply topic.
     * Returns null on timeout.
     */
    fun request(
        topic: String,
        payload: Any?,
        sender: String,
        timeoutMs: Long = 5000
    ): Message? {
        val replyTopic = "reply.${messageIdCounter.incrementAndGet()}"
        val responseLatch = java.util.concurrent.CountDownLatch(1)
        var response: Message? = null

        val sub = subscribe(replyTopic, "$sender.reply") { msg ->
            response = msg
            responseLatch.countDown()
        }

        publish(topic, payload, sender, mapOf("replyTo" to replyTopic))

        responseLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        unsubscribe(sub)

        return response
    }

    /**
     * Get the dead letter queue contents.
     */
    fun getDeadLetters(): List<DeadLetter> = deadLetters.toList()

    /**
     * Clear the dead letter queue.
     */
    fun clearDeadLetters() {
        deadLetters.clear()
    }

    /**
     * Get bus statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "total_published" to totalPublished.get(),
        "total_delivered" to totalDelivered.get(),
        "total_dropped" to totalDropped.get(),
        "active_subscriptions" to allSubscriptions.size,
        "exact_topics" to exactSubscriptions.size,
        "wildcard_patterns" to wildcardSubscriptions.size,
        "history_size" to messageHistory.size,
        "dead_letters" to deadLetters.size,
        "running" to running.get()
    )

    /**
     * List all active subscriptions.
     */
    fun getSubscriptions(): List<Subscription> = allSubscriptions.values.toList()

    /**
     * Check if any subscribers exist for a topic.
     */
    fun hasSubscribers(topic: String): Boolean {
        if (exactSubscriptions.containsKey(topic) && exactSubscriptions[topic]!!.isNotEmpty()) {
            return true
        }
        return wildcardSubscriptions.any { it.regex.matches(topic) }
    }

    /**
     * Shut down the bus, stopping async delivery.
     */
    fun shutdown() {
        running.set(false)
        executor.shutdown()
        Log.d(TAG, "MessageBus shut down. Stats: ${getStats()}")
    }

    // -- Internal delivery --

    private fun deliver(message: Message) {
        var delivered = false

        // Exact match
        exactSubscriptions[message.topic]?.forEach { entry ->
            try {
                entry.handler.onMessage(message)
                totalDelivered.incrementAndGet()
                delivered = true
            } catch (e: Exception) {
                Log.w(TAG, "Handler error for topic '${message.topic}' subscriber '${entry.subscriber}'", e)
            }
        }

        // Wildcard match
        for (entry in wildcardSubscriptions) {
            if (entry.regex.matches(message.topic)) {
                try {
                    entry.handler.onMessage(message)
                    totalDelivered.incrementAndGet()
                    delivered = true
                } catch (e: Exception) {
                    Log.w(TAG, "Wildcard handler error for '${message.topic}' subscriber '${entry.subscriber}'", e)
                }
            }
        }

        // Dead letter if no one received it
        if (!delivered) {
            totalDropped.incrementAndGet()
            val deadLetter = DeadLetter(message, "No subscribers for topic '${message.topic}'")
            if (!deadLetters.offer(deadLetter)) {
                deadLetters.poll()
                deadLetters.offer(deadLetter)
            }
        }
    }

    private fun patternToRegex(pattern: String): Regex {
        val regexStr = pattern
            .replace(".", "\\.")
            .replace("**", "##DOUBLESTAR##")
            .replace("*", "[^.]+")
            .replace("##DOUBLESTAR##", ".+")
        return Regex("^$regexStr$")
    }

    private data class HandlerEntry(
        val subId: Long,
        val subscriber: String,
        val handler: MessageHandler
    )

    private data class WildcardEntry(
        val subId: Long,
        val subscriber: String,
        val pattern: String,
        val regex: Regex,
        val handler: MessageHandler
    )

    companion object {
        private const val TAG = "MessageBus"
        private const val DEFAULT_ASYNC_THREADS = 2
        private const val DEFAULT_HISTORY_CAPACITY = 500
        private const val DEFAULT_MAX_QUEUE_SIZE = 1000
        private const val MAX_DEAD_LETTERS = 100
    }
}
