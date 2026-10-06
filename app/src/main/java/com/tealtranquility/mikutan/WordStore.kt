package com.tealtranquility.mikutan

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 単語リスト（words.txt）と保管庫（archive.txt）の読み書きを 1 箇所にまとめる。
 *
 * 保存先は 3 段構え:
 *  1. SAF で選んだフォルダ … 「保存フォルダを変更」で明示的に指定した時だけ
 *  2. 既定: Documents/ミク単 … MediaStore 経由。**権限は一切不要**
 *  3. 保険: アプリ専用領域 … 1 も 2 も失敗した時。データを落とさないため
 *
 * 2 が既定なので、初回起動でユーザーがフォルダを選ぶ必要はない。
 * Download ではなく Documents にしているのは、Download が
 * 「ネットから落としてきた物」の置き場で、アプリが生成した利用者の文書は
 * Documents、というのが Android の作法のため。
 */
object WordStore {

    const val FILE_NAME = "words.txt"
    const val ARCHIVE_NAME = "archive.txt"

    /**
     * 直前に AI へ送った単語の控え。送るとリストは空になるので、
     * AI 側で失敗した時に戻せるように 1 回分だけ残す（次に送ると上書き）。
     */
    const val LAST_SENT_NAME = "last_sent.txt"

    /** 既定の保存先フォルダ名。 */
    const val FOLDER_NAME = "ミク単"

    /** 既定の保存先。MediaStore の RELATIVE_PATH に渡す相対パスでもある。 */
    val DEFAULT_PATH: String = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER_NAME

    private val REL_PATH get() = DEFAULT_PATH

    // ------------------------------------------------------------ 保存先

    fun treeUri(ctx: Context): Uri? =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getString("tree", null)
            ?.let { Uri.parse(it) }

    /**
     * SAF で選んだフォルダが **今も書ける状態か** を確かめてから返す。
     *
     * 選んだ後にフォルダを消された・名前を変えられた・権限が切れた・
     * SD カードを抜かれた、といったことが起きる。これを見ずに書きにいくと、
     * 例外になって**拾った単語がそのまま失われる**（キャプチャは透過オーバーレイなので
     * 失敗に気づきにくい）。使えないと分かったら既定の保存先に逃がす。
     */
    private fun usableTree(ctx: Context): Uri? {
        val tree = treeUri(ctx) ?: return null
        return try {
            val dir = DocumentFile.fromTreeUri(ctx, tree)
            if (dir != null && dir.exists() && dir.canWrite()) tree else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * SAF でフォルダを選んだのに、今は使えない状態か。
     * このとき単語は既定の保存先に逃がしているので、一覧で警告を出すのに使う。
     */
    fun isTreeBroken(ctx: Context): Boolean =
        treeUri(ctx) != null && usableTree(ctx) == null

    /** 設定画面に出す保存先の表示名。実際に書かれる場所を表示する。 */
    fun folderName(ctx: Context): String {
        val tree = treeUri(ctx) ?: return REL_PATH
        val usable = usableTree(ctx)
        if (usable == null) {
            // 設定は残っているのに使えない状態。黙って既定に逃がすと
            // 「Miku に保存しているつもり」が続いてしまうので、ここで知らせる。
            return "$REL_PATH（選んだフォルダが使えません）"
        }
        return DocumentFile.fromTreeUri(ctx, usable)?.name ?: REL_PATH
    }

    // -------------------------------------------------- MediaStore（既定）

    private fun collection(): Uri =
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 既に作ってあるファイルを探す。無ければ null。 */
    private fun findDoc(ctx: Context, name: String): Uri? {
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        // RELATIVE_PATH は末尾スラッシュ付きで格納される
        val args = arrayOf("$REL_PATH/", name)
        ctx.contentResolver.query(
            collection(), arrayOf(MediaStore.MediaColumns._ID), sel, args, null
        )?.use { c ->
            if (c.moveToFirst()) {
                return ContentUris.withAppendedId(collection(), c.getLong(0))
            }
        }
        return null
    }

    /** 新規作成。フォルダが無ければシステムが掘ってくれる。 */
    private fun createDoc(ctx: Context, name: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH)
        }
        return ctx.contentResolver.insert(collection(), v)
            ?: throw IllegalStateException("$REL_PATH にファイルを作成できません")
    }

    private fun doc(ctx: Context, name: String): Uri =
        findDoc(ctx, name) ?: createDoc(ctx, name)

