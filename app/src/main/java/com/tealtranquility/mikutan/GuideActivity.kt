package com.tealtranquility.mikutan

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 初回起動時だけ出す使い方ガイド。図入り 3 ページ＋言語選択 1 ページの計 4 ページ。
 *
 * 進み方は「次へ」ボタンが主役で、左スワイプでも進める（紙をめくる向きに合わせて、
 * 左スワイプ＝次、右スワイプ＝戻る）。ボタンがあるので方向を知らなくても詰まらない。
 *
 * 最後の言語選択ページでは「次へ」を隠してある。**どちらかを選んで初めて
 * 見了扱いになる**ので、既定プロンプトが未選択のまま使われることがない
 * （中国語が既定のままだと、日本のたいていの利用者には合わないため）。
 *
 * 図は res/drawable-nodpi/guide_1.png … guide_3.png を置けば自動で読まれる。
 * 無い間はその場に枠だけのプレースホルダを描くので、画像が未用意でもビルドは通る。
 */
class GuideActivity : Activity() {

    private val dp: Float get() = resources.displayMetrics.density
    private val pageWidth: Float get() = resources.displayMetrics.widthPixels.toFloat()

    private val tutorial = listOf(
        // 多くの端末で「ミク単」は選択メニューの最初の段に出ず、「⋮」の奥に入る
        "単語を長押しして、「⋮」から「ミク単」を選ぶと、\nこのアプリのリストに追加されます。",
        "溜まったら「Ankiファイル作成をAIに依頼」を押して、\n" +
            "コピーされた文章を AI に貼り付けてください。",
        "AI が返したファイルを開いて、\nAnkiDroid に取り込みます。\n" +
            "（Gemini や Claude でも同じように使えます）"
    )
    // スワイプ削除はガイドから外し、一覧画面のヒントで教える（MainActivity）。
    // 3 枚を超えると読まれにくいのと、消したくなったその画面で見せるほうが伝わるため。
    // 3 枚目の文面は、実際の AI（ChatGPT 無料版など）で手順を確かめてから詰める。

    /** 図のページ＋最後の言語選択ページ。 */
    private val pageCount get() = tutorial.size + 1

