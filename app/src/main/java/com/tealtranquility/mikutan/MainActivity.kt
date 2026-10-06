package com.tealtranquility.mikutan

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.animation.AccelerateInterpolator
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * メイン画面。溜まった単語を眺めて、覚えていた語を消し、
 * 残りを LLM に渡すためにコピーする。
 *
 * 配色はシステムのダークモード設定に自動追従（values/ と values-night/ で切替）。
 */
class MainActivity : Activity() {

    private val words = mutableListOf<String>()
    private lateinit var adapter: WordAdapter
    private lateinit var listView: ListView
    private lateinit var header: TextView
    private lateinit var empty: TextView
    private lateinit var swipeHint: TextView
    private lateinit var restoreLink: TextView

    // 削除の取り消し用（直前の 1 件を覚えておく）
    private var lastRemoved: String? = null
    private var lastRemovedIndex: Int = -1

    // スワイプで開いている行は常に 1 つだけ。別の行を触ったら前のを閉じる。
    private var openRow: View? = null
    private var openRowCloser: (() -> Unit)? = null

    private val dp: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        reload()
        // 初回だけ使い方ガイドを重ねて出す（2 回目以降は出ない）
        if (GuideActivity.shouldShow(this)) {
            startActivity(Intent(this, GuideActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reload() // キャプチャで増えた分を戻ってきた時に反映
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.lw_bg))
        }

        // ActionBar を消したので、ステータスバー／ナビゲーションバーの下に
        // 潜り込まないよう、その分の余白を自分で入れる。
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
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

