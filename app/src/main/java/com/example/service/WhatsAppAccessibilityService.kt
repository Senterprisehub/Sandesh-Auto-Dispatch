package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.SandeshApplication
import com.example.data.model.LockCredentialType
import com.example.dispatch.ActiveDispatchSession
import com.example.dispatch.DispatchCoordinator
import com.example.dispatch.NotificationHelper
import com.example.dispatch.RecurrenceCalculator
import com.example.dispatch.WhatsAppPickerCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class WhatsAppAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstanceRef = WeakReference(this)
        SandeshQuickTileService.requestTileRefresh(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()

        val session = DispatchCoordinator.current()
        if (session == null) {
            val pickerSession = WhatsAppPickerCoordinator.currentSession() ?: return
            if (!pickerSession.hasResolvedClonePicker && isDualAppChooserPackage(pkg, root)) {
                handleDualAppPickerChoice(root, pickerSession.isCloneTarget) {
                    pickerSession.hasResolvedClonePicker = true
                    pickerSession.lastActionTimestamp = System.currentTimeMillis()
                }
                return
            }
            if (pkg.contains("whatsapp", ignoreCase = true)) {
                WhatsAppPickerHandler.handlePickerEvent(this, root, pickerSession)
            }
            return
        }

        if (System.currentTimeMillis() - session.startTimeMillis > 35_000L) {
            failSession(session, "Dispatch window exceeded 35 seconds")
            return
        }

        // 1. Handle Device Lock Screen if keyguard is active
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true && isSystemKeyguardPackage(pkg)) {
            handleKeyguardUnlock(root, session)
            return
        }

        // 2. Handle OEM Dual-App / Clone Chooser Sheets (Xiaomi, Samsung, Oppo, Realme, Vivo)
        if (!session.hasResolvedClonePicker && isDualAppChooserPackage(pkg, root)) {
            handleDualAppPicker(root, session)
            return
        }

        // 3. Handle WhatsApp & WhatsApp Business UI Flow
        if (pkg.contains("whatsapp", ignoreCase = true)) {
            handleWhatsAppUi(root, session)
        }
    }

    private fun handleKeyguardUnlock(root: AccessibilityNodeInfo, session: ActiveDispatchSession) {
        val now = System.currentTimeMillis()
        if (now - session.lastActionTimestamp < 450L) return

        val creds = session.credentials ?: return
        if (creds.lockType == LockCredentialType.NONE) {
            if (!session.hasSwipedKeyguard) {
                session.hasSwipedKeyguard = true
                session.lastActionTimestamp = now
                AccessibilityNodeHelpers.swipeUpToUnlock(this, root)
            }
            return
        }

        val secret = creds.deviceCredential ?: return
        if (!session.hasSwipedKeyguard) {
            session.hasSwipedKeyguard = true
            session.lastActionTimestamp = now
            AccessibilityNodeHelpers.swipeUpToUnlock(this, root)
            return
        }

        if (session.hasSubmittedKeyguardSecret) return

        when (creds.lockType) {
            LockCredentialType.PIN -> {
                val firstNode = AccessibilityNodeHelpers.findKeyguardDigitNode(root, secret.firstOrNull() ?: return)
                if (firstNode == null) {
                    // Retry swipe in case bouncer didn't open yet
                    session.lastActionTimestamp = now
                    AccessibilityNodeHelpers.swipeUpToUnlock(this, root)
                    return
                }
                session.hasSubmittedKeyguardSecret = true
                session.lastActionTimestamp = now
                val pinChars = secret.copyOf()
                creds.clear()
                enterPinSequentially(pinChars)
            }

            LockCredentialType.PATTERN -> {
                val patternBounds = AccessibilityNodeHelpers.findPatternViewBounds(root) ?: return
                session.hasSubmittedKeyguardSecret = true
                session.lastActionTimestamp = now
                val patternCopy = secret.copyOf()
                creds.clear()
                AccessibilityNodeHelpers.drawPatternGesture(this, patternBounds, patternCopy)
                patternCopy.fill('\u0000')
            }

            LockCredentialType.PASSWORD -> {
                val editNode = AccessibilityNodeHelpers.findFirstEditableField(root) ?: return
                session.hasSubmittedKeyguardSecret = true
                session.lastActionTimestamp = now
                val pwString = String(secret)
                creds.clear()
                AccessibilityNodeHelpers.setText(editNode, pwString)
                mainHandler.postDelayed({
                    rootInActiveWindow?.let { freshRoot ->
                        AccessibilityNodeHelpers.findKeyguardEnterNode(freshRoot)
                            ?.let { AccessibilityNodeHelpers.clickOrTap(this, it) }
                    }
                }, 250L)
            }

            LockCredentialType.NONE -> Unit
        }
    }

    private fun enterPinSequentially(pinDigits: CharArray) {
        pinDigits.forEachIndexed { idx, digit ->
            mainHandler.postDelayed({
                val freshRoot = rootInActiveWindow
                if (freshRoot != null) {
                    AccessibilityNodeHelpers.findKeyguardDigitNode(freshRoot, digit)
                        ?.let { AccessibilityNodeHelpers.clickOrTap(this, it) }
                }
                if (idx == pinDigits.lastIndex) {
                    pinDigits.fill('\u0000')
                    mainHandler.postDelayed({
                        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        if (km?.isKeyguardLocked == true) {
                            rootInActiveWindow?.let { r ->
                                AccessibilityNodeHelpers.findKeyguardEnterNode(r)
                                    ?.let { AccessibilityNodeHelpers.clickOrTap(this, it) }
                            }
                        }
                    }, 300L)
                }
            }, idx * 160L)
        }
    }

    private fun handleDualAppPicker(root: AccessibilityNodeInfo, session: ActiveDispatchSession) {
        val now = System.currentTimeMillis()
        if (now - session.lastActionTimestamp < 400L) return
        handleDualAppPickerChoice(root, session.isCloneTarget) {
            session.hasResolvedClonePicker = true
            session.lastActionTimestamp = now
        }
    }

    private fun handleDualAppPickerChoice(
        root: AccessibilityNodeInfo,
        isCloneTarget: Boolean,
        onChosen: () -> Unit
    ) {
        val keywords = if (isCloneTarget) {
            listOf("dual", "clone", "2nd", "second", "parallel", "work", "twin")
        } else {
            listOf("whatsapp")
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val text = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
            if (text.contains("whatsapp") && node.isVisibleToUser) {
                candidates += node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }

        val chosen = if (isCloneTarget) {
            candidates.firstOrNull { node ->
                val t = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
                keywords.any { t.contains(it) }
            } ?: candidates.lastOrNull()
        } else {
            candidates.firstOrNull { node ->
                val t = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
                !t.contains("dual") && !t.contains("clone") && !t.contains("2nd")
            } ?: candidates.firstOrNull()
        }

        if (chosen != null) {
            onChosen()
            AccessibilityNodeHelpers.clickOrTap(this, chosen)
        }
    }

    private fun handleWhatsAppUi(root: AccessibilityNodeInfo, session: ActiveDispatchSession) {
        val now = System.currentTimeMillis()
        if (now - session.lastActionTimestamp < 300L) return

        // A. WhatsApp built-in App Lock PIN screen
        val waPin = session.credentials?.whatsAppLockPin
        if (!session.hasUnlockedWhatsAppLock && waPin != null && isWhatsAppLockScreen(root)) {
            val pinCopy = waPin.copyOf()
            session.hasUnlockedWhatsAppLock = true
            session.lastActionTimestamp = now
            val pinField = AccessibilityNodeHelpers.findFirstEditableField(root)
            if (pinField != null) {
                AccessibilityNodeHelpers.setText(pinField, String(pinCopy))
            } else {
                enterPinSequentially(pinCopy)
            }
            pinCopy.fill('\u0000')
            return
        }

        // B. Chat Screen or Media Preview Screen -> verify text/caption & tap Send
        val sendButton = findRealSendButton(root)
        val isMediaPreview = isMediaPreviewScreen(root)
        val isChat = isChatConversationScreen(root)

        if ((isMediaPreview || isChat) && sendButton != null && !session.hasClickedSend) {
            if (session.messageBody.isNotBlank()) {
                val inputField = findMessageOrCaptionField(root)
                if (inputField != null && inputField.text.isNullOrBlank()) {
                    AccessibilityNodeHelpers.setText(inputField, session.messageBody)
                    session.lastActionTimestamp = now
                    mainHandler.postDelayed({
                        val refreshedSend = rootInActiveWindow?.let { findRealSendButton(it) } ?: sendButton
                        triggerSendAndComplete(refreshedSend, session)
                    }, 350L)
                    return
                }
            }
            triggerSendAndComplete(sendButton, session)
            return
        }

        // C. Contact / Share Picker Screen (Groups, Media Share, or Cloned App)
        if (!session.hasSelectedRecipient && isContactPickerScreen(root)) {
            val query = session.recipientName.ifBlank { session.normalizedPhone }
            if (query.isBlank()) return

            val searchInput = findSearchInput(root)
            if (searchInput != null) {
                if (searchInput.text?.toString()?.equals(query, ignoreCase = true) != true) {
                    session.lastActionTimestamp = now
                    AccessibilityNodeHelpers.setText(searchInput, query)
                    mainHandler.postDelayed({
                        rootInActiveWindow?.let { fresh ->
                            selectMatchingRecipientRow(fresh, query, session)
                        }
                    }, 550L)
                } else {
                    selectMatchingRecipientRow(root, query, session)
                }
            } else {
                findSearchActionButton(root)?.let { searchBtn ->
                    session.lastActionTimestamp = now
                    AccessibilityNodeHelpers.clickOrTap(this, searchBtn)
                }
            }
        }
    }

    private fun selectMatchingRecipientRow(
        root: AccessibilityNodeInfo,
        query: String,
        session: ActiveDispatchSession
    ) {
        val candidates = root.findAccessibilityNodeInfosByText(query)
        val rowNode = candidates.firstOrNull { node ->
            val cls = node.className?.toString().orEmpty()
            node.isVisibleToUser && !cls.contains("EditText")
        }
        if (rowNode != null) {
            session.hasSelectedRecipient = true
            session.lastActionTimestamp = System.currentTimeMillis()
            AccessibilityNodeHelpers.clickOrTap(this, rowNode)

            // Some WhatsApp versions require tapping a green Next/Send FAB after selecting contact
            mainHandler.postDelayed({
                val fresh = rootInActiveWindow ?: return@postDelayed
                if (!isChatConversationScreen(fresh) && !isMediaPreviewScreen(fresh)) {
                    findForwardNextFab(fresh)?.let { AccessibilityNodeHelpers.clickOrTap(this, it) }
                }
            }, 450L)
        }
    }

    private fun triggerSendAndComplete(sendNode: AccessibilityNodeInfo, session: ActiveDispatchSession) {
        if (session.hasClickedSend) return
        session.hasClickedSend = true
        session.lastActionTimestamp = System.currentTimeMillis()
        AccessibilityNodeHelpers.clickOrTap(this, sendNode)

        val shouldRelock = session.wasLockedInitially &&
            (session.credentials?.autoRelockAfterSend ?: true)

        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            if (shouldRelock && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mainHandler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    completeSessionSuccess(session)
                }, 350L)
            } else {
                completeSessionSuccess(session)
            }
        }, 800L)
    }

    private fun completeSessionSuccess(session: ActiveDispatchSession) {
        DispatchCoordinator.finishAndClear()
        val duration = System.currentTimeMillis() - session.startTimeMillis
        val app = applicationContext as? SandeshApplication ?: return
        val recipientLabel = session.recipientName.ifBlank { session.normalizedPhone }

        serviceScope.launch {
            val nextRecurring = app.container.repository.recordDispatchOutcome(
                scheduleId = session.scheduleId,
                success = true,
                durationMillis = duration,
                detailMessage = "Delivered via ${session.targetInstanceLabel}",
                allowRetry = false
            )
            if (nextRecurring != null) {
                com.example.dispatch.AlarmScheduler.schedule(applicationContext, nextRecurring)
            }
            NotificationHelper.showResultNotification(
                context = applicationContext,
                scheduleId = session.scheduleId,
                recipientLabel = recipientLabel,
                success = true,
                detail = nextRecurring?.let {
                    "Sent! Next run ${RecurrenceCalculator.formatCountdown(it.scheduledTimeMillis)}"
                } ?: "Delivered via ${session.targetInstanceLabel}"
            )
            mainHandler.post {
                JitAccessibilityController.ensureIdleOffIfJitEnabled(applicationContext)
            }
        }
    }

    private fun failSession(session: ActiveDispatchSession, reason: String) {
        DispatchCoordinator.finishAndClear()
        val duration = System.currentTimeMillis() - session.startTimeMillis
        val app = applicationContext as? SandeshApplication ?: return
        serviceScope.launch {
            val nextOrRetry = app.container.repository.recordDispatchOutcome(
                scheduleId = session.scheduleId,
                success = false,
                durationMillis = duration,
                detailMessage = reason,
                allowRetry = true
            )
            if (nextOrRetry != null) {
                com.example.dispatch.AlarmScheduler.schedule(applicationContext, nextOrRetry)
            }
            NotificationHelper.showResultNotification(
                context = applicationContext,
                scheduleId = session.scheduleId,
                recipientLabel = session.recipientName.ifBlank { session.normalizedPhone },
                success = false,
                detail = if (nextOrRetry?.retryAtMillis ?: 0L > 0L) "Auto-retrying in 60s: $reason" else reason
            )
            mainHandler.post {
                JitAccessibilityController.ensureIdleOffIfJitEnabled(applicationContext)
            }
        }
    }

    private fun isSystemKeyguardPackage(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg.contains("keyguard") || pkg.contains("lockscreen")

    private fun isDualAppChooserPackage(pkg: String, root: AccessibilityNodeInfo): Boolean {
        val known = pkg.contains("miui.securitycore") ||
            pkg.contains("oplus.multiapp") ||
            pkg.contains("coloros") ||
            pkg.contains("daagent") ||
            pkg.contains("doubleinstance") ||
            pkg == "android"
        if (!known) return false
        val hasDualPrompt = root.findAccessibilityNodeInfosByText("WhatsApp").size >= 2 ||
            root.findAccessibilityNodeInfosByText("Dual").isNotEmpty() ||
            root.findAccessibilityNodeInfosByText("Clone").isNotEmpty()
        return hasDualPrompt
    }

    private fun isWhatsAppLockScreen(root: AccessibilityNodeInfo): Boolean {
        return root.findAccessibilityNodeInfosByText("WhatsApp locked").isNotEmpty() ||
            root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/fingerprint_prompt_title").isNotEmpty() ||
            root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/fingerprint_prompt_title").isNotEmpty()
    }

    private fun isChatConversationScreen(root: AccessibilityNodeInfo): Boolean {
        val chatIds = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
        return chatIds.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }
    }

    private fun isMediaPreviewScreen(root: AccessibilityNodeInfo): Boolean {
        val previewIds = listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp.w4b:id/caption",
            "com.whatsapp:id/add_caption",
            "com.whatsapp.w4b:id/add_caption"
        )
        return previewIds.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }
    }

    private fun isContactPickerScreen(root: AccessibilityNodeInfo): Boolean {
        val pickerIds = listOf(
            "com.whatsapp:id/menuitem_search",
            "com.whatsapp.w4b:id/menuitem_search",
            "com.whatsapp:id/search_src_text",
            "com.whatsapp.w4b:id/search_src_text",
            "com.whatsapp:id/contactpicker_row_name",
            "com.whatsapp.w4b:id/contactpicker_row_name"
        )
        return pickerIds.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() } ||
            root.findAccessibilityNodeInfosByText("Send to").isNotEmpty() ||
            root.findAccessibilityNodeInfosByText("Frequently contacted").isNotEmpty()
    }

    private fun findMessageOrCaptionField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp:id/caption",
            "com.whatsapp.w4b:id/caption"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (node != null) return node
        }
        return null
    }

    private fun findSearchInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/search_src_text",
            "com.whatsapp.w4b:id/search_src_text",
            "com.whatsapp:id/search_bar_text",
            "com.whatsapp.w4b:id/search_bar_text"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (node != null) return node
        }
        return null
    }

    private fun findSearchActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/menuitem_search",
            "com.whatsapp.w4b:id/menuitem_search"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (node != null) return node
        }
        return root.findAccessibilityNodeInfosByText("Search")
            .firstOrNull { it.isVisibleToUser && it.isClickable }
    }

    private fun findForwardNextFab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/next_btn",
            "com.whatsapp.w4b:id/next_btn"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser && it.isEnabled }
            if (node != null) return node
        }
        return null
    }

    private fun findRealSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/conversation_send_button",
            "com.whatsapp.w4b:id/conversation_send_button",
            "com.whatsapp:id/btn_send",
            "com.whatsapp:id/entry_send"
        )
        for (id in sendIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser && it.isEnabled }
            if (node != null) {
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                if (!desc.contains("voice") && !desc.contains("audio") && !desc.contains("mic")) {
                    return node
                }
            }
        }

        val labels = listOf("Send", "Enviar", "Envoyer", "Kirim", "Senden", "भेजें")
        for (lbl in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(lbl)
            for (node in nodes) {
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                if (node.isVisibleToUser && node.isEnabled &&
                    !desc.contains("voice") && !desc.contains("audio") && !desc.contains("mic")
                ) {
                    return node
                }
            }
        }

        // Structural fallback for future WhatsApp UI updates: clickable button to the right of non-empty input
        val inputField = findMessageOrCaptionField(root)
        if (inputField != null && !inputField.text.isNullOrBlank()) {
            val inputRect = Rect()
            inputField.getBoundsInScreen(inputRect)
            val excludedTerms = listOf("voice", "audio", "mic", "record", "camera", "attach", "emoji", "sticker", "gif")
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeFirst()
                val cls = node.className?.toString().orEmpty()
                val isBtn = cls.contains("Button") || cls.contains("ImageButton") || cls.contains("ImageView")
                if (isBtn && node.isVisibleToUser && node.isEnabled && node.isClickable) {
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    if (excludedTerms.none { desc.contains(it) }) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.left >= inputRect.right - 20 && rect.bottom >= inputRect.top - 40 && rect.width() > 0) {
                            return node
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            }
        }
        return null
    }

    override fun onInterrupt() {
        DispatchCoordinator.finishAndClear()
        JitAccessibilityController.ensureIdleOffIfJitEnabled(applicationContext)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        activeInstanceRef = null
        SandeshQuickTileService.requestTileRefresh(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstanceRef = null
        SandeshQuickTileService.requestTileRefresh(applicationContext)
        serviceScope.cancel()
    }

    companion object {
        @Volatile
        private var activeInstanceRef: WeakReference<WhatsAppAccessibilityService>? = null

        fun isServiceBound(): Boolean = activeInstanceRef?.get() != null

        fun requestSelfDisable(): Boolean {
            val instance = activeInstanceRef?.get() ?: return false
            return runCatching {
                instance.disableSelf()
                true
            }.getOrDefault(false)
        }
    }
}
