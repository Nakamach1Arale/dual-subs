<div align="center">

# DualSubs · Android 全局实时双语字幕

**为 Android 上的任何视频、直播或网页播放器提供实时双语悬浮字幕**

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(ARM64)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![STT](https://img.shields.io/badge/STT-sherpa--onnx%20%7C%20Soniox-blue)](#语音识别-stt)
[![LLM](https://img.shields.io/badge/Translation-Claude%20%7C%20OpenAI%20Compatible-orange)](#ai-翻译)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

**简体中文** | **[English](README_EN.md)**

*捕获系统内录音频 → 实时语音识别（本地离线 / 云端流式） → LLM 上下文翻译 → 全局悬浮字幕*

</div>

---

**DualSubs** 可以在 Android 手机上为**任何视频、直播或网页播放器**提供实时双语字幕：直接捕获其他应用正在播放的系统声音，经本地离线（`sherpa-onnx`）或云端（`Soniox`）语音识别后，交由大语言模型（LLM）结合上下文翻译，并以悬浮窗形式叠加在播放器上方。

```text
其他 App 播放的声音 ──AudioPlaybackCapture──▶ 语音识别 (STT)
                                            ├─ 本地离线：Silero VAD 切句 + sherpa-onnx（默认，免费）
                                            └─ 云端流式：Soniox WebSocket 实时识别（可选，低延迟）
                                            ▼
                                 LLM 上下文翻译（Claude / DeepSeek / Groq 等）
                                            ▼
                               全局悬浮字幕窗（覆盖在全屏视频之上）
```

## ✨ 核心特性

- **真正全局通用**：基于系统级音频捕获（`AudioPlaybackCapture`），不绑定特定平台——无论是浏览器网页、视频客户端还是直播 App 均可使用。
- **无视画面 DRM 限制**：仅捕获音频流，不录制屏幕画面，因此不受视频 App 的防录屏/黑屏（DRM）机制影响，且系统开销更低。
- **端云双语音引擎**：默认内置免费、无网络依赖的本地离线识别（`Silero VAD` + `sherpa-onnx`），同时支持高精度的 `Soniox` 云端流式识别。
- **智能上下文翻译**：支持 Claude 官方 SDK 及任意 OpenAI 兼容接口（DeepSeek、Groq 等），每次翻译自动附带前文语境，告别生硬机翻。
- **舒适的阅读防抖**：精心设计字幕驻留时间、最短显示时长与空闲自动隐藏机制，避免新旧字幕闪烁跳变或遮挡视频画面。
- **隐私安全**：API Key 仅保存在手机本地应用私有存储中（且已禁用系统备份），绝不上传第三方服务器。

---

## 🎙️ 语音识别 (STT)

支持在**本地离线**与**云端流式**两种引擎间自由切换：

| 引擎 | 特性说明 |
| :--- | :--- |
| **本地离线（默认）** | 基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)。模型在 App 内按需下载，下载后**完全离线、零费用**。说话过程中每秒刷新灰色实时预览，句末停顿后约 1 秒出最终结果。 |
| **Soniox 云端** | 延迟约 1 秒，识别准确率更高，支持多语种自动混合识别。按音频时长计费（约 \$0.12/小时），需配置 [Soniox](https://soniox.com) API Key。 |

### 可选离线模型
应用内可直接下载以下 INT8 量化模型（托管于 HuggingFace，国内网络自动优先走 `hf-mirror.com` 镜像，支持断点续传）：

| 模型 | 适用语言与特点 | 体积 |
| :--- | :--- | :--- |
| [ReazonSpeech k2 v2](https://huggingface.co/reazon-research/reazonspeech-k2-v2) | **日语**（基于日本电视节目音频训练，适合生肉番剧/综艺/直播） | ~170 MB |
| [ReazonSpeech k2 v2 ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en) | **日语 + 英语**（支持日英夹杂场景） | ~77 MB |
| [SenseVoice](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09) | **中 / 英 / 日 / 韩 / 粤**（多语种自动检测，自带标点恢复） | ~240 MB |

> [!TIP]
> **语言设置建议**：可在设置中选择「自动识别」，或手动固定为日语、中文、英语、韩语、粤语。固定源语言不仅能提升 SenseVoice 和 Soniox 的识别准确率，也会作为提示词告知翻译模型。
> 
> **扩展自定义模型**：如需添加其他模型，只需在 `ModelCatalog.kt` 中追加配置项即可（凡 `sherpa-onnx` 支持的 Transducer / SenseVoice 模型均可直接兼容）。

---

## 🌐 AI 翻译

目标语言可在设置中自定义（默认为**简体中文**）。每次请求会自动携带前几句字幕作为上下文，保证代词指代与断句连贯。

| 翻译服务 | 配置说明 |
| :--- | :--- |
| **Claude**（默认 `claude-opus-5-5`） | 使用官方 Anthropic Java SDK，默认启用低 `effort` 模式以降低延迟；触发安全分类拦截时自动回退至备用模型。也可改为 `claude-haiku-5-5` 以获得极致速度与更低成本。 |
| **OpenAI 兼容接口** | 支持任意兼容 OpenAI 格式的服务商。<br>• **DeepSeek**：Base URL `https://api.deepseek.com`，模型填 `deepseek-chat`<br>• **Groq**：Base URL `https://api.groq.com/openai/v1` |
| **不翻译（仅字幕）** | 关闭翻译，仅作为实时语音转写字幕使用。 |

---

## 💬 字幕显示与交互

为提升观看体验，悬浮窗针对实时流式字幕做了多项防抖与可读性优化：

- **同屏句数**：支持同时显示 1–3 句（默认 2 句）。
- **防闪烁驻留（最短 4 秒）**：译文生成后至少在屏幕上保留 4 秒，即使新句子快速涌入也不会被立刻顶掉。
- **自动过期隐藏**：译文显示满指定时间后自动淡出（默认 15 秒；设为 `0` 则常驻直至被新句替换）。无字幕时悬浮窗自动完全隐藏，不遮挡视频。
- **长句强制切分**：本地离线识别模式下，若连续说话无停顿满 10 秒，将自动强制切分并送交翻译，避免字幕长时间卡顿。

---

## 🚀 快速开始

### 系统要求
- **Android 10 (API 29)** 及以上的 ARM 架构设备。

### 使用步骤
1. 安装并打开 APK。
2. **配置语音识别**：若使用本地离线模式，请选择一个模型并点击 **下载**（下载期间请保持 App 在前台运行）。
3. **配置翻译服务**：选择翻译引擎并填入对应的 API Key，或选择「不翻译」。
4. 点击 **开始字幕**，按系统提示依次授予必要权限：
   - **悬浮窗权限**（显示在其他应用上层）
   - **录音权限**（Android 系统规定内录系统音频同样需要此权限）
   - **屏幕共享授权**：建议选择 **整个屏幕**（若选“单个应用”，请指定目标播放器 App）。*注：本应用仅捕获音频通道，绝不录制画面。*
5. 切换到任意视频、直播或浏览器开始播放。
6. **操控悬浮窗**：
   - **单击字幕框**：呼出快捷控制栏（`A−` / `A+` 调节字号、显隐原文、清空当前字幕、`✕` 关闭）。
   - **上下拖动**：自由调整字幕垂直位置。
7. **停止服务**：点击通知栏中的「停止」，或点击悬浮窗菜单上的 `✕`。

---

## 🛠️ 编译构建

### 环境依赖
- **JDK 17**
- **Android SDK**（Platform 35）

### 构建命令
```bash
# Linux / macOS
./gradlew assembleDebug

# Windows
build.cmd
```

- **自动拉取依赖**：首次构建时，Gradle 会自动从 GitHub Releases 下载 `sherpa-onnx` 的 AAR 依赖并放置于 `app/libs/`。
- **国内网络友好**：`settings.gradle.kts` 已配置优先使用阿里云 Maven 镜像（官方仓库兜底），中国大陆网络环境下可直接顺畅构建。
- **产物路径**：编译生成的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

---

## ⚠️ 已知限制与排查

- **部分 App 无法捕获声音（一直显示“等待语音…”）**：
  部分应用在音频属性中显式声明了禁止被其他应用内录（`ALLOW_CAPTURE_BY_NONE`）。遇到此类情况，可在 DualSubs 设置中将音源切换为 **外部音源（麦克风收音）**，并开启手机外放使用。
- **无说话人区分**：当前版本暂不支持区分多人对话的角色标签（Speaker Diarization）。
- **发热与性能保护**：本地离线识别属于计算密集型任务，长时间运行会有一定耗电与发热。若旧机型推理速度跟不上实时语速，系统最多缓冲 10 秒音频，超出部分会自动丢弃最旧的片段以保证字幕实时性。

---

## 📂 项目结构

| 文件 | 职责说明 |
| :--- | :--- |
| `MainActivity.kt` | 主设置界面、模型下载管理、运行时权限与 MediaProjection 授权流程 |
| `SubtitleService.kt` | 核心前台服务：串联音频采集、语音识别、短句合并、LLM 翻译与悬浮窗渲染 |
| `AudioCapture.kt` | 16 kHz 单声道 PCM 音频采集器（支持系统内录与麦克风外录双音源） |
| `SttEngine.kt` | 语音识别引擎统一抽象接口 |
| `LocalSttEngine.kt` | 本地离线引擎实现：集成 Silero VAD 语音活动检测与 sherpa-onnx 实时预览 |
| `ModelCatalog.kt` | 离线模型清单与下载器（支持断点续传、镜像自动回退、文件大小校验） |
| `SonioxClient.kt` | Soniox WebSocket 云端实时流式识别客户端 |
| `Translator.kt` | 翻译模块：封装 Claude 官方 SDK 与通用 OpenAI 兼容接口 |
| `SubtitleOverlay.kt` | 全局可拖拽悬浮字幕窗，负责字幕排队、最短驻留与过期销毁逻辑 |

---

## 📄 许可证与致谢

本项目基于 [MIT License](LICENSE) 开源。

### 第三方开源组件
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**（Apache-2.0）：构建时自动下载引入。
- **[Silero VAD](https://github.com/snakers4/silero-vad)**（MIT）：内置于 `app/src/main/assets/silero_vad.onnx`。
- **语音识别模型**：由用户在 App 内按需下载，各模型权重分别遵循其原始仓库的许可证条款。