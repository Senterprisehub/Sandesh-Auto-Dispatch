package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

@Composable
fun PatternLockPad(
    onSavePattern: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedNodes = remember { mutableStateListOf<Int>() }
    var activeFingerPos by remember { mutableStateOf<Offset?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (selectedNodes.isEmpty()) {
                "Draw your 3x3 lock pattern below (min 4 dots)"
            } else {
                "Pattern sequence captured (${selectedNodes.size} dots)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Canvas(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .testTag("pattern_lock_canvas")
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            selectedNodes.clear()
                            activeFingerPos = startOffset
                            hitTestNode(startOffset, size.width.toFloat(), size.height.toFloat())?.let {
                                selectedNodes.add(it)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val pos = change.position
                            activeFingerPos = pos
                            hitTestNode(pos, size.width.toFloat(), size.height.toFloat())?.let { node ->
                                if (node !in selectedNodes) {
                                    selectedNodes.add(node)
                                }
                            }
                        },
                        onDragEnd = { activeFingerPos = null },
                        onDragCancel = { activeFingerPos = null }
                    )
                }
        ) {
            val centers = computeNodeCenters(size.width, size.height)

            // Draw connecting lines
            if (selectedNodes.isNotEmpty()) {
                val path = Path()
                selectedNodes.forEachIndexed { idx, nodeIdx ->
                    val center = centers[nodeIdx]
                    if (idx == 0) path.moveTo(center.x, center.y) else path.lineTo(center.x, center.y)
                }
                activeFingerPos?.let { finger ->
                    path.lineTo(finger.x, finger.y)
                }
                drawPath(
                    path = path,
                    color = primaryColor.copy(alpha = 0.7f),
                    style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Draw 9 dots
            centers.forEachIndexed { idx, center ->
                val isSelected = idx in selectedNodes
                drawCircle(
                    color = if (isSelected) primaryContainer else surfaceColor,
                    radius = 28f,
                    center = center
                )
                drawCircle(
                    color = if (isSelected) primaryColor else outlineColor,
                    radius = 28f,
                    center = center,
                    style = Stroke(width = if (isSelected) 4f else 2f)
                )
                drawCircle(
                    color = if (isSelected) primaryColor else outlineColor,
                    radius = if (isSelected) 12f else 8f,
                    center = center
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            OutlinedButton(
                onClick = { selectedNodes.clear() },
                modifier = Modifier.testTag("clear_pattern_button")
            ) {
                Text("Clear")
            }
            Button(
                onClick = { onSavePattern(selectedNodes.joinToString("")) },
                enabled = selectedNodes.size >= 4,
                modifier = Modifier.testTag("save_pattern_button")
            ) {
                Text("Save Pattern")
            }
        }
    }
}

private fun computeNodeCenters(width: Float, height: Float): List<Offset> {
    val cellW = width / 3f
    val cellH = height / 3f
    return (0 until 9).map { idx ->
        val row = idx / 3
        val col = idx % 3
        Offset(cellW * (col + 0.5f), cellH * (row + 0.5f))
    }
}

private fun hitTestNode(point: Offset, width: Float, height: Float): Int? {
    val centers = computeNodeCenters(width, height)
    val hitRadius = (minOf(width, height) / 6f) * 0.75f
    centers.forEachIndexed { idx, center ->
        if (hypot(point.x - center.x, point.y - center.y) <= hitRadius) {
            return idx
        }
    }
    return null
}
