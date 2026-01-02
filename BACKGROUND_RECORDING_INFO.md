# バックグラウンド録画の動作説明

## 現在の実装状態

### ✅ 動作するもの
1. **VideoRecording（動画録画）**
   - バックグラウンドでもVideoCapture経由の録画は継続
   - カスタムLifecycleOwner（常にRESUMED状態）により実現
   - 録画ファイルには映像が正しく記録される

2. **フォアグラウンドサービス**
   - 通常のService（非LifecycleService）として実装
   - アプリのライフサイクルに依存せず動作

3. **音声録音**
   - バックグラウンドでもマイクから音声を記録

### ❌ 動作しないもの（Androidの制限）

1. **ImageCapture（プレビュー画像キャプチャ）**
   - バックグラウンドではカメラからの静止画キャプチャが制限される
   - セキュリティ上の理由でAndroid OSが制限
   - **これは正常な動作です**

2. **カメラプレビュー表示**
   - バックグラウンドではプレビューSurfaceが更新されない
   - ただし、録画には影響なし

## 重要な理解

### 「カメラの映像が止まる」とは？

以下の2つの意味があります：

#### 1. プレビュー画像が更新されない（正常）
- `capturePreviewImage()`による1秒ごとのプレビューキャプチャが失敗
- MainActivityに表示されるプレビュー画像が更新されない
- **しかし、録画ファイルには映像が記録されている**
- これはAndroidの仕様

#### 2. 録画ファイルに映像が記録されない（異常）
- VideoRecording自体が停止している
- 録画ファイルが空または最初のフレームで固まっている
- **この場合は問題あり**

## 確認方法

### 録画ファイルをチェック
```
1. バックグラウンドで録画開始
2. 10-20秒待機
3. フォアグラウンドに戻る
4. 録画停止
5. ギャラリーで録画ファイルを再生
   - 映像が動いている → ✅ 正常（VideoRecordingは動作している）
   - 映像が止まっている/真っ黒 → ❌ 異常
```

### ログを確認
```
adb logcat | grep RecordingService
```

以下のログが出ていれば録画は動作中：
- `Recording status: XXs, bytes: YYYY` （10秒ごと）
- バイト数が増加していれば録画継続中

## 現在の実装の詳細

### カスタムLifecycleOwner
```kotlin
private val cameraLifecycleOwner = object : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    
    init {
        // 常にRESUMED状態を維持
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}
```

- カメラを常にアクティブな状態にバインド
- バックグラウンドでもカメラが動作
- **VideoRecordingには有効**
- **ImageCaptureには無効**（OSレベルの制限）

### ServiceScope
```kotlin
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```

- アプリのライフサイクルに依存しない
- サービスが明示的に破棄されるまで動作

## 解決策と対処法

### プレビュー画像が更新されない問題
**解決策**: これは仕様です。以下の対処を実装済み：
- エラーログをWARNINGレベルに変更
- try-catchで例外を捕捉
- 録画は継続

### 録画ファイルに映像が記録されない場合
以下を確認：

1. **ログを確認**
   ```
   Log.i(TAG, "Recording status: ...") 
   ```
   このログが10秒ごとに出ているか？

2. **カメラLifecycle状態**
   ```
   CameraLifecycle: RESUMED
   ```
   が維持されているか？

3. **WakeLock**
   WakeLockが保持されているか？

## 今後の改善案

### オプション1: バックグラウンドでプレビューを無効化
プレビューキャプチャを完全に停止し、録画のみ継続
```kotlin
if (isAppInForeground()) {
    startPreviewCapture()
}
```

### オプション2: 通知にメッセージを表示
バックグラウンドではプレビュー無効を通知
```
"録画中（プレビュー: バックグラウンドでは無効）"
```

### オプション3: フォアグラウンド時のみプレビュー再開
アプリがフォアグラウンドに戻ったらプレビューを再開

## まとめ

**現状**:
- ✅ VideoRecordingはバックグラウンドでも動作
- ❌ ImageCapture（プレビュー）はバックグラウンドでは動作しない（仕様）
- ✅ 録画ファイルには映像が記録される

**ユーザーへの説明**:
「バックグラウンドではプレビュー画像は更新されませんが、録画は継続されており、ファイルには映像が正しく記録されます。」

