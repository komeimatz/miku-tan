package com.tealtranquility.mikutan

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 設定画面。メイン画面の下部に散らばっていた導線をここに集約した。
 *
 * 項目:
 *  - 保存フォルダ（変更する / 既定に戻す）
 *  - Ankiカード作成プロンプト（変更する / 既定(英語) / 既定(中国語)）
 *  - 使い方をもう一度見る
 *  - 全設定をリセット
 */
class SettingsActivity : Activity() {

    private val dp: Float get() = resources.displayMetrics.density

    private lateinit var folderNow: TextView
    private lateinit var folderReset: Button
    private lateinit var promptNow: TextView
    private lateinit var artNow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // プロンプト編集から戻ってきた時に表示を合わせる
    }

    // ---------------------------------------------------------------- UI

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

        // --- 見出し行（戻る＋タイトル） ---
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
            text = "設定"
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

        // --- 本体 ---
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, (24 * dp).toInt())
        }

        // 保存フォルダ
        box.addView(section("保存フォルダ"))
        folderNow = now()
        box.addView(folderNow)
        box.addView(action("変更する") { pickFolder() })
        folderReset = action("既定（${WordStore.DEFAULT_PATH}）に戻す") { resetFolder() }
        box.addView(folderReset)

        // プロンプト
        box.addView(section("Ankiカード作成プロンプト"))
        promptNow = now()
        box.addView(promptNow)
        box.addView(buildPromptChooser())

        // その他
        // ミクさんの絵。プロンプトとは独立（英語カードでも「おっけー」でいい）。
        box.addView(section("追加したときのミクさん"))
        artNow = now()
        box.addView(artNow)
        listOf(MikuArt.DONE, MikuArt.GAODING, MikuArt.OKKE).forEach { v ->
            box.addView(action(MikuArt.label(v)) {
                MikuArt.set(this, v)
                refresh()
            })
        }

        box.addView(section("その他"))
        box.addView(action("使い方をもう一度見る") {
            startActivity(Intent(this, GuideActivity::class.java))
        })
        box.addView(action("全設定をリセット") { resetAll() })

        // 連絡先。バージョン表示のすぐ上に置いて、報告のときに一緒に目に入るようにする。
        // リンクを開くのはブラウザ（または X アプリ）なので、このアプリ自体は
        // ネット権限を持たないまま。
        box.addView(TextView(this).apply {
            text = "プロンプトに関するアイディアやバグ報告はこちらへ"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.lw_text_sub))
            setPadding(0, (32 * dp).toInt(), 0, (4 * dp).toInt())
        })
        box.addView(TextView(this).apply {
            text = "@$CONTACT_HANDLE"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.lw_button))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener { openLink(CONTACT_URL) }
        })

        // 不具合報告のときに「どのバージョン？」を聞かなくて済むように出しておく
        box.addView(TextView(this).apply {
            text = versionLabel()
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.lw_text_sub))
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })

        // PCL クレジット。PCL 第 3 条第 3 項と、クリプトンのキャラクター利用ガイドラインに沿って、
        // ①PCL による許諾 ②PCL の URL ③キャラクター名 ④社名 を出す。
        // 名前とアイコンにミクさんを使っているので、公式アプリと誤解されないよう「非公式」も明記する
        // （ガイドラインは「公式製品のような誤解を招く利用」を禁じている）。
        // APK は単体でも人の手に渡るので、README だけでなくアプリ自体に載せておく。
        box.addView(TextView(this).apply {
            val license = "ピアプロ・キャラクター・ライセンス"
            val body = "このアプリは非公式のファン作品です。\n" +
                "イラストは、${license}に基づいて、クリプトン・フューチャー・メディア株式会社の" +
                "キャラクター「初音ミク」を描いたものです。"
            val start = body.indexOf(license)
            text = SpannableString(body).apply {
                setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = openLink(PCL_URL)
                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = getColor(R.color.lw_button)
                    }
                }, start, start + license.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            movementMethod = LinkMovementMethod.getInstance()
            textSize = 11f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setTextColor(getColor(R.color.lw_text_sub))
            setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
        })

        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(box)
        })

        return root
    }

    /** 区切りの小見出し。 */
    private fun section(label: String): View = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(getColor(R.color.lw_accent))
        setPadding(0, (24 * dp).toInt(), 0, (2 * dp).toInt())
    }

    /** 「現在: 〜」の行。 */
    private fun now(): TextView = TextView(this).apply {
        textSize = 13f
        setTextColor(getColor(R.color.lw_text_sub))
        setPadding(0, 0, 0, (10 * dp).toInt())
    }

    /**
     * プロンプトの選び方を「土台を選ぶ → そこから手を入れる」の流れで見せる。
     *
     *   [既定（英語）にする]     ┌──────────┐
     *   [既定（中国語）にする] → │ 既定値から │
     *   [既定（専門用語）にする]  │カスタマイズ│
     *                           └──────────┘
     * 右のボタンは今選ばれている文面を編集画面で開く。
     */
    private fun buildPromptChooser(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f)
        }
        fun preset(label: String, done: String, apply: () -> Unit) =
            action(label) {
                apply()
                refresh()
                toast(done)
            }.apply { textSize = 13f }
        presets.addView(preset("既定（英語）にする", "英語の既定プロンプトにしました") {
            Prompt.resetToEnglish(this)
        })
        presets.addView(preset("既定（中国語）にする", "中国語の既定プロンプトにしました") {
            Prompt.resetToChinese(this)
        })
        presets.addView(preset("既定（専門用語）にする", "専門用語の既定プロンプトにしました") {
            Prompt.resetToTerms(this)
        }.apply {
            // 最後のボタンの下余白は不要（右のボタンと下端を揃えるため）
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        })
        row.addView(presets)

        row.addView(TextView(this).apply {
            text = "→"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.lw_button))
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        })

        // 右: 左 3 つを合わせた高さいっぱいの縦長ボタン
        row.addView(action("既定値から\nカスタマイズする") {
            startActivity(Intent(this, PromptEditActivity::class.java))
        }.apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })

        // 行全体の下余白（他のボタン列と揃える）
        row.setPadding(0, 0, 0, (8 * dp).toInt())
        return row
    }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(getColor(R.color.lw_button))
        setBackgroundResource(R.drawable.btn_lw_outline)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()
        ).apply { bottomMargin = (8 * dp).toInt() }
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        folderNow.text = "現在: ${WordStore.folderName(this)}"
        // 既定に戻す導線は、SAF で選んでいる時だけ意味がある
        folderReset.visibility =
            if (WordStore.treeUri(this) == null) View.GONE else View.VISIBLE
        promptNow.text = "現在: ${Prompt.label(this)}"
        artNow.text = "現在: ${MikuArt.label(MikuArt.get(this))}"
    }

    // ------------------------------------------------------------ 保存先

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(i, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("tree", uri.toString())
                .apply()
            refresh()
        }
    }

    /** SAF の指定を捨てて既定に戻す。選んでいたフォルダのファイル自体は消さない。 */
    private fun resetFolder() {
        releaseTree()
        getSharedPreferences("cfg", MODE_PRIVATE).edit().remove("tree").apply()
        refresh()
        toast("既定の保存先に戻しました")
    }

    private fun releaseTree() {
        WordStore.treeUri(this)?.let { uri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    // ---------------------------------------------------------- リセット

    private fun resetAll() {
        AlertDialog.Builder(this)
            .setTitle("全設定をリセット")
            .setMessage(
                "保存フォルダの指定・プロンプト・使い方ガイドの表示履歴を、" +
                    "インストール直後の状態に戻します。\n\n" +
                    "単語のデータ（words.txt / archive.txt）は消えません。"
            )
            .setNegativeButton("やめる", null)
            .setPositiveButton("リセット") { _, _ ->
                releaseTree()
                getSharedPreferences("cfg", MODE_PRIVATE).edit().clear().apply()
                refresh()
                toast("設定をリセットしました")
            }
            .show()
    }

    /** 「ミク単  v0.1 (1)」のような表示。 */
    /** リンクはブラウザ（または X アプリ）に開かせる。このアプリはネット権限を持たない。 */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("リンクを開けるアプリがありません（$url）")
        }
    }

    private fun versionLabel(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${getString(R.string.app_name)}  v${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("")

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_TREE = 1

        /** アイディア・バグ報告の窓口（X）。 */
        private const val CONTACT_HANDLE = "MikuTealSerene"
        private const val CONTACT_URL = "https://x.com/$CONTACT_HANDLE"
        private const val PCL_URL = "https://piapro.jp/license/pcl/summary"
    }
}
