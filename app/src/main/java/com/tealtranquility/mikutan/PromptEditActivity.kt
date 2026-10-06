package com.tealtranquility.mikutan

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * プロンプトを直接編集する画面。
 *
 * 末尾は `# 単語リスト` で終わらせる約束（MainActivity がその後ろに単語を足す）。
 * 空のまま保存すると中国語の既定に戻る。
 */
class PromptEditActivity : Activity() {

    private val dp: Float get() = resources.displayMetrics.density
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        input.setText(Prompt.current(this))
    }

    private fun buildUi(): View {
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.lw_bg))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, (14 * dp).toInt(), pad, (14 * dp).toInt())
        }
        bar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(getColor(R.color.lw_button))
            setPadding(0, 0, (16 * dp).toInt(), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "プロンプトを編集"
            textSize = 22f
            setTextColor(getColor(R.color.lw_button))
        })
        root.addView(bar)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (1.5f * dp).toInt()
            )
            setBackgroundColor(getColor(R.color.lw_accent))
        })

        root.addView(TextView(this).apply {
            text = "この文章の後ろに単語リストが自動で付きます。\n" +
                "末尾は「# 単語リスト」で終わらせてください。"
            textSize = 12f
            setTextColor(getColor(R.color.lw_text_sub))
            setPadding(pad, (10 * dp).toInt(), pad, (8 * dp).toInt())
        })

        input = EditText(this).apply {
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setTextColor(getColor(R.color.lw_text))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(pad, 0, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(input)

        val btnBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, (12 * dp).toInt())
        }
        btnBox.addView(Button(this).apply {
            text = "保存する"
            isAllCaps = false
            textSize = 15f
            setTextColor(getColor(R.color.lw_button_text))
            setBackgroundResource(R.drawable.btn_lw)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setOnClickListener { save() }
        })
        root.addView(btnBox)

        return root
    }

    private fun save() {
        val body = input.text.toString().trim()
        if (body.isEmpty()) {
            Prompt.resetToChinese(this)
            Toast.makeText(this, "空だったので既定（中国語）に戻しました", Toast.LENGTH_SHORT).show()
        } else {
            Prompt.save(this, body)
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
