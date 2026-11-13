package io.yiner.browser.browser

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.util.Calendar
import java.util.Locale

/**
 * 网页对话框：alert/confirm/prompt、<select>、日期时间、颜色、HTTP 认证、
 * 文件上传、弹窗拦截、离开页面确认、navigator.share。
 * 没有可用界面时一律 dismiss，网页不会卡住。
 */
class PromptHandler(private val hostProvider: () -> UiHost?) : PromptDelegate {

    private fun <T : PromptDelegate.BasePrompt> show(
        prompt: T,
        build: (UiHost, GeckoResult<PromptResponse>) -> AlertDialog.Builder?,
    ): GeckoResult<PromptResponse> {
        val host = hostProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        val builder = build(host, result) ?: return GeckoResult.fromValue(prompt.dismiss())
        builder.setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
        builder.show()
        return result
    }

    private fun dialog(host: UiHost, title: String?) =
        AlertDialog.Builder(host.activity).apply { if (!title.isNullOrBlank()) setTitle(title) }

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt) =
        show(prompt) { host, r ->
            dialog(host, prompt.title).setMessage(prompt.message)
                .setPositiveButton("确定") { _, _ -> r.complete(prompt.dismiss()) }
        }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt) =
        show(prompt) { host, r ->
            dialog(host, prompt.title).setMessage(prompt.message)
                .setPositiveButton("确定") { _, _ -> r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton("取消") { _, _ -> r.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
        }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt) =
        show(prompt) { host, r ->
            val input = EditText(host.activity).apply { setText(prompt.defaultValue.orEmpty()); setSingleLine() }
            dialog(host, prompt.title).setMessage(prompt.message).setView(padded(host, input))
                .setPositiveButton("确定") { _, _ -> r.complete(prompt.confirm(input.text.toString())) }
                .setNegativeButton("取消") { _, _ -> r.complete(prompt.dismiss()) }
        }

    override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt) =
        show(prompt) { host, r ->
            val onlyPassword = prompt.authOptions.flags and
                PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
            val user = EditText(host.activity).apply {
                hint = "用户名"; setSingleLine(); setText(prompt.authOptions.username.orEmpty())
            }
            val pass = EditText(host.activity).apply {
                hint = "密码"; setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val box = LinearLayout(host.activity).apply {
                orientation = LinearLayout.VERTICAL
                if (!onlyPassword) addView(user)
                addView(pass)
            }
            dialog(host, prompt.title ?: "需要登录").setMessage(prompt.message).setView(padded(host, box))
                .setPositiveButton("登录") { _, _ ->
                    r.complete(
                        if (onlyPassword) prompt.confirm(pass.text.toString())
                        else prompt.confirm(user.text.toString(), pass.text.toString()),
                    )
                }
                .setNegativeButton("取消") { _, _ -> r.complete(prompt.dismiss()) }
        }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt) =
        show(prompt) { host, r ->
            val flat = flatten(prompt.choices)
            val labels = flat.map { (c, depth) -> "  ".repeat(depth) + c.label }.toTypedArray()
            val b = dialog(host, prompt.title ?: prompt.message)
            if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flat.size) { flat[it].first.selected }
                b.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("确定") { _, _ ->
                        val ids = flat.indices.filter { checked[it] }.map { flat[it].first.id }.toTypedArray()
                        r.complete(prompt.confirm(ids))
                    }
                    .setNegativeButton("取消") { _, _ -> r.complete(prompt.dismiss()) }
            } else {
                val selected = flat.indexOfFirst { it.first.selected }
                b.setSingleChoiceItems(labels, selected) { d, which ->
                    r.complete(prompt.confirm(flat[which].first))
                    d.dismiss()
                }
            }
            b
        }

    /** 展开 <optgroup>，去掉分隔线和禁用项。 */
    private fun flatten(
        choices: Array<PromptDelegate.ChoicePrompt.Choice>,
        depth: Int = 0,
    ): List<Pair<PromptDelegate.ChoicePrompt.Choice, Int>> = choices.flatMap { c ->
        when {
            c.separator -> emptyList()
            c.items != null -> flatten(c.items!!, depth + 1)
            c.disabled -> emptyList()
            else -> listOf(c to depth)
        }
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
        val host = hostProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        val cal = Calendar.getInstance()
        val def = prompt.defaultValue.orEmpty()
        parseDate(def)?.let { (y, m, d) -> cal.set(y, m - 1, d) }
        parseTime(def.substringAfter('T', def))?.let { (h, min) -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min) }

        fun pickTime(prefix: String) {
            TimePickerDialog(host.activity, { _, h, min ->
                result.complete(prompt.confirm(prefix + String.format(Locale.ROOT, "%02d:%02d", h, min)))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).apply {
                setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
            }.show()
        }

        fun pickDate(onPicked: (Int, Int, Int) -> Unit) {
            DatePickerDialog(host.activity, { _, y, m, d -> onPicked(y, m + 1, d) },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                parseDate(prompt.minValue.orEmpty())?.let { (y, m, d) -> datePicker.minDate = millis(y, m, d) }
                parseDate(prompt.maxValue.orEmpty())?.let { (y, m, d) -> datePicker.maxDate = millis(y, m, d) }
                setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
            }.show()
        }

        when (prompt.type) {
            PromptDelegate.DateTimePrompt.Type.DATE -> pickDate { y, m, d ->
                result.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d)))
            }
            PromptDelegate.DateTimePrompt.Type.MONTH -> pickDate { y, m, _ ->
                result.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-%02d", y, m)))
            }
            PromptDelegate.DateTimePrompt.Type.TIME -> pickTime("")
            PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> pickDate { y, m, d ->
                pickTime(String.format(Locale.ROOT, "%04d-%02d-%02dT", y, m, d))
            }
            else -> {
                // WEEK 等少见类型：文本输入，格式由网页校验
                val input = EditText(host.activity).apply { setText(def); hint = "例如 2026-W46"; setSingleLine() }
                AlertDialog.Builder(host.activity).setTitle(prompt.title ?: "输入").setView(padded(host, input))
                    .setPositiveButton("确定") { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                    .setNegativeButton("取消") { _, _ -> result.complete(prompt.dismiss()) }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
            }
        }
        return result
    }

    override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt) =
        show(prompt) { host, r ->
            val input = EditText(host.activity).apply {
                setText(prompt.defaultValue ?: "#000000"); hint = "#RRGGBB"; setSingleLine()
            }
            dialog(host, prompt.title ?: "选择颜色").setView(padded(host, input))
                .setPositiveButton("确定") { _, _ ->
                    val v = input.text.toString().trim()
                    r.complete(if (Regex("^#[0-9a-fA-F]{6}$").matches(v)) prompt.confirm(v.lowercase()) else prompt.dismiss())
                }
                .setNegativeButton("取消") { _, _ -> r.complete(prompt.dismiss()) }
        }

    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt) =
        show(prompt) { host, r ->
            dialog(host, "已拦截弹出窗口").setMessage("此网站想打开一个新窗口：\n${prompt.targetUri.orEmpty()}")
                .setPositiveButton("允许") { _, _ -> r.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton("拦截") { _, _ -> r.complete(prompt.confirm(AllowOrDeny.DENY)) }
        }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt) =
        show(prompt) { host, r ->
            dialog(host, "离开此页面？").setMessage("你所做的更改可能不会保存。")
                .setPositiveButton("离开") { _, _ -> r.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton("留下") { _, _ -> r.complete(prompt.confirm(AllowOrDeny.DENY)) }
        }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt) =
        show(prompt) { host, r ->
            dialog(host, "重新提交表单？").setMessage("刷新此页面会再次发送之前提交的数据。")
                .setPositiveButton("重新发送") { _, _ -> r.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton("取消") { _, _ -> r.complete(prompt.confirm(AllowOrDeny.DENY)) }
        }

    override fun onSharePrompt(session: GeckoSession, prompt: PromptDelegate.SharePrompt): GeckoResult<PromptResponse> {
        val host = hostProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val text = listOfNotNull(prompt.title, prompt.text, prompt.uri).filter { it.isNotBlank() }.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return runCatching {
            host.activity.startActivity(Intent.createChooser(send, "分享"))
            GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.SUCCESS))
        }.getOrElse { GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.FAILURE)) }
    }

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
        val host = hostProvider() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        val mimes = prompt.mimeTypes?.filter { it.contains('/') }?.toTypedArray()?.takeIf { it.isNotEmpty() }
            ?: arrayOf("*/*")
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        val wantsImage = mimes.any { it.startsWith("image/") }

        fun fromFiles() = host.pickFiles(mimes, multiple) { uris ->
            result.complete(
                when {
                    uris.isEmpty() -> prompt.dismiss()
                    multiple -> prompt.confirm(host.activity, uris.toTypedArray())
                    else -> prompt.confirm(host.activity, uris.first())
                },
            )
        }

        fun fromCamera() = host.capturePhoto { uri ->
            result.complete(if (uri == null) prompt.dismiss() else prompt.confirm(host.activity, uri))
        }

        when {
            wantsImage && prompt.capture != PromptDelegate.FilePrompt.Capture.NONE -> fromCamera()
            wantsImage -> AlertDialog.Builder(host.activity).setTitle("上传图片")
                .setItems(arrayOf("拍照", "从文件中选择")) { _, which -> if (which == 0) fromCamera() else fromFiles() }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            else -> fromFiles()
        }
        return result
    }

    private fun padded(host: UiHost, view: android.view.View): android.view.View {
        val pad = (20 * host.activity.resources.displayMetrics.density).toInt()
        return LinearLayout(host.activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun parseDate(s: String): Triple<Int, Int, Int>? {
        val m = Regex("^(\\d{4})-(\\d{2})(?:-(\\d{2}))?").find(s) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "1" }.toInt())
    }

    private fun parseTime(s: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{2}):(\\d{2})").find(s) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun millis(y: Int, m: Int, d: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d) }.timeInMillis
}
