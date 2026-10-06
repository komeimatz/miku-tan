# ミク単 — 開発ガイド

このファイルは、このアプリを改造・ビルドする人（と、その人が使う AI コーディングエージェント）向けの手引きです。
設計上の判断と、その理由、実際にハマったところをまとめています。

---

## 0. 守ってほしいこと

- **広告・課金・投げ銭を入れない。** 勉強の道具にノイズを差し込むことを否定する立場で作っています。
  ライセンス（PolyForm Noncommercial）でも営利利用は禁止です
- **イラストはライセンスの対象外**（`NOTICE.md`）。改造版を配布するなら差し替えてください
- **権限を増やさない方向で考える。** 今のアプリはネット権限もストレージ権限も持っていません。
  これは設計上の強みなので、機能追加のために権限を足す前に、足さずに済む方法を探してください
- **既定の中国語プロンプト（`Prompt.CHINESE`）は、実際に AI に通して出力を確かめて確定した文面**です。
  変えるなら、変えたあとに実際に回して確かめてください

---

## 1. このアプリは何か

外国語（や専門用語）の「知らない言葉」を集めて、Anki の暗記カードにするための入口となる Android アプリです。

```
どのアプリでも、知らない単語を長押し →「⋮」→「ミク単」
      ↓   （words.txt に 1 行追記。ミクさんが「DONE!」と出て消える）
あとでアプリを開き、覚えていた語をスワイプで消す
      ↓
「Ankiファイル作成をAIに依頼」→ クリップボードにプロンプト＋単語リスト（リストは空になる）
      ↓
チャット AI（ChatGPT / Gemini / Claude など）に貼る → import.txt が返ってくる
      ↓
AnkiDroid に取り込む
```

### 設計の要

**拾う瞬間は限界までバカに徹する。** 整形・重複除去・読み付与を一切しない。
知能はすべて後工程（AI）に送る。拾う瞬間に手間があると続かないため。

このおかげで、**AI が賢くなるほど、アプリを一切いじらずにカードの質が上がる**。
新しい機能（量詞を添える、繁体字を簡体字に揃える等）も、コードではなくプロンプトの数行で足せる。

### 似た道具との違い

Yomitan ＋ AnkiConnect（ブラウザの辞書ポップアップからカードを作る定番）とは目的が同じですが、

- **ブラウザの外でも使える**（テキスト選択か共有ができるアプリならどこでも）
- **カードの中身を辞書でなく LLM が作る**（文脈に合った例文、量詞、別読みなど）
- **拾う瞬間に何も判断しない**

が違います。逆に、その場で意味を知りたい・オフラインで完結したい・答えの揺れが嫌、なら Yomitan が向いています。

---

## 2. 構成

```
miku-tan/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ gradlew / gradlew.bat / gradle/wrapper/   … Gradle 8.9 固定
└─ app/
   ├─ build.gradle.kts          … minSdk 29 / targetSdk 35 / compileSdk 35 / Kotlin
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/tealtranquility/mikutan/
      │   ├─ MainActivity.kt       … 一覧・スワイプ削除・Undo・手動追加・AI へ送る
      │   ├─ CaptureActivity.kt    … 単語の受け口（テキスト選択／共有）＋ミクさん演出
      │   ├─ GuideActivity.kt      … 初回ガイド（図 3 ページ＋言語選択）
      │   ├─ SettingsActivity.kt   … 設定
      │   ├─ PromptEditActivity.kt … プロンプトの編集
      │   ├─ WordStore.kt          … 保存先 3 段構え（SAF / MediaStore / 保険）
      │   ├─ Prompt.kt             … 既定プロンプト 3 種（中国語／英語／専門用語）
      │   └─ MikuArt.kt            … 追加時の絵の選択
      └─ res/
         ├─ values/strings.xml     … app_name / launcher_name（ホーム画面の「ミク単」）/ capture_label
         ├─ drawable/              … ボタン、ゴミ箱アイコン（VectorDrawable）
         ├─ drawable-nodpi/        … イラスト、ガイド画像
         └─ mipmap-*/              … アダプティブアイコン
```

