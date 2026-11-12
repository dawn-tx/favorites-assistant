package com.favkit.tablet.floating

import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.addTextChangedListener

/**
 * 手写笔友好的备注输入区。
 *
 * 刻意使用 AndroidView + 真实 EditText 而不是 Compose 文本框：Android 14+ 对可编辑文本框
 * 默认开启手写笔直写（笔落在输入区即书写，不需要切换输入法），更早的平板用手写输入法同样
 * 能直接写；不依赖任何实验性 Compose 手写扩展，也没有需要额外申请的权限。
 */
@Composable
fun StylusNoteField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 4,
) {
    val latestValue by rememberUpdatedState(value)
    val latestEmit by rememberUpdatedState(onValueChange)
    val textColor = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (minLines >= 4) 132.dp else 76.dp),
        factory = { context ->
            val density = context.resources.displayMetrics.density
            val pad = (14f * density).toInt()
            EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                    EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                gravity = Gravity.TOP or Gravity.START
                setLines(minLines)
                maxLines = minLines + 3
                textSize = 17f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextColor(textColor.toArgb())
                setHintTextColor(hintColor.copy(alpha = 0.7f).toArgb())
                highlightColor = accent.copy(alpha = 0.35f).toArgb()
                hint = "备注：手写笔可直接在此书写"
                setPadding(pad, pad, pad, pad)
                setHorizontallyScrolling(false)
                addTextChangedListener { editable ->
                    val text = editable?.toString().orEmpty()
                    if (text != latestValue) latestEmit(text)
                }
            }
        },
        update = { editText ->
            // 仅在外部草稿与当前文本不一致时改写，保留手写光标位置
            if (editText.text.toString() != value) editText.setTextKeepState(value)
        },
    )
}
