package com.tealtranquility.mikutan

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast

/**
 * Weblio などで選択した文字列を、テキスト選択メニューから受け取り、
 * 同期フォルダ内の words.txt に 1 行追記する。
 * 追記が成功したら、ミクさんが画面下から「ぴょこっ」と出て、少し間を置いて
 * 下へ落ちながら消える縦バウンス演出を 1 度だけ出して finish する。
 */
class CaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // オーバーレイ扱い: タッチもフォーカスも奪わず、後ろの Weblio を邪魔しない
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        // 入口は 2 つある。
        //  1. テキスト選択メニュー（PROCESS_TEXT）
        //     編集可能な欄からは EXTRA_PROCESS_TEXT、Web ページやメール本文など
        //     読み取り専用の選択からは EXTRA_PROCESS_TEXT_READONLY で飛んでくる。
        //  2. 共有メニュー（SEND）… EXTRA_TEXT
        //     ビリビリのコメントや電子書籍など、長押し選択ができず
        //     「共有」しか出さないアプリからはこちらで拾う。
        val raw = (
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            )?.toString()?.trim()

        if (raw.isNullOrEmpty()) {
            finish()
            return
        }

        // 選択範囲でなく本文まるごと渡してくるアプリがまれにあるため、
        // 長すぎる入力はリストを壊す前に弾く。
        if (raw.length > MAX_LEN) {
            Toast.makeText(
                this,
                "選択が長すぎます（${raw.length}文字）。単語だけ選んでね",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val text = raw
        val line = text.replace("\n", " ").replace("\r", " ") + "\n"

        try {
            WordStore.append(this, WordStore.FILE_NAME, line)
            // 保存先は 3 段構えで必ずどこかに書ける（WordStore 参照）ので、
            // 書けたら必ずミクさんを出す。これが追加成功の合図も兼ねる。
            playMikuThenFinish(art("miku_added", R.drawable.miku_added))
        } catch (e: Exception) {
            // ここに来るのは 3 段目（アプリ専用領域）まで落ちてなお失敗した時だけ。
            // 透過オーバーレイなのでトーストだけだと見逃されるため、
            // 失敗用のイラストがあればそれを出す。
            Toast.makeText(this, "保存に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            val failArt = art("miku_failed", 0)
            if (failArt != 0) playMikuThenFinish(failArt) else finish()
        }
    }

    /**
     * 設定で選ばれたミクさん（MikuArt）に合わせてイラストを選ぶ。
     *
     * `<base>_zh` / `<base>_ja` を優先し、無ければ `<base>` に落ちる。
     * たとえば失敗時の絵は `miku_failed.png` 1 枚だけ置けば、誰にでもそれが出る。
     */
    private fun art(base: String, fallback: Int): Int {
        val sfx = MikuArt.suffix(MikuArt.get(this))
        if (sfx.isNotEmpty()) {
            val v = drawableId(base + sfx)
            if (v != 0) return v
        }
        val id = drawableId(base)
        return if (id != 0) id else fallback
    }

    /** 後から置かれるかもしれない画像を名前で引く。無ければ 0。 */
    @SuppressLint("DiscouragedApi")
    private fun drawableId(name: String): Int =
        resources.getIdentifier(name, "drawable", packageName)

    private fun playMikuThenFinish(artRes: Int) {
        val root = FrameLayout(this)
        val miku = ImageView(this).apply {
            setImageResource(artRes)
            adjustViewBounds = true
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        // 横長バッジなので画面幅の 7 割くらい、下寄り中央に配置
        val size = (w * 0.7f).toInt()
        root.addView(
            miku,
            FrameLayout.LayoutParams(
                size,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply {
                bottomMargin = (h * 0.18f).toInt() // 定位置（画面下から少し上）
            }
        )
        setContentView(root)

        val hidden = h * 0.55f   // 画面下に隠れている位置（定位置からの相対）
        miku.translationY = hidden
        miku.alpha = 0f

        // 1) 下からぴょこっと登場（OvershootInterpolator で定位置を少し超えてから収まる）
        val enter = ObjectAnimator.ofFloat(miku, "translationY", hidden, 0f).apply {
            duration = 520L
            interpolator = OvershootInterpolator(2.2f)
        }
        val fadeIn = ObjectAnimator.ofFloat(miku, "alpha", 0f, 1f).apply {
            duration = 200L
        }

        // 2) 少し滞在 → 3) 下へ加速しながら落ちて消える
        val drop = ObjectAnimator.ofFloat(miku, "translationY", 0f, hidden).apply {
            duration = 430L
            startDelay = 650L                       // 滞在時間
            interpolator = AccelerateInterpolator(1.6f) // 重力っぽく加速
        }
        val fadeOut = ObjectAnimator.ofFloat(miku, "alpha", 1f, 0f).apply {
            duration = 360L
            startDelay = 720L
        }

        AnimatorSet().apply {
            // enter / fadeIn は即時、drop / fadeOut は各自の startDelay で遅れて走る
            playTogether(enter, fadeIn, drop, fadeOut)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                }
            })
            start()
        }
    }

    companion object {
        /** 1 回のキャプチャで受け付ける最大文字数（単語・短いフレーズ想定）。 */
        const val MAX_LEN = 60
    }
}