        // --- リスト本体（余った領域を全部使う） ---
        adapter = WordAdapter()
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
        }
        // タイトルと件数はリストの「先頭行」として差し込む。
        // addHeaderView はアダプタの管理外なので、コピー対象にもスワイプ対象にも
        // ならず、リストと一緒にスクロールする。
        listView.addHeaderView(buildListHeader(pad), null, false)
        // 手動追加の入口はリストの末尾に。ヘッダと同じくアダプタ管理外なので
        // コピー対象にもスワイプ対象にもならない。
        listView.addFooterView(buildAddFooter(pad), null, false)
        listView.adapter = adapter
        root.addView(listView)

        // --- リストとボタン群の境界線（#E12885 の細線） ---
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (1.5f * dp).toInt()
            )
            setBackgroundColor(color(R.color.lw_accent))
        })

        // --- ボタン群 ---
        val btnBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * dp).toInt(), pad, (12 * dp).toInt())
        }

        btnBox.addView(
            makeButton("Ankiファイル作成をAIに依頼", primary = true) { copyWithPrompt() }
        )
        btnBox.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (8 * dp).toInt()
            )
        })
        btnBox.addView(
            makeButton("AIプロンプトなしでコピー", primary = false) { copyWordsOnly() }
        )
        // 保存フォルダ・プロンプト・ガイドの導線は設定画面（ヘッダ右上）へ移した。

        root.addView(btnBox)
        return root
    }

    /**
     * リストの先頭に差し込むヘッダ。アプリ名・件数・（空の時だけ）使い方の一言。
     * リストと一緒にスクロールして流れていく。
     */
    private fun buildListHeader(pad: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (18 * dp).toInt(), pad, (10 * dp).toInt())
        }

        // タイトル行。右端に設定への入り口を置く。
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTextColor(color(R.color.lw_button))
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "設定"
            textSize = 14f
            setTextColor(color(R.color.lw_button))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        })
        box.addView(titleRow)

        header = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.lw_text_sub))
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        box.addView(header)

        // 右スワイプ削除は見ただけでは分からない操作なので、ここで一度だけ教える。
        // 単語がある時だけ出し、一度でも削除したら二度と出さない（reloadHeaderOnly 参照）。
        swipeHint = TextView(this).apply {
            text = "右スワイプで削除できます"
            textSize = 12f
            setTextColor(color(R.color.lw_accent))
            setPadding(0, (6 * dp).toInt(), 0, 0)
            visibility = View.GONE
        }
        box.addView(swipeHint)

        empty = TextView(this).apply {
            text = "まだ単語がありません。\n\nWeblio などで単語を長押しして\n「ミク単」を選ぶと、ここに溜まります。"
            textSize = 14f
            setTextColor(color(R.color.lw_text_sub))
            setPadding(0, (28 * dp).toInt(), 0, 0)
            visibility = View.GONE
        }
        box.addView(empty)

        // AI に送った直後（リストが空）に、控えから戻すための導線
        restoreLink = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.lw_button))
            setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = View.GONE
            setOnClickListener { restoreLastSent() }
        }
        box.addView(restoreLink)

        return box
    }

    /** リスト末尾の「＋ 手動で追加」。 */
    private fun buildAddFooter(pad: Int): View = TextView(this).apply {
        text = "＋ 手動で追加"
        textSize = 15f
        setTextColor(color(R.color.lw_button))
        setPadding(pad, (16 * dp).toInt(), pad, (16 * dp).toInt())
        setOnClickListener { showManualAdd() }
    }

    /**
     * 入力欄 1 つだけの小窓。打ち込んでも、長押しで貼り付けてもいい。
     * 整形ルールは長押しで拾ったとき（CaptureActivity）と揃える:
     * 前後の空白を削り、改行は空白に、MAX_LEN を超えたら弾く。
     */
    private fun showManualAdd() {
        val input = EditText(this).apply {
            hint = "単語を入力、または長押しで貼り付け"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val frame = FrameLayout(this).apply {
            val p = (20 * dp).toInt()
            setPadding(p, (8 * dp).toInt(), p, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("手動で追加")
            .setView(frame)
            .setNegativeButton("やめる", null)
            .setPositiveButton("追加", null) // 弾いた時に閉じないよう、押下処理は後で差し替える
            .create()

        fun submit() {
            val word = input.text.toString().trim().replace("\n", " ").replace("\r", " ")
            when {
                word.isEmpty() -> dialog.dismiss()
                word.length > CaptureActivity.MAX_LEN ->
                    toast("長すぎます（${word.length}文字）。単語だけにしてください")
                else -> {
                    WordStore.append(this, WordStore.FILE_NAME, word + "\n")
                    dialog.dismiss()
                    reload()
                }
            }
        }

        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
        }
        // 開いた瞬間にキーボードを出す
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun makeButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) color(R.color.lw_button_text) else color(R.color.lw_button))
            setBackgroundResource(
                if (primary) R.drawable.btn_lw else R.drawable.btn_lw_outline
            )
            stateListAnimator = null // 角丸・影を消して長方形を保つ
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            )
            setOnClickListener { onClick() }
        }

    private fun makeTextLink(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.lw_text_sub))
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            setOnClickListener { onClick() }
        }

    private fun color(id: Int): Int =
        if (android.os.Build.VERSION.SDK_INT >= 23) getColor(id)
        else @Suppress("DEPRECATION") resources.getColor(id)

    // ------------------------------------------------------------- データ

    private fun reload() {
        forgetOpenRow()
        words.clear()
        words.addAll(WordStore.loadWords(this))
        adapter.notifyDataSetChanged()
        reloadHeaderOnly()
    }

    /** 開いている行の記憶を捨てる（行の View を作り直す前に呼ぶ）。 */
    private fun forgetOpenRow() {
        openRow = null
        openRowCloser = null
    }

    /** 覚えていた語を消す。archive.txt には残すので散逸しない。 */
    private fun removeAt(index: Int) {
        if (index !in words.indices) return
        val word = words.removeAt(index)
        lastRemoved = word
        lastRemovedIndex = index
        forgetOpenRow()
        adapter.notifyDataSetChanged()
        persist()
        WordStore.archive(this, word)
        // 削除できると分かったので、ヒントはもう出さない
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean(KEY_SWIPE_LEARNED, true).apply()
        showUndoBar(word)
    }

    private fun undoRemove() {
        val word = lastRemoved ?: return
        val at = lastRemovedIndex.coerceIn(0, words.size)
        words.add(at, word)
        lastRemoved = null
        lastRemovedIndex = -1
        adapter.notifyDataSetChanged()
        persist()
        reloadHeaderOnly()
    }

    private fun persist() {
        runCatching { WordStore.saveWords(this, words) }
            .onFailure { toast("保存に失敗: ${it.message}") }
        reloadHeaderOnly()
    }

    private fun reloadHeaderOnly() {
        // 一覧で知りたいのは件数だけ。保存先の場所は設定画面に出す。
        // ただし「選んだフォルダが使えず既定に逃がしている」時だけは、
        // 気づかないと困るのでここでも知らせる。
        val count = if (words.isEmpty()) null else "${words.size} 語が登録されています"
        val broken = if (WordStore.isTreeBroken(this))
            "⚠ 設定した保存フォルダが使えないため、${WordStore.DEFAULT_PATH} に保存しています"
        else null
        header.text = listOfNotNull(count, broken).joinToString("\n")
        header.visibility = if (count == null && broken == null) View.GONE else View.VISIBLE
        // リスト自体は常に表示する（ヘッダがリストの中にあるため隠すと
        // タイトルまで消えてしまう）。空の案内はヘッダの下に出す。
        empty.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
        val learned = getSharedPreferences("cfg", MODE_PRIVATE)
            .getBoolean(KEY_SWIPE_LEARNED, false)
        swipeHint.visibility =
            if (words.isNotEmpty() && !learned) View.VISIBLE else View.GONE

        // 送った直後でリストが空の間だけ「戻す」を出す
        val sent = if (words.isEmpty()) lastSent().size else 0
        restoreLink.text = "↩ 前回 AI に送った $sent 語を戻す"
        restoreLink.visibility = if (sent > 0) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------- コピー

    private fun copyWithPrompt() {
        if (words.isEmpty()) { toast("単語がありません"); return }
        // 設定で差し替えられている可能性があるので、押すたびに読み直す
        copy(Prompt.current(this) + "\n" + words.joinToString("\n") + "\n")
        val n = sendOff()
        // トーストは 2 行までしか出ないので収める
        toast("${n} 語をプロンプト付きでコピーしました\nAI に貼り付けてください（チャットはピン留め推奨）")
    }

    private fun copyWordsOnly() {
        if (words.isEmpty()) { toast("単語がありません"); return }
        copy(words.joinToString("\n") + "\n")
        val n = sendOff()
        toast("${n} 語をコピーしました\nAI のチャットに貼り付けてください")
    }

    /**
     * AI に送った単語をリストから外す。次に送る時に同じ語で
     * またカードが作られないように。AI 側で失敗した時のために
     * last_sent.txt に 1 回分だけ控えを残す（リストが空の間「戻す」が出る）。
     */
    private fun sendOff(): Int {
        val n = words.size
        runCatching {
            WordStore.writeText(this, WordStore.LAST_SENT_NAME, words.joinToString("\n") + "\n")
            WordStore.saveWords(this, emptyList())
        }.onFailure { toast("リストを空にできませんでした: ${it.message}") }
        reload()
        return n
    }

    /** 控えから戻す。今リストにある語（送った後に拾った語）はその後ろに残す。 */
    private fun restoreLastSent() {
        val sent = lastSent()
        if (sent.isEmpty()) return
        val merged = sent + words
        runCatching {
            WordStore.saveWords(this, merged)
            WordStore.writeText(this, WordStore.LAST_SENT_NAME, "")
        }.onFailure { toast("戻せませんでした: ${it.message}") }
        reload()
        toast("${sent.size} 語を戻しました")
    }

    private fun lastSent(): List<String> =
        WordStore.readText(this, WordStore.LAST_SENT_NAME)
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ミク単", text))
    }

    // 保存フォルダの選択・リセットは SettingsActivity に移動した。
    // 戻ってきた時は onResume の reload() で表示が追従する。

    // ------------------------------------------------------- Undo バー

    /** 削除直後に数秒だけ出る取り消しバー（片手操作のミスタップ救済）。 */
    private fun showUndoBar(word: String) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.lw_accent))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        bar.addView(TextView(this).apply {
            text = "「$word」を削除"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = "取り消す"
            setTextColor(color(R.color.lw_button))
            textSize = 14f
            setPadding((12 * dp).toInt(), 0, 0, 0)
            setOnClickListener {
                undoRemove()
                (bar.parent as? ViewGroup)?.removeView(bar)
            }
        })

        val decor = window.decorView as ViewGroup
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        decor.addView(bar, lp)
        bar.postDelayed({ (bar.parent as? ViewGroup)?.removeView(bar) }, 4000)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // -------------------------------------------------------- アダプタ

    private inner class WordAdapter : BaseAdapter() {
        override fun getCount() = words.size
        override fun getItem(position: Int) = words[position]
        override fun getItemId(position: Int) = position.toLong()

        // 行ごとにスワイプ状態を持たせたいので convertView は使い回さない。
        // 一覧はせいぜい数十語なので、作り直しのコストより状態管理の単純さを取る。
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            buildSwipeRow(position)
    }

    // ------------------------------------------------------ スワイプ行

    /**
     * 1 行 = 1 単語。**右スワイプ**すると行がマゼンタに染まり、左端にゴミ箱が出る。
     * ゴミ箱をタップで削除、左に戻すと取り消し。
     * 誤爆しにくいよう、削除は必ず「スワイプ → ゴミ箱タップ」の 2 アクションにしてある。
     *
     * 右方向だけにしているのは、左スワイプだと単語が行の左端からはみ出して
     * 見えなくなるため（前景ごと左へ動くので、左寄せの文字が切れる）。
     *
     * 構造:
     *   container(FrameLayout)
     *     ├─ bg … マゼンタ地＋左端のゴミ箱。alpha でフェードイン/アウト
     *     └─ fg … 単語。背景は透過で、translationX で右へ動く
     *
     * 調整ノブ:
     *   REVEAL_DP  … 開く幅（＝ゴミ箱の露出量）
     *   OPEN_RATIO … 指を離した時に開いたままにする閾値（開き幅に対する割合）
     *   SETTLE_MS  … 開閉アニメの長さ
     */
    private fun buildSwipeRow(position: Int): View {
        val reveal = REVEAL_DP * dp
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val textNormal = color(R.color.lw_text)

        // --- 背景: マゼンタ地とゴミ箱 ---
        val bg = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.lw_accent))
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // 前景が右へ逃げるので、露出するのは左端。ゴミ箱もそこに置く。
        val trash = ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            visibility = View.GONE
            setPadding((22 * dp).toInt(), 0, (22 * dp).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
            setOnClickListener { removeAt(position) }
        }
        bg.addView(trash)

        // --- 前景: 単語 ---
        val label = TextView(this).apply {
            text = words[position]
            textSize = 20f
            setTextColor(textNormal)
        }
        val fg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            addView(label)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = FrameLayout(this).apply {
            addView(bg)
            addView(fg)
        }

        // 開き具合(0..1)を見た目に反映する。マゼンタの濃さと文字色を連動させ、
        // 進んだ側のゴミ箱だけを出す。
        fun applyProgress(tx: Float) {
            val p = (tx / reveal).coerceIn(0f, 1f)
            bg.alpha = p
            label.setTextColor(blend(textNormal, Color.WHITE, p))
            trash.visibility = if (tx > 0f) View.VISIBLE else View.GONE
        }

        fun animateTo(target: Float) {
            val from = fg.translationX
            if (from == target) {
                applyProgress(target)
                return
            }
            ValueAnimator.ofFloat(from, target).apply {
                duration = SETTLE_MS
                addUpdateListener {
                    val v = it.animatedValue as Float
                    fg.translationX = v
                    applyProgress(v)
                }
                start()
            }
        }

        fun settle(target: Float) {
            animateTo(target)
            if (target == 0f) {
                if (openRow === fg) forgetOpenRow()
            } else {
                openRow = fg
                openRowCloser = { animateTo(0f) }
            }
        }

        // 行全体（区切り線込み）。払って消す時に高さを畳むので先に作っておく。
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                container,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                )
                setBackgroundColor(color(R.color.lw_divider))
            })
        }

        var gone = false

        /**
         * 払って消す。行を右の画面外へ飛ばし、続けて高さを 0 まで畳んで
         * 下の行を詰めさせてから、実際に削除する（Undo バーも出る）。
         */
        fun flyAway() {
            if (gone) return
            gone = true
            forgetOpenRow()
            ValueAnimator.ofFloat(fg.translationX, container.width.toFloat()).apply {
                duration = FLY_MS
                interpolator = AccelerateInterpolator()
                addUpdateListener { fg.translationX = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val h = rowView.height
                        ValueAnimator.ofInt(h, 0).apply {
                            duration = COLLAPSE_MS
                            addUpdateListener {
                                rowView.layoutParams?.let { lp ->
                                    lp.height = it.animatedValue as Int
                                    rowView.layoutParams = lp
                                }
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    removeAt(position)
                                }
                            })
                            start()
                        }
                    }
                })
                start()
            }
        }

        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        fg.setOnTouchListener { _, e ->
            if (gone) return@setOnTouchListener true
            // 速度は「画面から見た座標」で測る。この行は指について一緒に動くので、
            // 行から見た座標（e.x）のままだと指がほぼ止まって見え、速度が 0 になる。
            fun track(ev: MotionEvent) {
                val abs = MotionEvent.obtain(ev)
                abs.setLocation(ev.rawX, ev.rawY)
                tracker?.addMovement(abs)
                abs.recycle()
            }
            track(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startTx = fg.translationX
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain()
                    track(e)
                    true // 以降の MOVE/UP を受け取るために消費する
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    // 横移動が縦より優勢になって初めてスワイプと判定する。
                    // それまでは ListView に渡すので、縦スクロールは普通にできる。
                    // 右方向、または既に開いている行（左に戻す操作）だけを拾う。
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) &&
                        (dx > 0f || startTx > 0f)
                    ) {
                        dragging = true
                        listView.requestDisallowInterceptTouchEvent(true)
                        if (openRow !== fg) {
                            openRowCloser?.invoke()
                            forgetOpenRow()
                        }
                    }
                    if (dragging) {
                        // ゴミ箱の位置で止めず、行の幅いっぱいまで引っぱれる
                        val tx = (startTx + dx).coerceIn(0f, container.width.toFloat())
                        fg.translationX = tx
                        applyProgress(tx)
                    }
                    dragging
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // 最後の MOVE より先まで指が進んで離れることがあるので、離した位置で測り直す
                        val tx = (startTx + e.rawX - downX).coerceIn(0f, container.width.toFloat())
                        val t = tracker
                        t?.computeCurrentVelocity(1000) // px/秒
                        val vx = t?.xVelocity ?: 0f
                        when {
                            // 勢いよく右に払った／半分以上引っぱった → そのまま飛ばして消す
                            e.actionMasked == MotionEvent.ACTION_UP &&
                                (vx > FLING_DISMISS_DP_PER_SEC * dp ||
                                    tx > container.width * DISMISS_RATIO) -> flyAway()
                            // ゆっくりなら従来どおり、ゴミ箱の位置で止める／閉じる
                            else -> settle(if (tx >= reveal * OPEN_RATIO) reveal else 0f)
                        }
                    } else if (fg.translationX != 0f) {
                        settle(0f) // 開いた行の本体をタップ → 閉じる
                    }
                    tracker?.recycle()
                    tracker = null
                    dragging = false
                    true
                }

                else -> false
            }
        }

        return rowView
    }

    /** 2 色を t(0..1) で混ぜる。スワイプ中に文字色を白へ寄せるのに使う。 */
    private fun blend(from: Int, to: Int, t: Float): Int {
        val u = 1f - t
        return Color.argb(
            (Color.alpha(from) * u + Color.alpha(to) * t).toInt(),
            (Color.red(from) * u + Color.red(to) * t).toInt(),
            (Color.green(from) * u + Color.green(to) * t).toInt(),
            (Color.blue(from) * u + Color.blue(to) * t).toInt()
        )
    }

    companion object {
        const val REQ_TREE = 1

        /** 一度でも右スワイプで削除したか。立っていれば一覧のヒントを出さない。 */
        private const val KEY_SWIPE_LEARNED = "swipe_learned"

        // 払って消す動きの調整ノブ
        /** これより速く右へ払ったら、距離に関係なく飛ばして消す（dp/秒）。 */
        const val FLING_DISMISS_DP_PER_SEC = 1000f

        /** ゆっくりでも行幅のこの割合まで引っぱったら消す。 */
        const val DISMISS_RATIO = 0.5f

        /** 画面外へ飛んでいく時間(ms)。 */
        const val FLY_MS = 160L

        /** 飛んだあと、行の高さを畳んで下を詰める時間(ms)。 */
        const val COLLAPSE_MS = 160L

        /** スワイプで開く幅(dp)。ゴミ箱の露出量でもある。 */
        const val REVEAL_DP = 76f

        /** 指を離した時、この割合まで開いていれば開いたままにする。 */
        const val OPEN_RATIO = 0.4f

        /** 開閉アニメの長さ(ms)。 */
        const val SETTLE_MS = 180L
    }
}
