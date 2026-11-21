package com.example.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.dispatch.ActivePickerSession
import com.example.dispatch.PickedWhatsAppRecipient
import com.example.dispatch.WhatsAppPickerCoordinator

object WhatsAppPickerHandler {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun handlePickerEvent(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        session: ActivePickerSession
    ) {
        val now = System.currentTimeMillis()
        if (now - session.lastActionTimestamp < 280L) return

        val isChat = isChatConversation(root)

        // 1. Contact Info Screen (reached after auto-peeking chat header)
        if (session.hasTappedHeaderForInfo && !isChat) {
            handleContactInfoScreen(service, root, session, now)
            return
        }

        // 2. Chat Conversation Screen (reached when user selects a contact or group)
        if (isChat) {
            val chatTitle = extractConversationTitle(root).trim()
            if (chatTitle.isBlank() || isIgnoredTitle(chatTitle)) return

            if (session.isGroupMode) {
                completePickAndReturn(
                    service,
                    PickedWhatsAppRecipient(
                        displayName = chatTitle,
                        phoneNumber = "",
                        isGroup = true
                    )
                )
                return
            }

            if (WhatsAppPickerCoordinator.looksLikePhoneNumber(chatTitle)) {
                completePickAndReturn(
                    service,
                    PickedWhatsAppRecipient(
                        displayName = "",
                        phoneNumber = chatTitle,
                        isGroup = false
                    )
                )
                return
            }

            // Saved contact name shown in chat header -> auto-peek Contact Info to fetch phone number
            if (!session.hasTappedHeaderForInfo) {
                session.capturedChatName = chatTitle
                val headerNode = findConversationHeaderNode(root)
                if (headerNode != null) {
                    session.hasTappedHeaderForInfo = true
                    session.lastActionTimestamp = now
                    AccessibilityNodeHelpers.clickOrTap(service, headerNode)
                }
            }
        }
    }

    private fun handleContactInfoScreen(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        session: ActivePickerSession,
        now: Long
    ) {
        val priorityTexts = collectPriorityInfoTexts(root)
        val allVisibleTexts = collectAllVisibleTexts(root)

        val extractedPhone = WhatsAppPickerCoordinator.extractPhoneNumberFromTexts(priorityTexts)
            ?: WhatsAppPickerCoordinator.extractPhoneNumberFromTexts(allVisibleTexts)

        val resolvedName = session.capturedChatName.ifBlank {
            extractContactInfoName(root)
        }

        if (!extractedPhone.isNullOrBlank()) {
            completePickAndReturn(
                service,
                PickedWhatsAppRecipient(
                    displayName = resolvedName,
                    phoneNumber = extractedPhone,
                    isGroup = false
                )
            )
            return
        }

        // Scroll once in case "About and phone number" sits below the action buttons
        if (!session.hasScrolledInfo && now - session.lastActionTimestamp > 450L) {
            session.hasScrolledInfo = true
            session.lastActionTimestamp = now
            findScrollableContainer(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            return
        }

        // Safety fallback if Contact Info has no visible phone (e.g., hidden business line)
        if (now - session.lastActionTimestamp > 2200L && resolvedName.isNotBlank()) {
            completePickAndReturn(
                service,
                PickedWhatsAppRecipient(
                    displayName = resolvedName,
                    phoneNumber = "",
                    isGroup = false
                )
            )
        }
    }

    private fun completePickAndReturn(
        service: AccessibilityService,
        recipient: PickedWhatsAppRecipient
    ) {
        WhatsAppPickerCoordinator.emitPickedAndFinish(recipient)
        WhatsAppPickerCoordinator.returnToSandesh(service)
        val appContext = service.applicationContext
        if (JitAccessibilityController.hasWriteSecureSettings(appContext)) {
            mainHandler.postDelayed({
                JitAccessibilityController.ensureIdleOffIfJitEnabled(appContext)
            }, 250L)
        }
    }

    private fun isChatConversation(root: AccessibilityNodeInfo): Boolean {
        val ids = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
        return ids.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }
    }

    private fun extractConversationTitle(root: AccessibilityNodeInfo): String {
        val titleIds = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
        for (id in titleIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
            if (node != null) return node.text.toString()
        }
        return ""
    }

    private fun findConversationHeaderNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val headerIds = listOf(
            "com.whatsapp:id/conversation_contact",
            "com.whatsapp.w4b:id/conversation_contact",
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
        for (id in headerIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (node != null) return node
        }
        return null
    }

    private fun extractContactInfoName(root: AccessibilityNodeInfo): String {
        val nameIds = listOf(
            "com.whatsapp:id/contact_info_name",
            "com.whatsapp.w4b:id/contact_info_name",
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp:id/contact_title",
            "com.whatsapp.w4b:id/contact_title"
        )
        for (id in nameIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
            if (node != null) return node.text.toString().trim()
        }
        return ""
    }

    private fun collectPriorityInfoTexts(root: AccessibilityNodeInfo): List<String> {
        val phoneIds = listOf(
            "com.whatsapp:id/phone_number",
            "com.whatsapp.w4b:id/phone_number",
            "com.whatsapp:id/about_and_phone_number",
            "com.whatsapp.w4b:id/about_and_phone_number",
            "com.whatsapp:id/contact_subtitle",
            "com.whatsapp.w4b:id/contact_subtitle",
            "com.whatsapp:id/subtitle",
            "com.whatsapp.w4b:id/subtitle",
            "com.whatsapp:id/title",
            "com.whatsapp.w4b:id/title"
        )
        val result = mutableListOf<String>()
        for (id in phoneIds) {
            root.findAccessibilityNodeInfosByViewId(id).forEach { node ->
                if (node.isVisibleToUser && !node.text.isNullOrBlank()) {
                    result += node.text.toString()
                }
            }
        }
        return result
    }

    private fun collectAllVisibleTexts(root: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.isVisibleToUser) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return texts
    }

    private fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.isScrollable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun isIgnoredTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower == "whatsapp" || lower == "whatsapp business" || lower == "search"
    }
}
