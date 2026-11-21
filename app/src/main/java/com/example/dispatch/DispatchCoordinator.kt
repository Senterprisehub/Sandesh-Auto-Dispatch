package com.example.dispatch

import com.example.security.DecryptedDispatchCredentials
import java.util.concurrent.atomic.AtomicReference

data class ActiveDispatchSession(
    val scheduleId: Long,
    val targetPackage: String,
    val targetUserSerial: Long,
    val isCloneTarget: Boolean,
    val targetInstanceLabel: String,
    val recipientName: String,
    val normalizedPhone: String,
    val isGroup: Boolean,
    val messageBody: String,
    val hasMedia: Boolean,
    val wasLockedInitially: Boolean,
    val startTimeMillis: Long = System.currentTimeMillis(),
    val credentials: DecryptedDispatchCredentials? = null,
    @Volatile var hasSwipedKeyguard: Boolean = false,
    @Volatile var hasSubmittedKeyguardSecret: Boolean = false,
    @Volatile var hasResolvedClonePicker: Boolean = false,
    @Volatile var hasUnlockedWhatsAppLock: Boolean = false,
    @Volatile var hasSelectedRecipient: Boolean = false,
    @Volatile var hasClickedSend: Boolean = false,
    @Volatile var lastActionTimestamp: Long = 0L
)

object DispatchCoordinator {
    private val queueLock = Any()
    private val pendingQueue = ArrayDeque<Long>()
    private val activeSession = AtomicReference<ActiveDispatchSession?>(null)

    fun enqueueSchedule(scheduleId: Long): Boolean {
        if (scheduleId <= 0L) return false
        synchronized(queueLock) {
            if (activeSession.get()?.scheduleId == scheduleId || scheduleId in pendingQueue) {
                return false
            }
            pendingQueue.addLast(scheduleId)
            return true
        }
    }

    fun dequeueNextSchedule(): Long? {
        synchronized(queueLock) {
            return pendingQueue.removeFirstOrNull()
        }
    }

    fun pendingCount(): Int = synchronized(queueLock) { pendingQueue.size }

    fun hasActiveOrQueuedWork(): Boolean {
        synchronized(queueLock) {
            return activeSession.get() != null || pendingQueue.isNotEmpty()
        }
    }

    fun startSession(session: ActiveDispatchSession) {
        activeSession.getAndSet(session)?.credentials?.clear()
    }

    fun current(): ActiveDispatchSession? = activeSession.get()

    fun finishAndClear(): ActiveDispatchSession? {
        val prev = activeSession.getAndSet(null)
        prev?.credentials?.clear()
        return prev
    }

    fun clearAll() {
        synchronized(queueLock) {
            pendingQueue.clear()
        }
        finishAndClear()
    }
}
