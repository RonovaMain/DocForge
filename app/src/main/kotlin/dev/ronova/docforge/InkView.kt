package dev.ronova.docforge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Слой для рисования поверх страницы в режиме презентации: ручка, маркер, ластик, отмена.
 * Штрихи хранятся в долях страницы (0..1 по ширине и высоте), поэтому переживают поворот экрана
 * и без потерь переносятся в PDF ([PdfTools.bakeInk]).
 */
class InkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Tool { PEN, HIGHLIGHTER, ERASER }

    /** Один штрих: [points] — x0, y0, x1, y1… в долях страницы; [width] — толщина в долях ширины страницы. */
    class Stroke(val color: Int, val width: Float) {
        val points = ArrayList<Float>()
    }

    private class Removed(val index: Int, val stroke: Stroke)

    /** Действие для отмены: либо добавлен [added], либо удалены [removed] (в порядке удаления). */
    private class Op(val page: Int, val added: Stroke?, val removed: List<Removed>?)

    private val strokes = HashMap<Int, ArrayList<Stroke>>()
    private val history = ArrayList<Op>()
    private val pageRect = RectF()
    private var page = 0
    private var live: Stroke? = null
    private var erased: ArrayList<Removed>? = null
    private var lastX = 0f
    private var lastY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    var tool: Tool = Tool.PEN
    var penColor: Int = 0xFFF44336.toInt()
    var markerColor: Int = 0xFFFFEB3B.toInt()

    /** Толщина (1..[MAX_LEVEL]) отдельно для ручки, маркера и ластика; 4 — обычная. */
    var penLevel: Int = DEFAULT_LEVEL
    var markerLevel: Int = DEFAULT_LEVEL
    var eraserLevel: Int = DEFAULT_LEVEL

    fun currentLevel(): Int = when (tool) {
        Tool.PEN -> penLevel
        Tool.HIGHLIGHTER -> markerLevel
        Tool.ERASER -> eraserLevel
    }

    fun setCurrentLevel(level: Int) {
        val value = level.coerceIn(1, MAX_LEVEL)
        when (tool) {
            Tool.PEN -> penLevel = value
            Tool.HIGHLIGHTER -> markerLevel = value
            Tool.ERASER -> eraserLevel = value
        }
    }

    /** Пока false, слой только показывает штрихи и пропускает касания дальше (к WebView). */
    var drawingEnabled: Boolean = false

    /** Есть изменения, не попавшие в PDF. */
    var dirty: Boolean = false
        private set

    var onChanged: (() -> Unit)? = null

    /** Страница [index] (с нуля) нарисована в прямоугольнике [rect] (координаты этого View). */
    fun setPage(index: Int, rect: RectF) {
        page = index
        pageRect.set(rect)
        invalidate()
        onChanged?.invoke()
    }

    fun hasInk(): Boolean = strokes.values.any { it.isNotEmpty() }

    fun hasInkOnPage(): Boolean = strokes[page]?.isNotEmpty() == true

    fun needsSave(): Boolean = dirty && hasInk()

    fun canUndo(): Boolean = history.isNotEmpty()

    fun markSaved() {
        dirty = false
    }

    fun clearAll() {
        strokes.clear()
        history.clear()
        live = null
        erased = null
        dirty = false
        invalidate()
        onChanged?.invoke()
    }

    /** Копия всех штрихов (страница с нуля → штрихи) — для записи в PDF в фоновом потоке. */
    fun snapshot(): Map<Int, List<Stroke>> {
        val result = HashMap<Int, List<Stroke>>()
        for ((index, list) in strokes) {
            if (list.isEmpty()) continue
            result[index] = list.map { s -> Stroke(s.color, s.width).also { it.points.addAll(s.points) } }
        }
        return result
    }

    fun undo() {
        if (history.isEmpty()) return
        val op = history.removeAt(history.size - 1)
        val list = strokes.getOrPut(op.page) { ArrayList() }
        val added = op.added
        if (added != null) {
            list.remove(added)
        } else {
            val removed = op.removed.orEmpty()
            for (i in removed.indices.reversed()) {
                val r = removed[i]
                list.add(min(r.index, list.size), r.stroke)
            }
        }
        dirty = true
        invalidate()
        onChanged?.invoke()
    }

    fun clearPage() {
        val list = strokes[page] ?: return
        if (list.isEmpty()) return
        val removed = ArrayList<Removed>()
        while (list.isNotEmpty()) removed.add(Removed(0, list.removeAt(0)))
        history.add(Op(page, null, removed))
        dirty = true
        invalidate()
        onChanged?.invoke()
    }

    // ------------------------------------------------------------------ рисование

    override fun onDraw(canvas: Canvas) {
        if (pageRect.isEmpty) return
        val list = strokes[page] ?: return
        canvas.save()
        canvas.clipRect(pageRect)
        for (s in list) drawStroke(canvas, s)
        canvas.restore()
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        val p = s.points
        if (p.size < 2) return
        val w = pageRect.width()
        val h = pageRect.height()
        paint.color = s.color
        paint.strokeWidth = max(s.width * w, 1f)
        val x0 = pageRect.left + p[0] * w
        val y0 = pageRect.top + p[1] * h
        if (p.size < 4) {
            canvas.drawPoint(x0, y0, paint)
            return
        }
        path.reset()
        path.moveTo(x0, y0)
        var prevX = x0
        var prevY = y0
        var i = 2
        while (i < p.size) {
            val x = pageRect.left + p[i] * w
            val y = pageRect.top + p[i + 1] * h
            path.quadTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
            prevX = x
            prevY = y
            i += 2
        }
        path.lineTo(prevX, prevY)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled || pageRect.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) move(event.getHistoricalX(i), event.getHistoricalY(i))
                move(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                move(event.x, event.y)
                finish()
            }
            MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }

    private fun begin(x: Float, y: Float) {
        lastX = x
        lastY = y
        if (tool == Tool.ERASER) {
            erased = ArrayList()
            eraseAt(x, y)
            return
        }
        val stroke = if (tool == Tool.HIGHLIGHTER) {
            Stroke(withAlpha(markerColor, MARKER_ALPHA), MARKER_WIDTH * markerLevel / DEFAULT_LEVEL)
        } else {
            Stroke(withAlpha(penColor, 255), PEN_WIDTH * penLevel / DEFAULT_LEVEL)
        }
        stroke.points.add(norm(x, pageRect.left, pageRect.width()))
        stroke.points.add(norm(y, pageRect.top, pageRect.height()))
        strokes.getOrPut(page) { ArrayList() }.add(stroke)
        live = stroke
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        if (tool == Tool.ERASER) {
            if (erased != null) eraseAt(x, y)
            return
        }
        val stroke = live ?: return
        if (hypot(x - lastX, y - lastY) < MIN_STEP * resources.displayMetrics.density) return
        lastX = x
        lastY = y
        stroke.points.add(norm(x, pageRect.left, pageRect.width()))
        stroke.points.add(norm(y, pageRect.top, pageRect.height()))
        invalidate()
    }

    private fun finish() {
        val stroke = live
        if (stroke != null) {
            history.add(Op(page, stroke, null))
            dirty = true
            live = null
        }
        val removed = erased
        if (removed != null) {
            if (removed.isNotEmpty()) {
                history.add(Op(page, null, removed))
                dirty = true
            }
            erased = null
        }
        onChanged?.invoke()
    }

    /** Ластик стирает штрихи целиком: всё, чего коснулся. */
    private fun eraseAt(x: Float, y: Float) {
        val list = strokes[page] ?: return
        val removed = erased ?: return
        val radius = ERASER_RADIUS * eraserLevel / DEFAULT_LEVEL * resources.displayMetrics.density
        var changed = false
        for (i in list.indices.reversed()) {
            if (touches(list[i], x, y, radius)) {
                removed.add(Removed(i, list.removeAt(i)))
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun touches(s: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val p = s.points
        val w = pageRect.width()
        val h = pageRect.height()
        val reach = radius + s.width * w / 2f
        var px = pageRect.left + p[0] * w
        var py = pageRect.top + p[1] * h
        if (hypot(x - px, y - py) <= reach) return true
        var i = 2
        while (i < p.size) {
            val cx = pageRect.left + p[i] * w
            val cy = pageRect.top + p[i + 1] * h
            if (distanceToSegment(x, y, px, py, cx, cy) <= reach) return true
            px = cx
            py = cy
            i += 2
        }
        return false
    }

    private fun distanceToSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(x - ax, y - ay)
        val t = (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(x - (ax + t * dx), y - (ay + t * dy))
    }

    private fun norm(value: Float, start: Float, size: Float): Float =
        if (size <= 0f) 0f else ((value - start) / size).coerceIn(0f, 1f)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        const val MAX_LEVEL = 12
        const val DEFAULT_LEVEL = 4
        private const val PEN_WIDTH = 0.0045f
        private const val MARKER_WIDTH = 0.028f
        private const val MARKER_ALPHA = 0x66
        private const val MIN_STEP = 1.5f
        private const val ERASER_RADIUS = 14f
    }
}