依存は `androidx.documentfile` のみ。レイアウト XML は使わず、画面はすべてコードで組んでいます
（画面数が少なく、依存を増やしたくないため）。

配色は `#86CECB`（ミクさんカラー）と `#E12885`（マゼンタ）。ダークモードに追従します。

---

## 3. ビルド

### 必要なもの
- JDK 17
- Android SDK（Platform API 35、Build-Tools 35.0.0。AGP が 34.0.0 も自動で取りにいく）
- Android Studio は必須ではない。コマンドラインツールだけで組めます
  （Windows なら `scoop install temurin17-jdk android-clt` が手軽）

### local.properties
リポジトリ直下に作る。環境依存なので Git 管理外。
```
sdk.dir=C:/Users/<ユーザー名>/AppData/Local/Android/Sdk
```
**バックスラッシュでなく `/` で書くこと。** Java の properties 形式は `\` をエスケープとして解釈するため、
`C:\Users\...` と書くと `\U` が食われてパスが壊れる。

### ビルドとインストール
```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Gradle wrapper を作り直す場合の注意
Gradle 9.x は **AGP 8.5.2 と互換がない**。プロジェクト内で `gradle wrapper` を叩くと落ちるので、
空ディレクトリに `settings.gradle.kts` だけ置いて `gradle wrapper --gradle-version 8.9` を実行し、
生成物をコピーする。

### よくあるつまずき
| 症状 | 原因と対処 |
|---|---|
| `SDK location not found` | `local.properties` が無い／パスが違う |
| `Unsupported class file major version` | JDK が 17 でない |
| `adb` が見つからない | PATH を通したあとの**新しいシェル**で実行する |
| 端末が `unauthorized` | 端末側の「USB デバッグを許可しますか」を承認。**Android 11 以降は 7 日つながないと許可が自動で取り消される** |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 署名鍵が違う。デバッグ鍵（`~/.android/debug.keystore`）は PC ごとに別物 |
| リソース名でビルドが落ちる | Android のリソース名は **小文字・数字・`_` のみ**（`-` や日本語は不可） |
| AGP 8.5.2 × compileSdk 35 の警告 | 既知。ビルドは通る |

---

## 4. 動作確認

### 手で確かめる
1. 起動 → 初回ガイド 4 ページ。最後の「英語／中国語／その他」を選ぶと閉じる
2. ブラウザ等で単語を長押し → `⋮` → 「ミク単」 → ミクさんが出て消える
3. アプリに戻ると一覧に増えている
4. 行を右スワイプ（ゴミ箱）／勢いよく払う（そのまま消える）→ 4 秒の Undo バー
5. 「Ankiファイル作成をAIに依頼」→ クリップボードに入り、リストは空になる →「↩ 前回 AI に送った N 語を戻す」で戻せる

### adb で確かめる
UI の確認は `screencap` より **`uiautomator dump` のほうが確実**（`screencap` は黒画像を返すことがある）。
```
# キャプチャを 1 件流す
adb shell am start -a android.intent.action.PROCESS_TEXT -t text/plain --es android.intent.extra.PROCESS_TEXT "训斥" -n com.tealtranquility.mikutan/.CaptureActivity
# 保存先を直接見る
adb shell cat "/sdcard/Documents/ミク単/words.txt"
# 画面のテキスト付きノード木
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
```
Windows の PowerShell で `adb exec-out screencap -p > shot.png` とすると、**リダイレクトでバイナリが壊れる**。
端末側に保存して `adb pull` すること。

設定画面などは `exported="false"` なので、adb から直接は起動できない（正しい挙動）。

### どの AI で通るか
Claude、Gemini Flash（無料枠）、ChatGPT（無料版）で確認済み。
**プロンプトを変えたら、無料枠の弱いモデルでも通るかを確かめること。** 配布先の多くは課金していない。

---

## 5. 各ファイルの要点とハマりどころ

