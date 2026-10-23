package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.eddyizm.tempus.R
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The beat map shown while Eternal mode is active.
 *
 * The horizontal axis is the source track's beat axis. Faint arcs are every transition the
 * analyzer found; the live arc/cursor shows where the endless walk is right now, and recent
 * jumps are drawn as fading trails. A coverage strip along the bottom shows which beats the
 * session has visited. The existing time bar stays below this view and remains the seek control.
 */
class EternalTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Jump(val from: Int, val to: Int, val atMs: Long)

    private val density = resources.displayMetrics.density
    private val primary = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimary, Color.GRAY
    )
    private val tertiary = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorTertiary, primary
    )
    private val onSurface = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE
    )
    private val onSurfaceVariant = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface
    )
    private val surfaceVariant = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorSurfaceVariant, Color.DKGRAY
    )

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(surfaceVariant, 24)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
        color = ColorUtils.setAlphaComponent(onSurface, 18)
    }
    private val staticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        strokeCap = Paint.Cap.ROUND
        color = ColorUtils.setAlphaComponent(tertiary, 150)
    }
    private val jumpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = tertiary
    }
    private val cursorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
        color = ColorUtils.setAlphaComponent(primary, 26)
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val coveragePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.035f
    }

    private val surfaceRect = RectF()
    private val coverageRect = RectF()
    private val staticPath = Path()
    private val branchPath = Path()
    private val jumpPath = Path()

    private var beatCount = 0
    private var branches = IntArray(0)
    private var visits = IntArray(0)
    private var currentBeat = 0
    private var jumpCount = 0L
    private var positionMs = 0L
    private var durationMs = 0L
    private var lastBeat = -1
    private var lastJumpCount = 0L
    private var lastLabelSecond = -1L
    private var lastDescriptionSecond = -1L

    private val jumps = ArrayDeque<Jump>()

    private var beatLabel = ""
    private var jumpLabel = ""

    private var geometryWidth = -1
    private var geometryHeight = -1
    private var branchPathBeat = -1
    private var staticPathValid = false
    private var branchPathValid = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = false
        isFocusable = false
    }

    /** Supplies the static beat graph for the current session. */
    fun setMap(beatCount: Int, branches: IntArray) {
        val safeBeatCount = beatCount.coerceAtLeast(0)
        this.beatCount = safeBeatCount
        this.branches = if (branches.size % 2 == 0) branches.copyOf() else IntArray(0)
        visits = IntArray(safeBeatCount)
        jumps.clear()
        currentBeat = 0
        jumpCount = 0L
        positionMs = 0L
        durationMs = 0L
        lastBeat = -1
        lastJumpCount = 0L
        lastLabelSecond = -1L
        lastDescriptionSecond = -1L
        beatLabel = ""
        jumpLabel = ""
        staticPathValid = false
        branchPathValid = false
        geometryWidth = -1
        geometryHeight = -1
        updateLabels()
        updateContentDescription()
        invalidate()
    }

    /** Supplies the latest source position and planner counters. */
    fun setTelemetry(positionMs: Long, durationMs: Long, beat: Int, jumpCount: Long) {
        if (beatCount <= 0) return

        val safeBeat = beat.coerceIn(0, beatCount - 1)
        val now = SystemClock.uptimeMillis()
        val newSecond = positionMs.coerceAtLeast(0L) / 1_000L
        var changed = false

        if (lastBeat != safeBeat) {
            if (lastBeat in 0 until beatCount) {
                visits[lastBeat] = (visits[lastBeat] + 1).coerceAtMost(MAX_VISITS)
            }
            visits[safeBeat] = (visits[safeBeat] + 1).coerceAtMost(MAX_VISITS)
            if (lastBeat >= 0 && jumpCount > lastJumpCount) {
                jumps.addLast(Jump(lastBeat, safeBeat, now))
                while (jumps.size > MAX_JUMPS) jumps.removeFirst()
            }
            lastBeat = safeBeat
            changed = true
        }

        if (lastJumpCount != jumpCount) changed = true
        lastJumpCount = jumpCount
        currentBeat = safeBeat
        this.jumpCount = jumpCount
        this.positionMs = positionMs.coerceAtLeast(0L)
        this.durationMs = durationMs.coerceAtLeast(0L)
        if (branchPathBeat != safeBeat) branchPathValid = false

        if (changed || newSecond != lastLabelSecond) {
            updateLabels()
        }
        if (changed || newSecond != lastDescriptionSecond) {
            updateContentDescription()
        }
        invalidate()
    }

    /** Clears the map and telemetry, e.g. when leaving Eternal mode. */
    fun clear() {
        beatCount = 0
        branches = IntArray(0)
        visits = IntArray(0)
        jumps.clear()
        currentBeat = 0
        jumpCount = 0L
        positionMs = 0L
        durationMs = 0L
        lastBeat = -1
        lastJumpCount = 0L
        lastLabelSecond = -1L
        lastDescriptionSecond = -1L
        beatLabel = ""
        jumpLabel = ""
        staticPathValid = false
        branchPathValid = false
        geometryWidth = -1
        geometryHeight = -1
        contentDescription = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        geometryWidth = -1
        geometryHeight = -1
        staticPathValid = false
        branchPathValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right - left < dp(8f) || bottom - top < dp(8f)) return

        val labelHeight = sp(13f) + dp(2f)
        val coverageHeight = dp(3f)
        val baseline = bottom - coverageHeight - dp(2f)
        val mapTop = top + labelHeight
        val mapHeight = baseline - mapTop
        if (mapHeight <= dp(4f)) return
        val contentWidth = right - left

        ensureGeometry(contentWidth, mapTop, baseline, mapHeight)

        surfaceRect.set(left, top, right, bottom)
        canvas.drawRoundRect(surfaceRect, dp(8f), dp(8f), backgroundPaint)
        canvas.drawRoundRect(surfaceRect, dp(8f), dp(8f), outlinePaint)

        drawLabels(canvas, left, right, top)

        canvas.withClip(left, top, right, bottom) {
            // Faint static transitions: the shape of the track's jump graph.
            if (staticPathValid) {
                canvas.drawPath(staticPath, staticPaint)
            }

            // Baseline of the beat axis.
            labelPaint.color = ColorUtils.setAlphaComponent(onSurface, 24)
            labelPaint.style = Paint.Style.STROKE
            labelPaint.strokeWidth = dp(0.75f)
            canvas.drawLine(left, baseline, right, baseline, labelPaint)
            labelPaint.style = Paint.Style.FILL

            drawCoverage(canvas, left, right, bottom - coverageHeight, coverageHeight)

            // Branches that the current beat can take next.
            if (branchPathValid) {
                canvas.drawPath(branchPath, branchPaint)
            }

            // Recent jumps, newest on top, fading with age.
            val now = SystemClock.uptimeMillis()
            jumps.removeAll { now - it.atMs > JUMP_FADE_MS }
            for (jump in jumps) {
                val age = (now - jump.atMs).toFloat() / JUMP_FADE_MS
                val alpha = (1f - age).coerceIn(0f, 1f)
                if (alpha <= 0f) continue
                jumpPath.reset()
                addConnection(
                    jumpPath,
                    x(jump.from, left, contentWidth),
                    x(jump.to, left, contentWidth),
                    baseline,
                    mapHeight,
                )
                jumpPaint.color = ColorUtils.setAlphaComponent(tertiary, (alpha * 210).toInt())
                jumpPaint.strokeWidth = dp(1.2f + alpha * 0.8f)
                canvas.drawPath(jumpPath, jumpPaint)

                dotPaint.color = ColorUtils.setAlphaComponent(tertiary, (alpha * 180).toInt())
                canvas.drawCircle(
                    x(jump.to, left, contentWidth),
                    baseline,
                    dp(1.8f),
                    dotPaint,
                )
            }

            // Live beat cursor.
            if (beatCount > 0) {
                val cursorX = x(currentBeat, left, contentWidth)
                canvas.drawLine(cursorX, mapTop, cursorX, baseline, cursorGlowPaint)
                cursorPaint.shader = null
                canvas.drawLine(cursorX, mapTop, cursorX, baseline, cursorPaint)
                dotPaint.color = primary
                canvas.drawCircle(cursorX, baseline, dp(2.2f), dotPaint)
                dotPaint.color = ColorUtils.setAlphaComponent(onSurface, 160)
                canvas.drawCircle(cursorX, baseline, dp(1.0f), dotPaint)
            }

        }
    }

    private fun ensureGeometry(contentWidth: Float, mapTop: Float, baseline: Float, mapHeight: Float) {
        if (geometryWidth == width && geometryHeight == height && staticPathValid) {
            if (branchPathBeat == currentBeat && branchPathValid) return
        }

        if (geometryWidth != width || geometryHeight != height) {
            geometryWidth = width
            geometryHeight = height
            staticPath.reset()
            branchPath.reset()
            staticPathValid = false
            branchPathValid = false
            branchPathBeat = -1
            buildStaticPath(contentWidth, baseline, mapHeight)
            cursorPaint.shader = LinearGradient(
                0f,
                mapTop,
                0f,
                baseline,
                ColorUtils.setAlphaComponent(primary, 0),
                ColorUtils.setAlphaComponent(primary, 235),
                Shader.TileMode.CLAMP,
            )
        }

        if (!branchPathValid || branchPathBeat != currentBeat) {
            buildBranchPath(contentWidth, baseline, mapHeight)
            branchPathBeat = currentBeat
            branchPathValid = true
        }
    }

    private fun buildStaticPath(contentWidth: Float, baseline: Float, mapHeight: Float) {
        staticPath.reset()
        if (beatCount < 2 || branches.size < 2) {
            staticPathValid = true
            return
        }

        // Merge arcs at roughly two-pixel resolution. Dense analyses otherwise draw the same
        // path hundreds of times and the static graph is the most expensive part of onDraw.
        val columns = max(1, (contentWidth / dp(2f)).toInt())
        val seen = HashSet<Long>(branches.size)
        var index = 0
        while (index + 1 < branches.size) {
            val source = branches[index]
            val destination = branches[index + 1]
            index += 2
            if (source !in 0 until beatCount || destination !in 0 until beatCount) continue
            if (source == beatCount - 1 && destination == 0) continue

            val from = x(source, paddingLeft.toFloat(), contentWidth)
            val to = x(destination, paddingLeft.toFloat(), contentWidth)
            val first = ((min(from, to) - paddingLeft) / contentWidth * columns).toInt()
            val second = ((max(from, to) - paddingLeft) / contentWidth * columns).toInt()
            val key = (first.toLong() shl 32) or second.toLong()
            if (!seen.add(key)) continue
            addConnection(staticPath, from, to, baseline, mapHeight)
        }

        staticPaint.shader = LinearGradient(
            0f,
            baseline - mapHeight,
            0f,
            baseline,
            ColorUtils.setAlphaComponent(primary, 42),
            ColorUtils.setAlphaComponent(primary, 4),
            Shader.TileMode.CLAMP,
        )
        staticPathValid = true
    }

    private fun buildBranchPath(contentWidth: Float, baseline: Float, mapHeight: Float) {
        branchPath.reset()
        if (beatCount < 2 || branches.size < 2) return
        val left = paddingLeft.toFloat()
        val from = x(currentBeat, left, contentWidth)
        var index = 0
        while (index + 1 < branches.size) {
            val source = branches[index]
            val destination = branches[index + 1]
            index += 2
            if (source != currentBeat) continue
            if (destination !in 0 until beatCount) continue
            addConnection(branchPath, from, x(destination, left, contentWidth), baseline, mapHeight)
        }
    }

    private fun addConnection(
        path: Path,
        from: Float,
        to: Float,
        baseline: Float,
        mapHeight: Float,
    ) {
        val width = max(1f, (width - paddingLeft - paddingRight).toFloat())
        val span = abs(to - from)
        val controlY = baseline - sqrt(span / width) * mapHeight * 1.18f
        path.moveTo(from, baseline)
        path.cubicTo(from, controlY, to, controlY, to, baseline)
    }

    private fun drawCoverage(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
    ) {
        if (visits.isEmpty() || beatCount <= 0) return
        val maxVisits = visits.maxOrNull() ?: 0
        if (maxVisits <= 0) return

        val bins = min(64, max(1, ((right - left) / dp(3f)).toInt()))
        val binWidth = (right - left) / bins
        val gap = dp(1f)
        for (bin in 0 until bins) {
            val start = bin * beatCount / bins
            val end = max(start + 1, (bin + 1) * beatCount / bins)
            var value = 0
            for (beat in start until min(end, beatCount)) {
                value = max(value, visits[beat])
            }
            if (value <= 0) continue
            val ratio = sqrt(value.toFloat() / maxVisits)
            val barHeight = max(dp(1f), height * (0.35f + ratio * 0.65f))
            coveragePaint.color = ColorUtils.setAlphaComponent(
                primary,
                (20 + ratio * 150).toInt(),
            )
            coverageRect.set(
                left + bin * binWidth,
                top + height - barHeight,
                left + bin * binWidth + max(dp(1f), binWidth - gap),
                top + height,
            )
            canvas.drawRoundRect(coverageRect, dp(1f), dp(1f), coveragePaint)
        }
    }

    private fun drawLabels(canvas: Canvas, left: Float, right: Float, top: Float) {
        if (beatLabel.isEmpty()) return
        val baseline = top + sp(10.5f)
        labelPaint.style = Paint.Style.FILL
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = ColorUtils.setAlphaComponent(onSurface, 150)
        canvas.drawText(beatLabel, left + dp(8f), baseline, labelPaint)

        if (jumpLabel.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.RIGHT
            labelPaint.color = if (jumpCount > 0) {
                ColorUtils.setAlphaComponent(tertiary, 210)
            } else {
                ColorUtils.setAlphaComponent(onSurfaceVariant, 110)
            }
            canvas.drawText(jumpLabel, right - dp(8f), baseline, labelPaint)
        }
    }

    private fun updateLabels() {
        lastLabelSecond = positionMs / 1_000L
        beatLabel = if (beatCount > 0) {
            context.getString(R.string.player_eternal_beat, currentBeat + 1, beatCount)
        } else {
            ""
        }
        jumpLabel = if (beatCount > 0) {
            val time = DateUtils.formatElapsedTime(lastLabelSecond)
            context.getString(R.string.player_eternal_time_jumps, time, jumpWords(jumpCount))
        } else {
            ""
        }
    }

    private fun updateContentDescription() {
        lastDescriptionSecond = positionMs / 1_000L
        contentDescription = if (beatCount > 0) {
            context.getString(
                R.string.player_eternal_map_description,
                currentBeat + 1,
                beatCount,
                DateUtils.formatElapsedTime(lastDescriptionSecond),
                DateUtils.formatElapsedTime(durationMs / 1_000L),
                jumpWords(jumpCount),
            )
        } else {
            null
        }
    }

    private fun jumpWords(count: Long): String {
        val quantity = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return resources.getQuantityString(R.plurals.player_eternal_jumps, quantity, count)
    }

    private fun x(beat: Int, left: Float, contentWidth: Float): Float {
        if (beatCount <= 1) return left
        return left + beat.coerceIn(0, beatCount - 1).toFloat() / (beatCount - 1) * contentWidth
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        private const val MAX_JUMPS = 6
        private const val JUMP_FADE_MS = 6_000L
        private const val MAX_VISITS = 1_000_000
    }
}