    private fun appendMedia(ctx: Context, name: String, line: String) {
        val uri = doc(ctx, name)
        // "wa" = 追記。環境によっては追記モードを受けないことがあるので、
        // その場合は読み直して全部書き戻す（単語リストなので数 KB、実用上問題ない）。
        try {
            ctx.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                return
            }
        } catch (_: Exception) {
        }
        writeMedia(ctx, name, readMedia(ctx, name) + line)
    }

    private fun readMedia(ctx: Context, name: String): String {
        val uri = findDoc(ctx, name) ?: return ""
        return ctx.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
    }

    private fun writeMedia(ctx: Context, name: String, text: String) {
        val uri = doc(ctx, name)
        // "wt" = 既存内容を切り詰めてから書く
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("書き込みストリームを開けません")
    }

    // -------------------------------------------------- SAF（明示指定時）

    private fun safDoc(ctx: Context, tree: Uri, name: String): Uri {
        val dir = DocumentFile.fromTreeUri(ctx, tree)
            ?: throw IllegalStateException("フォルダにアクセスできません")
        val f = dir.findFile(name)
            ?: dir.createFile("text/plain", name)
            ?: throw IllegalStateException("ファイル作成に失敗")
        return f.uri
    }

    private fun appendSaf(ctx: Context, tree: Uri, name: String, line: String) {
        ctx.contentResolver.openOutputStream(safDoc(ctx, tree, name), "wa")?.use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("書き込みストリームを開けません")
    }

    private fun readSaf(ctx: Context, tree: Uri, name: String): String {
        val dir = DocumentFile.fromTreeUri(ctx, tree) ?: return ""
        val f = dir.findFile(name) ?: return ""
        return ctx.contentResolver.openInputStream(f.uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
    }

    private fun writeSaf(ctx: Context, tree: Uri, name: String, text: String) {
        ctx.contentResolver.openOutputStream(safDoc(ctx, tree, name), "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("書き込みストリームを開けません")
    }

    // -------------------------------------------------------------- 保険

    private fun privateFile(ctx: Context, name: String) =
        File(ctx.getExternalFilesDir(null), name)

    // ---------------------------------------------------------- 公開 API

    /**
     * 1 行追記する。キャプチャ時に使う（最速で終わらせたいのでこれだけ）。
     * MediaStore が転んでもアプリ専用領域に落として、単語だけは失わないようにする。
     */
    fun append(ctx: Context, name: String, line: String) {
        val tree = usableTree(ctx)
        if (tree != null) {
            // 直前の確認をすり抜けて失敗することもあるので、ここでも受け止める
            try {
                appendSaf(ctx, tree, name, line)
                return
            } catch (_: Exception) {
            }
        }
        try {
            appendMedia(ctx, name, line)
        } catch (_: Exception) {
            privateFile(ctx, name).appendText(line, Charsets.UTF_8)
        }
    }

    /** 全文を読む。ファイルが無ければ空文字。 */
    fun readText(ctx: Context, name: String): String {
        val tree = usableTree(ctx)
        if (tree != null) {
            runCatching { return readSaf(ctx, tree, name) }
        }
        return try {
            readMedia(ctx, name)
        } catch (_: Exception) {
            val f = privateFile(ctx, name)
            if (f.exists()) f.readText(Charsets.UTF_8) else ""
        }
    }

    /** 全文を書き換える。リストからの削除で使う。 */
    fun writeText(ctx: Context, name: String, text: String) {
        val tree = usableTree(ctx)
        if (tree != null) {
            try {
                writeSaf(ctx, tree, name, text)
                return
            } catch (_: Exception) {
            }
        }
        try {
            writeMedia(ctx, name, text)
        } catch (_: Exception) {
            privateFile(ctx, name).writeText(text, Charsets.UTF_8)
        }
    }

    /** 単語リストを行の配列で読む（空行は除く）。 */
    fun loadWords(ctx: Context): MutableList<String> =
        readText(ctx, FILE_NAME)
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

    /** 単語リストを丸ごと保存し直す。 */
    fun saveWords(ctx: Context, words: List<String>) {
        val text = if (words.isEmpty()) "" else words.joinToString("\n") + "\n"
        writeText(ctx, FILE_NAME, text)
    }

    /** 覚えていた語を保管庫に送る（消すのではなく残す）。 */
    fun archive(ctx: Context, word: String) {
        runCatching { append(ctx, ARCHIVE_NAME, word + "\n") }
    }
}