### CaptureActivity.kt（単語の受け口）
透過テーマのオーバーレイで、`FLAG_NOT_TOUCHABLE` によりタップは背後のアプリに素通りする。

入口は 2 つ:

| | extra | 使う場面 |
|---|---|---|
| `ACTION_PROCESS_TEXT` | `EXTRA_PROCESS_TEXT` / `EXTRA_PROCESS_TEXT_READONLY` | テキスト選択メニュー |
| `ACTION_SEND` | `EXTRA_TEXT` | 長押し選択ができず「共有」しか出さないアプリ |

Web ページやメール本文のような読み取り専用の選択は `..._READONLY` で飛んでくるので、両方見ること。
60 文字を超える入力は弾く（本文まるごと渡してくるアプリがあるため）。

**透過テーマなので背後のアプリがそのまま透ける。** イラストを透過 PNG にすると、ダークモードの画面の上で
黒い線が消える。**吹き出し・円の内側は白で塗る**こと。

### 選択メニューに「ミク単」が出ない時
切り分けは**この順**で。

1. **本当にインストールされているか**（`adb shell pm list packages | grep mikutan`）。
   開発中に一度、「出ない」の原因が単に未インストールだったことがある
2. **OS が候補として認識しているか**
   ```
   adb shell cmd package query-activities --brief -a android.intent.action.PROCESS_TEXT -t text/plain
   ```
   `dumpsys package` はマニフェスト上の宣言しか見えないので、切り分けとしては一段弱い
3. **一度アプリを起動したか**（未起動のアプリはインテント解決から除外されることがある）
4. **`⋮` を開いたか**

**並び順について**: 選択ツールバーは組み込みの項目（コピー・翻訳など）を先に並べ、外部アプリの項目は
**構造的に常にその後ろ**。使用頻度で前に出てくることはない。多くの端末で `⋮` の奥に入る。
（使うほど手前に来るのは**共有シート**のほう）

**他アプリの項目を減らしたい時**: Android 17 では adb（shell ユーザー）から**コンポーネント単位の
有効／無効は変えられない**（`Shell cannot change component state`）。自分のデバッグビルドでさえ拒否される。
パッケージ丸ごとなら可。AnkiDroid の項目は AnkiDroid 自身の設定（全般 → システム全体）で消せる。

### MainActivity.kt（一覧）
- ヘッダ（アプリ名・件数）とフッタ（「＋ 手動で追加」）は `addHeaderView` / `addFooterView` で差している。
  アダプタの管理外なので、コピー対象にもスワイプ対象にもならない
- **削除は右スワイプのみ**。左スワイプだと左寄せの単語が行の左端からはみ出して見えなくなるため
- 削除は 2 通り: ゆっくりずらす → ゴミ箱で止まる／勢いよく払う・半分以上引く → 飛んで消える。
  一発で消えても 4 秒の Undo バーで戻せる、が一発削除を許す前提
- **AI に送った瞬間にリストを空にする**。残すと次に送る時に同じ語でまたカードが作られるため。
  控えを `last_sent.txt` に 1 回分だけ残し、リストが空の間「戻す」を出す
  （コピー直後に AI アプリへ移動するので、4 秒の Undo では間に合わない）

**ハマりどころ: 速度は画面座標で測る。** スワイプの行は指について一緒に動くので、行から見た座標（`e.x`）を
`VelocityTracker` に渡すと指がほぼ止まって見え、**どれだけ速く払っても速度 0 になる**。
`MotionEvent.obtain()` した複製に `setLocation(rawX, rawY)` してから渡すこと。

ActionBar をテーマで消しているので、ステータスバー等の余白は `setOnApplyWindowInsetsListener` で自前で入れている
（targetSdk 35 は Android 15 以降、画面端まで描画するのが既定）。

### WordStore.kt（保存先）
3 段構え:

1. **SAF で選んだフォルダ** … 設定で明示指定した時だけ
2. **既定: `Documents/ミク単`** … MediaStore 経由。**権限は一切不要**（API 29 以降。minSdk を 29 にした理由）
3. **保険: アプリ専用領域** … 1 も 2 も失敗した時。単語を落とさないため

