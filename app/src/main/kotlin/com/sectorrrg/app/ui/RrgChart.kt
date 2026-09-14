package com.sectorrrg.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.sectorrrg.core.RrgPoint
import com.sectorrrg.data.SectorView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val Q_LEADING = Color(0x1A22C55E)
private val Q_WEAKENING = Color(0x1AF59E0B)
private val Q_LAGGING = Color(0x1AEF4444)
private val Q_IMPROVING = Color(0x1A3B82F6)
private val AXIS = Color(0x55FFFFFF)
private val LABEL = Color(0x99FFFFFF)

@OptIn(ExperimentalTextApi::class)
@Composable
fun RrgChart(
    sectors: List<SectorView>,
    tailWeeks: Int,
    endFraction: Float,
    highlighted: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val zoom = remember { mutableFloatStateOf(1f) }

    // 每個板塊嘅可見尾巴，終點由時間滑桿決定
    val trails = remember(sectors, tailWeeks, endFraction) {
        sectors.mapNotNull { v ->
            val pts = v.trail.points
            if (pts.isEmpty()) return@mapNotNull null
            val end = ((pts.size - 1) * endFraction).roundToInt().coerceIn(0, pts.size - 1)
            val start = max(0, end - tailWeeks + 1)
            v to pts.subList(start, end + 1)
        }
    }

    // 中軸必須喺畫面正中，否則象限視覺上會誤導
    val dev = remember(trails, zoom.floatValue) {
        val m = trails.flatMap { it.second }
            .maxOfOrNull { max(abs(it.ratio - 100.0), abs(it.momentum - 100.0)) } ?: 2.0
        (max(m, 1.5) * 1.20 / zoom.floatValue).toFloat()
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, z, _ ->
                    zoom.floatValue = (zoom.floatValue * z).coerceIn(0.5f, 4f)
                }
            }
            .pointerInput(trails, dev) {
                detectTapGestures { tap ->
                    val canvas = size.toSize()
                    var best: String? = null
                    var bestD = Float.MAX_VALUE
                    for ((view, pts) in trails) {
                        val d = (projectPoint(pts.last(), dev, canvas) - tap).getDistance()
                        if (d < bestD) { bestD = d; best = view.symbol }
                    }
                    onSelect(if (bestD < 72f) best else null)
                }
            }
    ) {
        drawQuadrants(tm)

        for ((view, pts) in trails) {
            val color = Color(view.colorArgb.toInt())
            val dim = highlighted != null && highlighted != view.symbol
            val screen = pts.map { projectPoint(it, dev, size) }

            // 尾巴越舊越淡越細，一眼睇得出行進方向
            for (i in 1 until screen.size) {
                val age = i.toFloat() / screen.size
                drawLine(
                    color = color.copy(alpha = (if (dim) 0.10f else 0.85f) * (0.25f + 0.75f * age)),
                    start = screen[i - 1],
                    end = screen[i],
                    strokeWidth = 1.5f + 3.5f * age,
                )
            }
            val head = screen.last()
            drawCircle(color.copy(alpha = if (dim) 0.25f else 1f), if (dim) 4f else 9f, head)
            if (!dim) {
                val l = tm.measure(view.symbol, TextStyle(color = color, fontSize = 11.sp))
                drawText(l, topLeft = Offset(head.x + 11f, head.y - l.size.height / 2f))
            }
        }
    }
}

private fun projectPoint(p: RrgPoint, dev: Float, s: Size): Offset = Offset(
    x = ((p.ratio - 100.0).toFloat() / dev * 0.5f + 0.5f) * s.width,
    y = (0.5f - (p.momentum - 100.0).toFloat() / dev * 0.5f) * s.height, // Y 軸翻轉
)

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawQuadrants(tm: TextMeasurer) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawRect(Q_IMPROVING, Offset.Zero, Size(cx, cy))
    drawRect(Q_LEADING, Offset(cx, 0f), Size(cx, cy))
    drawRect(Q_LAGGING, Offset(0f, cy), Size(cx, cy))
    drawRect(Q_WEAKENING, Offset(cx, cy), Size(cx, cy))

    drawLine(AXIS, Offset(cx, 0f), Offset(cx, size.height), 1f)
    drawLine(AXIS, Offset(0f, cy), Offset(size.width, cy), 1f)

    val style = TextStyle(color = LABEL, fontSize = 10.sp)
    val tags = listOf(
        Triple("Improving 改善", 0f, false),
        Triple("Leading 領先", size.width, true),
    )
    for ((text, x, right) in tags) {
        val l = tm.measure(text, style)
        drawText(l, topLeft = Offset(if (right) x - l.size.width - 8f else x + 8f, 8f))
    }
    val bottom = listOf(
        Triple("Lagging 落後", 0f, false),
        Triple("Weakening 轉弱", size.width, true),
    )
    for ((text, x, right) in bottom) {
        val l = tm.measure(text, style)
        drawText(
            l,
            topLeft = Offset(
                if (right) x - l.size.width - 8f else x + 8f,
                size.height - l.size.height - 8f,
            ),
        )
    }
}
