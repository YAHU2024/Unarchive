package com.unarchive.editorprobe

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView

/** Test-only IME. It sends the real Android InputConnection composition protocol.
 * It does not emulate a vendor's pinyin engine, candidate bar, or keyboard UI. */
class ProbeImeService : InputMethodService() {
    @Volatile var servedPackage: String? = null
        private set
    val connection: InputConnection? get() = currentInputConnection

    override fun onCreate() {
        super.onCreate()
        active = this
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        servedPackage = attribute?.packageName
    }

    override fun onCreateInputView(): View = TextView(this).apply {
        text = "中文组合输入协议探针\n仅发送测试文本，不读取私人内容"
        textSize = 18f
        setPadding(24, 24, 24, 24)
        minHeight = (resources.displayMetrics.density * 180).toInt()
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile var active: ProbeImeService? = null
            private set
    }
}
