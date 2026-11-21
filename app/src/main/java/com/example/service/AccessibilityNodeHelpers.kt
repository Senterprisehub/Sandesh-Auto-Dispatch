package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityNodeHelpers {

    fun clickOrTap(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(3) {
            if (current != null && current!!.isClickable && current!!.isEnabled) {
                if (current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = current?.parent
        }
        return tapNodeCenter(service, node)
    }

    fun tapNodeCenter(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val path = Path().apply {
            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 55L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun swipeUpToUnlock(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        val width = bounds.width().takeIf { it > 0 } ?: 1080
        val height = bounds.height().takeIf { it > 0 } ?: 2200
        val x = width * 0.5f
        val startY = height * 0.82f
        val endY = height * 0.25f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun findKeyguardDigitNode(root: AccessibilityNodeInfo, digit: Char): AccessibilityNodeInfo? {
        val digitStr = digit.toString()
        val idCandidates = listOf(
            "com.android.systemui:id/key$digit",
            "com.android.systemui:id/digit_$digit",
            "com.android.systemui:id/num_$digit"
        )
        for (id in idCandidates) {
            val found = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (found != null) return found
        }

        val byText = root.findAccessibilityNodeInfosByText(digitStr)
        return byText.firstOrNull { node ->
            if (!node.isVisibleToUser) return@firstOrNull false
            val textMatch = node.text?.toString()?.trim() == digitStr
            val descMatch = node.contentDescription?.toString()?.trim()?.startsWith(digitStr) == true
            (textMatch || descMatch) && (node.isClickable || node.parent?.isClickable == true)
        }
    }

    fun findKeyguardEnterNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.android.systemui:id/key_enter",
            "com.android.systemui:id/ok",
            "com.android.systemui:id/confirm_button",
            "com.android.systemui:id/btn_ok"
        )
        for (id in ids) {
            val found = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (found != null) return found
        }
        val labels = listOf("Enter", "OK", "Done", "Submit")
        for (label in labels) {
            val found = root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.isVisibleToUser && (it.isClickable || it.parent?.isClickable == true) }
            if (found != null) return found
        }
        return null
    }

    fun findPatternViewBounds(root: AccessibilityNodeInfo): Rect? {
        val ids = listOf(
            "com.android.systemui:id/lockPatternView",
            "com.android.systemui:id/lockPattern",
            "com.android.systemui:id/pattern_view"
        )
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (node != null) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 150 && r.height() > 150) return r
            }
        }
        // Fallback: search tree for class name containing LockPatternView
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("LockPatternView", ignoreCase = true) && node.isVisibleToUser) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 150 && r.height() > 150) return r
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    fun drawPatternGesture(
        service: AccessibilityService,
        bounds: Rect,
        nodesSequence: CharArray
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val indices = nodesSequence.toList().mapNotNull { ch -> ch.digitToIntOrNull()?.takeIf { it in 0..8 } }
        if (indices.size < 2) return false

        val cellW = bounds.width() / 3f
        val cellH = bounds.height() / 3f
        val path = Path()
        indices.forEachIndexed { order, cell ->
            val row = cell / 3
            val col = cell % 3
            val cx = bounds.left + cellW * (col + 0.5f)
            val cy = bounds.top + cellH * (row + 0.5f)
            if (order == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
        }
        val duration = (indices.size * 110L).coerceIn(320L, 1400L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun findFirstEditableField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    fun setText(node: AccessibilityNodeInfo, text: CharSequence): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