    private val pageViews = mutableListOf<View>()
    private val dots = mutableListOf<View>()
    private lateinit var nextBtn: Button
    private var current = 0
    private var settleAnim: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        showPage(0, animate = false)
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val pad = (24 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.lw_bg))
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

        // --- ページを重ねて置き、translationX で横に並べる ---
        val pager = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        repeat(pageCount) { i ->
            val p = if (i < tutorial.size) buildTutorialPage(i + 1, tutorial[i], pad)
            else buildChoicePage(pad)
            pageViews.add(p)
            pager.addView(p)
        }
        attachSwipe(pager)
        root.addView(pager)

        // --- ページ位置の点 ---
        val dotBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * dp).toInt())
        }
        repeat(pageCount) {
            val d = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt())
                    .apply { marginEnd = (8 * dp).toInt() }
            }
            dots.add(d)
            dotBox.addView(d)
        }
        root.addView(dotBox)

        // --- 次へ（最後の言語選択ページでは隠す） ---
        nextBtn = Button(this).apply {
            text = "次へ"
            isAllCaps = false
            textSize = 15f
            setTextColor(color(R.color.lw_button_text))
            setBackgroundResource(R.drawable.btn_lw)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
            ).apply { setMargins(pad, 0, pad, (20 * dp).toInt()) }
            setOnClickListener { showPage(currentPage() + 1) }
        }
        root.addView(nextBtn)

        return root
    }

    /** current を apply の外から読むための小物（apply 内だと Drawable.current に隠される）。 */
    private fun currentPage() = current

    private fun buildTutorialPage(number: Int, body: String, pad: Int): View {
        // 図の額縁は画面の左右の端まで届かせるので、横の余白は本文の側にだけ付ける
        val box = newPageBox(pad).apply { setPadding(0, pad, 0, pad) }

        val art = drawableId("guide_$number")
        val imageArea: View = if (art != 0) {
            framedImage(art)
        } else {
            // 画像が未用意の間の仮置き。枠だけ描いて、何を入れる場所か書いておく。
            val line = color(R.color.lw_divider)
            val w = (1 * dp).toInt()
            TextView(this).apply {
                text = "guide_$number.png\n（画像を置くとここに出ます）"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.lw_text_sub))
                background = GradientDrawable().apply {
                    setStroke(w, line)
                    setColor(Color.TRANSPARENT)
                }
            }
        }
        box.addView(
            imageArea,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (art == 0) setMargins(pad, 0, pad, 0)
            }
        )

        box.addView(TextView(this).apply {
            text = body
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setTextColor(color(R.color.lw_text))
            setPadding(pad, (24 * dp).toInt(), pad, (8 * dp).toInt())
        })

        return box
    }

    /**
     * ガイド画像に暗いティールの額縁を付けて、額縁の外側が画面の幅いっぱいになる大きさで中央に置く
     * （背の低い画面で縦が足りなければ、縦に合わせて縮める）。
     * スクショがそのまま画面いっぱいに出ると、本物の画面と見分けがつかず「説明の図」だと分かりにくいため。
     * 額縁は画像の縦横比にぴったり沿わせたいので、領域の大きさが決まってから寸法を計算して当てる。
     */
    private fun framedImage(art: Int): View {
        val border = (FRAME_DP * dp).toInt()
        val image = ImageView(this).apply {
            setImageResource(art)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(border, border, border, border)
            background = GradientDrawable().apply {
                cornerRadius = FRAME_RADIUS_DP * dp
                setColor(color(R.color.lw_guide_frame))
            }
        }
        val area = FrameLayout(this)
        area.addView(image, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        area.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l == or - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            val d = image.drawable ?: return@addOnLayoutChangeListener
            val maxW = (r - l).toFloat()
            val maxH = (b - t).toFloat()
            // 額縁の内側に、画像を縦横比のまま最大で収める
            val scale = minOf(
                (maxW - 2 * border) / d.intrinsicWidth,
                (maxH - 2 * border) / d.intrinsicHeight
            )
            val lp = image.layoutParams
            lp.width = (d.intrinsicWidth * scale).toInt() + 2 * border
            lp.height = (d.intrinsicHeight * scale).toInt() + 2 * border
            // レイアウト中なので、寸法の反映は次の周回に回す
            area.post { image.layoutParams = lp }
        }
        return area
    }

    /** 最後のページ。ここで選んだ言語が既定プロンプトになる。 */
    private fun buildChoicePage(pad: Int): View {
        val box = newPageBox(pad).apply { gravity = Gravity.CENTER }

        box.addView(TextView(this).apply {
            text = "あなたが勉強したいのは？"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.lw_text))
            setPadding(0, 0, 0, (28 * dp).toInt())
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // 初回だけは、プロンプトとミクさんの絵を 1 回の選択でまとめて決める。
        // 以後はそれぞれ設定で別々に変えられる。
        row.addView(choiceButton("英語") {
            Prompt.resetToEnglish(this)
            MikuArt.set(this, MikuArt.DONE)
            done("英語")
        })
        row.addView(gap())
        row.addView(choiceButton("中国語") {
            Prompt.resetToChinese(this)
            MikuArt.set(this, MikuArt.GAODING)
            done("中国語")
        })
        row.addView(gap())
        row.addView(choiceButton("その他") {
            // プロンプトは自分で書いてもらうしかない。英語版が一番書き換えやすいので
            // 下書きとして入れて、そのまま編集画面を開く。
            Prompt.resetToEnglish(this)
            MikuArt.set(this, MikuArt.OKKE)
            markSeen(this)
            startActivity(Intent(this, PromptEditActivity::class.java))
            Toast.makeText(
                this, "英語版を下書きにしています。勉強したい言語向けに書き換えてください",
                Toast.LENGTH_LONG
            ).show()
            finish()
        })
        box.addView(row)

        box.addView(TextView(this).apply {
            text = "「その他」を選ぶと、英語版を下書きにして\n" +
                "Ankiカード作成プロンプトの編集画面が開きます。\n" +
                "追加したときのミクさんは、あとから設定で変えられます。"
            textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setTextColor(color(R.color.lw_text_sub))
            setPadding(0, (28 * dp).toInt(), 0, 0)
        })

        return box
    }

    private fun gap() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams((10 * dp).toInt(), 1)
    }

    private fun choiceButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(color(R.color.lw_button_text))
            setBackgroundResource(R.drawable.btn_lw)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(0, (56 * dp).toInt(), 1f)
            setOnClickListener { onClick() }
        }

    private fun newPageBox(pad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(pad, pad, pad, pad)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // ------------------------------------------------------------ ページ

    private fun showPage(index: Int, animate: Boolean = true) {
        current = index.coerceIn(0, pageCount - 1)
        layoutPages(-current * pageWidth, animate)
        dots.forEachIndexed { i, d ->
            // 色は apply の外で決める。apply の中だと Drawable のメンバ（current など）が
            // 外側の変数を隠してしまう。
            val c = color(if (i == current) R.color.lw_button else R.color.lw_divider)
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
        }
        // 最後は言語を選ぶことが「次へ」の代わりになる
        nextBtn.visibility = if (current == pageCount - 1) View.GONE else View.VISIBLE
    }

    /** offset = 0 なら 1 ページ目が画面内。ページ i は offset + i*幅 の位置に置く。 */
    private fun layoutPages(offset: Float, animate: Boolean) {
        // 走っているアニメを必ず止める。連続でスワイプした時に
        // 指の動きと前のアニメが同じ translationX を奪い合わないように。
        settleAnim?.cancel()
        settleAnim = null
        if (!animate) {
            pageViews.forEachIndexed { i, v -> v.translationX = offset + i * pageWidth }
            return
        }
        val from = pageViews[0].translationX
        settleAnim = ValueAnimator.ofFloat(from, offset).apply {
            duration = SETTLE_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val o = it.animatedValue as Float
                pageViews.forEachIndexed { i, v -> v.translationX = o + i * pageWidth }
            }
            start()
        }
    }

    /**
     * ページめくりの指の処理。
     *
     * めくる条件は 2 つで、**どちらか満たせばめくる**:
     *  - 速く払った（`FLING_DP_PER_SEC` 以上）… 距離は問わない
     *  - ゆっくりでも `FLIP_RATIO` ぶん動いた
     *
     * 「最寄りのページに吸着」だと半画面ぶん動かさないとめくれず、
     * かなり払っても戻される。それが重く感じる原因なので採らない。
     */
    private fun attachSwipe(pager: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var base = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        pager.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    // アニメ途中で掴まれても飛ばないよう、今見えている位置を基準にする
                    settleAnim?.cancel()
                    base = pageViews[0].translationX
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) dragging = true
                    if (dragging) {
                        val min = -(pageCount - 1) * pageWidth
                        layoutPages((base + dx).coerceIn(min, 0f), animate = false)
                    }
                    dragging
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val t = tracker
                        t?.addMovement(e)
                        t?.computeCurrentVelocity(1000) // px/秒
                        val vx = t?.xVelocity ?: 0f
                        val moved = pageViews[0].translationX - base
                        val step = when {
                            abs(vx) > FLING_DP_PER_SEC * dp -> if (vx < 0) 1 else -1
                            abs(moved) > pageWidth * FLIP_RATIO -> if (moved < 0) 1 else -1
                            else -> 0
                        }
                        showPage(current + step)
                    }
                    tracker?.recycle()
                    tracker = null
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    /** 言語を選んだら見了扱いにして閉じる。 */
    private fun done(langLabel: String) {
        markSeen(this)
        Toast.makeText(this, "$langLabel の設定にしました", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------------------------------------------------------------- 小物

    @SuppressLint("DiscouragedApi")
    private fun drawableId(name: String): Int =
        resources.getIdentifier(name, "drawable", packageName)

    private fun color(id: Int): Int = getColor(id)

    companion object {
        private const val KEY_SEEN = "guide_seen"

        // ページめくりの手触りの調整ノブ
        /** ゆっくり動かした時、これだけ動けばめくる（画面幅に対する割合）。 */
        private const val FLIP_RATIO = 0.22f

        /** これより速く払ったら距離に関係なくめくる（dp/秒）。 */
        private const val FLING_DP_PER_SEC = 380f

        /** めくりアニメの長さ(ms)。 */
        private const val SETTLE_MS = 170L

        // ガイド画像の額縁
        /** 額縁の太さ(dp)。 */
        private const val FRAME_DP = 30f

        /** 額縁の外側の角の丸み(dp)。内側は角ばったままにして、絵の角を欠かさない。 */
        private const val FRAME_RADIUS_DP = 18f

        /** まだ一度も見ていないか。MainActivity から初回だけ出すのに使う。 */
        fun shouldShow(ctx: Context): Boolean =
            !ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getBoolean(KEY_SEEN, false)

        fun markSeen(ctx: Context) {
            ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SEEN, true).apply()
        }
    }
}
