# DualSubs · 双语实时字幕（Android）

Real-time bilingual subtitles for any video or live stream on Android: captures the audio other apps are playing, recognizes speech (offline on-device or Soniox cloud), translates it with an LLM, and shows a floating subtitle box on top of the player.

在 Android 手机上给**任何视频、直播或网页播放器**加实时双语字幕：抓取手机正在播放的声音 → 语音识别 → AI 翻译 → 悬浮字幕。

```
其他 App 播放的声音 ──AudioPlaybackCapture──▶ 语音识别
                                            ├─ 本地离线：Silero VAD 切句 + sherpa-onnx 模型（默认，免费）
                                            └─ 云端：Soniox 实时流式（可选，付费）
                                            ▼
                                 Claude / DeepSeek / Groq 等 LLM 翻译
                                            ▼
                           悬浮字幕窗（显示在全屏视频之上）
```

- 不依赖具体 App：浏览器、视频 App、直播 App 都可以，因为抓的是系统播放的声音。
- 只录声音，不录画面，所以视频是否有 DRM 都不影响。
- 需要 Android 10 以上的 ARM 手机。

## 语音识别

| 引擎 | 说明 |
|---|---|
| **本地离线（默认）** | 基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)。模型在 App 设置里按需下载，下载一次后不联网、不计费。说话过程中每秒刷新一次灰色预览，一句说完约 1 秒出结果。 |
| Soniox 云端 | 延迟约 1 秒、准确率更高、自动识别语言，按音频时长计费（约 $0.12/小时），需要 [Soniox](https://soniox.com) API key。 |

可下载的离线模型（从 HuggingFace 下载，优先走 hf-mirror.com 镜像）：

| 模型 | 语言 | 大小 |
|---|---|---|
| [ReazonSpeech k2 v2](https://huggingface.co/reazon-research/reazonspeech-k2-v2)（int8） | 日语，用日本电视节目音频训练 | 约 170 MB |
| [ReazonSpeech k2 v2 ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en)（int8） | 日语 + 英语，可以混着说 | 约 77 MB |
| [SenseVoice](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09)（int8） | 中文、英语、日语、韩语、粤语，自动判断语言，带标点 | 约 240 MB |

**识别语言**：设置里可以选“自动识别”，也可以固定为日语、中文、英语、韩语或粤语。固定语言后，SenseVoice 和 Soniox 的识别会更准，翻译模型也会被告知原文是什么语言。ReazonSpeech 只支持日语（ja-en 版支持日语和英语），选了模型不支持的语言时会提示换模型。

想加别的模型，在 `ModelCatalog.kt` 里加一项即可（sherpa-onnx 支持的 transducer / SenseVoice 模型都能直接用）。

## 翻译（选一个）

| 服务 | 说明 |
|---|---|
| [Claude](https://console.anthropic.com)（默认模型 `claude-opus-5-5`） | 官方 Anthropic Java SDK，低 effort 以减少延迟；遇到安全分类拒绝时自动切换备用模型。可改成 `claude-haiku-5-5`，更便宜、更快。 |
| 任意 OpenAI 兼容接口 | 例如 DeepSeek：Base URL `https://api.deepseek.com`，模型 `deepseek-chat`；Groq：Base URL `https://api.groq.com/openai/v1`。 |
| 不翻译 | 只显示原文。 |

目标语言可以在设置里改（默认简体中文）。每次翻译都会附上前几句作为上下文。

## 字幕显示

- **同时显示几句**：1–3 句，默认 2 句。
- **每句至少 4 秒**：译文出来后至少显示 4 秒，新句子来了也不会立刻顶掉它。
- **字幕保留秒数**：译文出来后再过这么多秒，这句就消失（默认 15 秒；0 = 一直显示，直到被新句子顶掉）。
- **最长一句**：本地识别时，一口气不停顿说满 10 秒会强制切一句。
- 没有字幕时，字幕框会自动隐藏，不挡画面。

## 构建

需要 JDK 17 和 Android SDK（platform 35）。

```bash
./gradlew assembleDebug
```

第一次构建时会自动从 GitHub Releases 下载 sherpa-onnx 的 AAR，放到 `app/libs/`。生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

依赖优先从阿里云 Maven 镜像下载，然后才是官方仓库（见 `settings.gradle.kts`），所以在中国大陆也能直接构建。Windows 上也可以运行 `build.cmd`。

## 使用

1. 安装 APK 并打开。
2. 选识别引擎。用本地离线的话，选一个模型并点 **下载**（下载期间请让 App 留在前台）。
3. 选翻译服务并填 key，或选“不翻译”。
4. 点 **开始字幕**，依次授权：
   - 显示在其他应用上层（悬浮窗）
   - 录音（Android 规定捕获系统声音也需要这个权限）
   - 屏幕共享：选 **整个屏幕**，或单个应用时选播放视频的那个 App。本应用只录声音，不录画面。
5. 切到视频或直播。
6. 字幕框：**点按** 显示菜单（A− / A+ 字号、原文开关、清空、✕ 关闭）；**上下拖动** 改变位置。
7. 停止：点通知栏里的“停止”，或字幕框菜单里的 ✕。

## 已知限制

- 不区分说话人。
- 有的 App 不允许别的 App 捕获它的声音，这时字幕会一直显示“等待语音…”。可以在设置里把音源改成 **外部音源**（麦克风收音），用外放播放。
- 本地识别比较耗电、发热。老手机如果算得比实时慢，最多积压 10 秒音频，再多会丢掉最旧的部分。
- API key 只保存在手机本地（应用私有存储，已关闭备份）。

## 代码结构

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | 设置界面、模型下载、权限和屏幕共享授权流程 |
| `SubtitleService.kt` | 前台服务，把音频、识别、短句合并、翻译、悬浮窗串起来 |
| `AudioCapture.kt` | 16 kHz 单声道 PCM 录音（机内音源或外部音源/麦克风） |
| `SttEngine.kt` | 识别引擎接口 |
| `LocalSttEngine.kt` | 离线识别：Silero VAD + sherpa-onnx，带实时预览 |
| `ModelCatalog.kt` | 可下载的模型列表和下载器（支持断点续传、镜像回退、大小校验） |
| `SonioxClient.kt` | Soniox WebSocket 流式识别 |
| `Translator.kt` | Claude（官方 SDK）和 OpenAI 兼容两种翻译 |
| `SubtitleOverlay.kt` | 可拖动的悬浮字幕窗，负责字幕保留和过期 |

## 许可证

[MIT](LICENSE)

## 第三方组件

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）：构建时下载。
- [Silero VAD](https://github.com/snakers4/silero-vad)（MIT）：`app/src/main/assets/silero_vad.onnx`。
- 识别模型由用户在 App 内下载，各自遵循原仓库的许可证。