`Download` でなく `Documents` なのは、アプリが作った利用者の文書の置き場が Documents、というのが作法だから。

SAF のフォルダは、選んだあとに消される・権限が切れるなどで使えなくなる。**書ける状態かを確かめてから使い**、
ダメなら既定に逃がす（`usableTree()`）。読み・書き・追記の 3 つとも同じ判定を通すこと
（書き込みだけ逃がすと、一覧に出てこないという分かりにくいズレになる）。逃がしている間は一覧で警告を出す。

MediaStore の追記（`"wa"`）・全書き換え（`"wt"`）・同名ファイルの重複作成が起きないことは実機で確認済み。

### GuideActivity.kt（初回ガイド）
- 最後の言語選択ページでは「次へ」を隠し、どれかを選ぶまで見了扱いにしない
- ページめくりは「速く払った」か「画面幅の 22% 動いた」でめくる。
  **最寄りページへの吸着（`roundToInt`）にすると、半画面動かさないとめくれず重く感じる**
- 連続スワイプでアニメと指が `translationX` を奪い合わないよう、アニメを保持して必ず `cancel()` する

**ハマりどころ: `apply` の中で外側の変数が隠れる。** `GradientDrawable().apply { ... current ... }` と書くと、
`current` がレシーバの `Drawable.getCurrent()` に解決され、外側の変数ではなくなる。値は `apply` の外で決めること。

ガイド画像は `drawable-nodpi/guide_1..3.png`。`FIT_CENTER` で縦横比を保って収まるので、端末ごとに用意する必要はない。
**横 1080 × 縦 1620（2:3）** が縦長スマホと 16:9 スマホの中間で余白が少ない。

### Prompt.kt / MikuArt.kt
- 既定プロンプトは中国語・英語・専門用語の 3 種。文面の末尾は必ず `# 単語リスト`（後ろに単語が足される）
- 専門用語版は「**確信の持てない用語はカードを作らず報告させる**」。専門用語の定義を AI が創作すると、
  間違いをそのまま覚えることになるため
- **追加時のミクさんの絵はプロンプトと連動させない。** 連動させると作った本人以外には気づけない隠れた仕様になる。
  連動するのは初回ガイドの選択時だけ

### 画像を扱う時の注意
- `drawable-nodpi` の画像は**置いた大きさがそのままメモリに載る**（1 ピクセル 4 バイトで展開）。
  表示サイズ程度（横 1080px 以下）に縮めて置く
- `getIdentifier()` で名前から引いている画像（ガイド、失敗時の絵、言語別の絵）は、**未配置でもビルドが通る**
- アダプティブアイコンの**テーマアイコン用（monochrome）は前景から生成**している。前景を描き直したら作り直すこと
  （明るさ → 透明度に変換して、黒い線だけを残す）

### SettingsActivity.kt
- 連絡先リンクはブラウザ／X アプリが開くので、アプリ自体はネット権限を持たないまま
- バージョン表示は `PackageManager` から取っている（AGP 8 以降、`BuildConfig` は既定で生成されない）
- 「全設定をリセット」は設定だけ消し、**単語データは消さない**

---

## 6. 同期（任意）

単語は `Documents/ミク単/words.txt` に溜まるただのテキストファイルなので、
Google Drive などに上げたい場合は、フォルダを監視してアップロードする同期アプリ（Autosync for Google Drive 等）を
組み合わせる。追記中心なので一方向のアップロードで十分。

---

## 7. 今後の候補

- AnkiDroid の API（`com.ichi2.anki.permission.READ_WRITE_DATABASE`）でカードを直接入れる。
  ただし**権限が 1 つ増える**ので、「権限ゼロ」とのトレードオフになる
- `archive.txt`（削除した語の履歴）を見返す画面
- 英語・専門用語プロンプトの検証と改善
- 穴埋め（Cloze）カード用のプロンプト（60 文字制限の見直しが必要）
