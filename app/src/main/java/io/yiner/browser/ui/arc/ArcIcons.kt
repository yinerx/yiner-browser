package io.yiner.browser.ui.arc

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 银耳浏览器自绘的线性图标（24×24 视口，1.8 线宽，圆端点）。
 * 只用通用几何形状表达含义，不取自任何第三方图标集或品牌素材。
 */
object ArcIcons {
    private fun icon(name: String, width: Float = 1.8f, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t); lineTo(r - rad, t)
        arcTo(rad, rad, 0f, false, true, r, t + rad); lineTo(r, b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b); lineTo(l + rad, b)
        arcTo(rad, rad, 0f, false, true, l, b - rad); lineTo(l, t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t); close()
    }

    /** 01/04 中的“+”线条较粗（约 2dp），单独加粗。 */
    val Plus = icon("plus", 2.4f) { moveTo(12f, 3.5f); lineTo(12f, 20.5f); moveTo(3.5f, 12f); lineTo(20.5f, 12f) }
    val ChevronUpBold = icon("chevron_up_bold", 2.4f) { moveTo(5f, 15.5f); lineTo(12f, 8.5f); lineTo(19f, 15.5f) }
    val ChevronUp = icon("chevron_up") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val ChevronLeft = icon("chevron_left") { moveTo(15f, 5.5f); lineTo(8.5f, 12f); lineTo(15f, 18.5f) }
    val ChevronRight = icon("chevron_right") { moveTo(9f, 5.5f); lineTo(15.5f, 12f); lineTo(9f, 18.5f) }
    val ArrowBack = icon("arrow_back") { moveTo(20f, 12f); lineTo(4.5f, 12f); moveTo(10.5f, 6f); lineTo(4.5f, 12f); lineTo(10.5f, 18f) }
    val Close = icon("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Check = icon("check") { moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f) }
    val Minus = icon("minus") { moveTo(5f, 12f); lineTo(19f, 12f) }

    /** 两个相扣的圆角环，表示“链接”。 */
    val Link = icon("link") {
        roundRect(2.5f, 8f, 13.5f, 16f, 4f)
        roundRect(10.5f, 8f, 21.5f, 16f, 4f)
    }
    /** 刷新：带箭头的开口圆弧。 */
    val Refresh = icon("refresh") {
        moveTo(19f, 12f)
        arcTo(7f, 7f, 0f, true, true, 16.9f, 7f)
        moveTo(17.5f, 3.5f); lineTo(17.5f, 7.5f); lineTo(13.5f, 7.5f)
    }
    /** 图钉：顶部横条 + 梯形钉帽 + 竖针。 */
    val Pin = icon("pin") {
        moveTo(7.5f, 3.5f); lineTo(16.5f, 3.5f)
        moveTo(9f, 3.5f); lineTo(9f, 10f); lineTo(6.5f, 14f); lineTo(17.5f, 14f); lineTo(15f, 10f); lineTo(15f, 3.5f)
        moveTo(12f, 14f); lineTo(12f, 20.5f)
    }
    /** 页内查找：文档框 + 放大镜。 */
    val Find = icon("find") {
        moveTo(12f, 4f); lineTo(6f, 4f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 13f)
        circle(16f, 7.5f, 3f)
        moveTo(18.2f, 9.7f); lineTo(20.5f, 12f)
    }
    /** 分享：三个节点两条连线。 */
    val Share = icon("share") {
        circle(17.5f, 5.5f, 2.5f); circle(6.5f, 12f, 2.5f); circle(17.5f, 18.5f, 2.5f)
        moveTo(8.7f, 10.7f); lineTo(15.3f, 6.8f); moveTo(8.7f, 13.3f); lineTo(15.3f, 17.2f)
    }
    /** 缩放：四角向外的箭头。 */
    val Zoom = icon("zoom") {
        moveTo(4f, 8.5f); lineTo(4f, 4f); lineTo(8.5f, 4f); moveTo(4f, 4f); lineTo(9f, 9f)
        moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f); moveTo(20f, 4f); lineTo(15f, 9f)
        moveTo(4f, 15.5f); lineTo(4f, 20f); lineTo(8.5f, 20f); moveTo(4f, 20f); lineTo(9f, 15f)
        moveTo(15.5f, 20f); lineTo(20f, 20f); lineTo(20f, 15.5f); moveTo(20f, 20f); lineTo(15f, 15f)
    }
    val Desktop = icon("desktop") { roundRect(3f, 4.5f, 21f, 16f, 1.5f); moveTo(12f, 16f); lineTo(12f, 20f); moveTo(8f, 20f); lineTo(16f, 20f) }
    /** 齿轮：圆 + 八根齿。 */
    val Gear = icon("gear") {
        circle(12f, 12f, 3f)
        circle(12f, 12f, 6.5f)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            moveTo(12f + 6.5f * kotlin.math.cos(a).toFloat(), 12f + 6.5f * kotlin.math.sin(a).toFloat())
            lineTo(12f + 9f * kotlin.math.cos(a).toFloat(), 12f + 9f * kotlin.math.sin(a).toFloat())
        }
    }
    val Search = icon("search") { circle(10.5f, 10.5f, 6f); moveTo(15f, 15f); lineTo(20f, 20f) }
    val Globe = icon("globe") {
        circle(12f, 12f, 8.5f)
        moveTo(3.5f, 12f); lineTo(20.5f, 12f)
        moveTo(12f, 3.5f); arcTo(5f, 8.5f, 0f, false, false, 12f, 20.5f)
        moveTo(12f, 3.5f); arcTo(5f, 8.5f, 0f, false, true, 12f, 20.5f)
    }
    val BrowserWindow = icon("browser_window") { roundRect(3f, 4.5f, 21f, 19.5f, 1.5f); moveTo(3f, 8.5f); lineTo(21f, 8.5f) }
    val Archive = icon("archive") {
        roundRect(3f, 4f, 21f, 8.5f, 1f)
        moveTo(4.5f, 8.5f); lineTo(4.5f, 20f); lineTo(19.5f, 20f); lineTo(19.5f, 8.5f)
        moveTo(9.5f, 12.5f); lineTo(14.5f, 12.5f)
    }
    val ArchiveTimer = icon("archive_timer") {
        roundRect(3f, 4f, 21f, 8.5f, 1f)
        moveTo(4.5f, 8.5f); lineTo(4.5f, 20f); lineTo(19.5f, 20f); lineTo(19.5f, 8.5f)
        moveTo(12f, 11f); lineTo(12f, 17f); moveTo(9.5f, 14.5f); lineTo(12f, 17f); lineTo(14.5f, 14.5f)
    }
    /** 橡皮擦：倾斜的平行四边形 + 底线。 */
    val Eraser = icon("eraser") {
        moveTo(4f, 15f); lineTo(13f, 6f); lineTo(19f, 12f); lineTo(12f, 19f); lineTo(8f, 19f); close()
        moveTo(8.5f, 10.5f); lineTo(14.5f, 16.5f)
        moveTo(14f, 20f); lineTo(20f, 20f)
    }
    val Folder = icon("folder") { moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 8.5f); lineTo(21f, 8.5f); lineTo(21f, 19f); lineTo(3f, 19f); close() }
    val Keyboard = icon("keyboard") {
        roundRect(2.5f, 6f, 21.5f, 18f, 1.5f)
        for (x in listOf(6f, 9.5f, 13f, 16.5f)) { moveTo(x, 9.5f); lineTo(x + 0.5f, 9.5f); moveTo(x, 12.5f); lineTo(x + 0.5f, 12.5f) }
        moveTo(7.5f, 15.2f); lineTo(16.5f, 15.2f)
    }
    /** 时钟（第一版的圆弧方向画反了，对照时发现后改为完整圆）。 */
    val History = icon("history") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }
    val Shield = icon("shield") { moveTo(12f, 3f); lineTo(19.5f, 6f); lineTo(19.5f, 11.5f); curveTo(19.5f, 16f, 16f, 19.5f, 12f, 21f); curveTo(8f, 19.5f, 4.5f, 16f, 4.5f, 11.5f); lineTo(4.5f, 6f); close() }
    /** 无痕：一副眼镜（两个圆镜片 + 鼻梁）。 */
    val Incognito = icon("incognito") {
        circle(7f, 15f, 3.2f); circle(17f, 15f, 3.2f)
        moveTo(10.2f, 15f); curveTo(11f, 14f, 13f, 14f, 13.8f, 15f)
        moveTo(3f, 10f); lineTo(21f, 10f); moveTo(6f, 10f); lineTo(8f, 4.5f); lineTo(16f, 4.5f); lineTo(18f, 10f)
    }
    val Download = icon("download") { moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f); moveTo(5f, 19.5f); lineTo(19f, 19.5f) }
    val Update = icon("update") {
        moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 16.9f, 7f)
        moveTo(17.5f, 3.5f); lineTo(17.5f, 7.5f); lineTo(13.5f, 7.5f)
        moveTo(12f, 9f); lineTo(12f, 13f); lineTo(14.5f, 14.5f)
    }
    val Info = icon("info") { circle(12f, 12f, 8.5f); moveTo(12f, 11f); lineTo(12f, 16.5f); moveTo(12f, 7.6f); lineTo(12f, 7.8f) }
    val Lock = icon("lock") { roundRect(5f, 10.5f, 19f, 20f, 2f); moveTo(8f, 10.5f); lineTo(8f, 7.5f); arcTo(4f, 4f, 0f, false, true, 16f, 7.5f); lineTo(16f, 10.5f) }
    /** 外观：半明半暗的圆（跟随系统）。 */
    val ThemeAuto = icon("theme_auto") {
        circle(12f, 12f, 8f)
        moveTo(12f, 4f); lineTo(12f, 20f)
        moveTo(12f, 7f); lineTo(16f, 7f); moveTo(12f, 10f); lineTo(19f, 10f); moveTo(12f, 13f); lineTo(19.5f, 13f); moveTo(12f, 16f); lineTo(17.5f, 16f)
    }
    /** 浅色：太阳。 */
    val Sun = icon("sun") {
        circle(12f, 12f, 4f)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            moveTo(12f + 6.5f * kotlin.math.cos(a).toFloat(), 12f + 6.5f * kotlin.math.sin(a).toFloat())
            lineTo(12f + 9f * kotlin.math.cos(a).toFloat(), 12f + 9f * kotlin.math.sin(a).toFloat())
        }
    }
    /** 深色：月牙。 */
    val Moon = icon("moon") {
        moveTo(19.5f, 14.5f)
        arcTo(8f, 8f, 0f, true, true, 9.5f, 4.5f)
        arcTo(6.5f, 6.5f, 0f, false, false, 19.5f, 14.5f)
        close()
    }
}
