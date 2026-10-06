package com.tealtranquility.mikutan

import android.content.Context

/**
 * 単語を追加したときに出すミクさんの絵をどれにするか。
 *
 * **プロンプト（何語のカードを作るか）とは独立した設定。**
 * 以前は「中国語プロンプトなら搞定啦」と連動させていたが、作った本人以外には
 * 気づけない隠れた連動だったうえ、好きな絵と勉強する言語は本来関係ないので分けた。
 * 連動するのは初回ガイドの言語選択だけ（初回に 2 回聞かないため）。
 *
 * 絵は drawable-nodpi に「基本名＋接尾辞」で置く:
 *   DONE!    … miku_added.png
 *   搞定啦～ … miku_added_zh.png
 *   おっけー … miku_added_ja.png
 * 接尾辞付きが無ければ基本名に落ちる。
 */
object MikuArt {

    const val DONE = "en"
    const val GAODING = "zh"
    const val OKKE = "ja"

    private const val PREFS = "cfg"
    private const val KEY = "miku_art"

    /** 2026-09-15〜26 の版がプロンプト連動で記録していたキー。移行用に読むだけ。 */
    private const val LEGACY_KEY = "study_lang"

    fun get(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY, null) ?: p.getString(LEGACY_KEY, null) ?: DONE
    }

    fun set(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }

    fun label(value: String): String = when (value) {
        GAODING -> "搞定啦～"
        OKKE -> "おっけー"
        else -> "DONE!"
    }

    /** ファイル名の接尾辞。DONE! は接尾辞なし（元の miku_added.png）。 */
    fun suffix(value: String): String = when (value) {
        GAODING -> "_zh"
        OKKE -> "_ja"
        else -> ""
    }
}
