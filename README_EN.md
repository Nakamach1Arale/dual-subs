<div align="center">

# DualSubs · Real-Time Bilingual Subtitles for Android

**Live bilingual floating subtitles for any video, live stream, or web player on Android**

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(ARM64)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![STT](https://img.shields.io/badge/STT-sherpa--onnx%20%7C%20Soniox-blue)](#speech-to-text-stt)
[![LLM](https://img.shields.io/badge/Translation-Claude%20%7C%20OpenAI%20Compatible-orange)](#ai-translation)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

**[简体中文](README.md)** | **English**

*System Audio Capture → Real-Time STT (On-Device Offline / Cloud Streaming) → Context-Aware LLM Translation → Floating Overlay*

</div>

---

**DualSubs** brings real-time bilingual subtitles to **any video, live stream, or browser player** on Android. It captures internal audio played by other apps, transcribes speech (either offline on-device via `sherpa-onnx` or in the cloud via `Soniox`), translates it with an LLM using conversational context, and renders a sleek floating subtitle box right on top of your player.

```text
Audio from Other Apps ──AudioPlaybackCapture──▶ Speech-to-Text (STT)
                                                  ├─ On-Device (Default, Free): Silero VAD + sherpa-onnx
                                                  └─ Cloud Streaming (Optional): Soniox real-time WebSocket
                                                  ▼
                                   Context-Aware LLM Translation (Claude / DeepSeek / Groq, etc.)
                                                  ▼
                                   Floating Subtitle Overlay (Rendered above fullscreen video)
```

## ✨ Key Features

- **Universal Compatibility**: Works across browsers, video players, and live-streaming apps by capturing system playback audio (`AudioPlaybackCapture`) rather than hooking into specific apps.
- **DRM-Friendly**: Captures audio only—never video frames. Video DRM protections (which block screen recording with black screens) do not affect subtitle generation.
- **Dual STT Engines**: Runs 100% offline and free on-device by default (`Silero VAD` + `sherpa-onnx`), with optional `Soniox` cloud streaming for ultra-low latency and higher accuracy.
- **Context-Aware LLM Translation**: Supports the official Anthropic Claude SDK and any OpenAI-compatible API (DeepSeek, Groq, etc.). Previous lines are automatically passed as context for natural, coherent translations.
- **Flicker-Free Reading Experience**: Built-in minimum display duration, auto-expiration, and auto-hide when silent prevent rapid subtitle flickering or unnecessary screen obstruction.
- **Privacy First**: API keys are stored strictly on-device in private app storage (with Android cloud backup disabled).

---

## 🎙️ Speech-to-Text (STT)

Switch freely between **On-Device Offline** and **Cloud Streaming** engines:

| Engine | Description |
| :--- | :--- |
| **On-Device Offline (Default)** | Powered by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Models are downloaded on demand inside the app—**100% offline and free** afterward. Refreshes a gray live preview every second while speaking, finalizing results ~1s after a pause. |
| **Soniox Cloud** | ~1s latency, higher accuracy, and automatic multilingual code-switching. Billed by audio duration (~$0.12/hr); requires a [Soniox](https://soniox.com) API key. |

### Downloadable Offline Models
INT8 quantized models can be downloaded directly in the app (hosted on HuggingFace with automatic fallback to `hf-mirror.com` and resume support):

| Model | Languages & Highlights | Size |
| :--- | :--- | :--- |
| [ReazonSpeech k2 v2](https://huggingface.co/reazon-research/reazonspeech-k2-v2) (int8) | **Japanese** — Trained on Japanese TV broadcast audio; ideal for anime, variety shows, and streams | ~170 MB |
| [ReazonSpeech k2 v2 ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en) (int8) | **Japanese + English** — Handles bilingual code-switching | ~77 MB |
| [SenseVoice](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09) (int8) | **Chinese, English, Japanese, Korean, Cantonese** — Auto language detection with punctuation | ~240 MB |

> [!TIP]
> **Source Language Selection**: You can leave recognition on "Auto Detect" or lock it to Japanese, Chinese, English, Korean, or Cantonese. Locking the source language improves accuracy for both SenseVoice and Soniox, and informs the LLM of the source language during translation.
> 
> **Adding Custom Models**: To add more offline models, simply add an entry to `ModelCatalog.kt` (any transducer or SenseVoice model supported by `sherpa-onnx` works out of the box).

---

## 🌐 AI Translation

The target language is configurable in Settings (defaults to **Simplified Chinese**). Every translation request includes recent sentences as context.

| Service | Details |
| :--- | :--- |
| **Claude** (Default: `claude-opus-5-5`) | Uses the official Anthropic Java SDK with low `effort` to minimize latency, plus automatic fallback if a safety refusal occurs. Can be switched to `claude-haiku-5-5` for faster, cheaper output. |
| **OpenAI-Compatible API** | Works with any OpenAI-compatible endpoint:<br>• **DeepSeek**: Base URL `https://api.deepseek.com`, Model `deepseek-chat`<br>• **Groq**: Base URL `https://api.groq.com/openai/v1` |
| **No Translation** | Displays transcribed source captions only. |

---

## 💬 Subtitle Display & Controls

- **Max Visible Lines**: 1–3 sentences on screen simultaneously (default: 2).
- **Minimum Hold Time (4s)**: Once a translation appears, it stays visible for at least 4 seconds so incoming lines don't immediately push it off-screen.
- **Subtitle Retention**: Automatically fades out a line after a set duration (default: 15s; `0` = keep visible until pushed out by new lines).
- **Japanese Furigana**: Displays kana readings above Japanese kanji (powered by Kuromoji + IPADIC, 100% offline). Can be toggled in Settings or via the `注音` button in the overlay menu; when source language is set to "Auto Detect", only lines containing kana are annotated so Chinese text won't be mis-annotated. Dictionary-based readings may occasionally misread proper nouns or heteronyms.
- **Max Segment Length**: During offline recognition, continuous speech without pauses is automatically split at 10 seconds to keep subtitles flowing.
- **Auto-Hide**: The floating box hides completely when no subtitles are active.

---

## 🚀 Getting Started

### Requirements
- **Android 10 (API 29)** or higher on an **ARM** device.

### Usage
1. Download the latest APK from [Releases](https://github.com/Nakamach1Arale/dual-subs/releases) (or build from source below), then install and open it.
2. **Select STT Engine**: If using On-Device Offline mode, pick a model and tap **Download** (keep the app in the foreground while downloading).
3. **Select Translation Service**: Enter your API key, or choose **No Translation**.
4. Tap **Start Subtitles** and grant the required permissions:
   - **Display over other apps** (for the floating subtitle overlay)
   - **Record audio** (required by Android even for internal system audio capture)
   - **Screen capture prompt**: Choose **Entire screen** (or select the target video player app if choosing "Single app"). *Note: DualSubs only captures audio, never screen video.*
5. Switch to your video or live stream app.
6. **Overlay Controls**:
   - **Tap the subtitle box**: Toggle the quick action bar (`A−` / `A+` font size, source text toggle, furigana toggle `注音`, clear, `✕` close).
   - **Drag vertically**: Reposition the subtitle box on screen.
7. **Stop**: Tap **Stop** in the notification shade or `✕` on the subtitle overlay menu.

---

## 🛠️ Building from Source

### Prerequisites
- **JDK 17**
- **Android SDK** (Platform 35)

### Build Commands
```bash
# Linux / macOS
./gradlew assembleDebug

# Windows (debug / release)
build.cmd
build.cmd release
```

- On the first build, Gradle automatically downloads the `sherpa-onnx` AAR from GitHub Releases into `app/libs/`.
- The output APK is generated at `app/build/outputs/apk/debug/app-debug.apk` (or `app/build/outputs/apk/release/app-release.apk` for `build.cmd release`).
- Maven repositories prioritize Aliyun mirrors before falling back to official repos (see `settings.gradle.kts`) for smooth builds in Mainland China.

---

## ⚠️ Known Limitations

- **No Speaker Diarization**: Does not distinguish between multiple speakers.
- **Apps Blocking Audio Capture**: Some apps explicitly forbid third-party audio capture (`ALLOW_CAPTURE_BY_NONE`), causing the overlay to remain on *"Waiting for speech…"*. Workaround: switch the Audio Source in Settings to **External (Microphone)** and play audio over your device's speaker.
- **Battery & Thermal Load**: On-device inference is compute-intensive. On older devices where inference runs slower than real-time, the audio buffer caps at 10 seconds and drops the oldest frames to stay live.

---

## 📂 Project Structure

| File | Responsibility |
| :--- | :--- |
| `MainActivity.kt` | Settings UI, model downloads, runtime permissions, and MediaProjection flow |
| `Prefs.kt` | Language definitions and SharedPreferences persistence for all user settings |
| `SubtitleService.kt` | Foreground service orchestrating audio capture, STT, translation, and overlay updates |
| `AudioCapture.kt` | 16 kHz mono PCM recorder (supports internal playback capture and microphone input) |
| `SttEngine.kt` | Speech-to-Text engine abstraction interface |
| `LocalSttEngine.kt` | Offline STT implementation: Silero VAD + sherpa-onnx with real-time preview |
| `ModelCatalog.kt` | Downloadable model registry and downloader (HTTP range resume, mirror fallback, size verification) |
| `SonioxClient.kt` | Soniox WebSocket real-time streaming STT client |
| `Translator.kt` | Translation clients for Claude (official SDK) and OpenAI-compatible APIs |
| `SubtitleOverlay.kt` | Draggable floating subtitle window managing retention and expiration timers |
| `Furigana.kt` / `RubySpan.kt` | Japanese furigana annotation: Kuromoji tokenization, okurigana alignment, and ruby rendering above kanji |

---

## 📄 License & Credits

Licensed under the [MIT License](LICENSE).

### Third-Party Components
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** (Apache-2.0): Downloaded automatically at build time.
- **[Kuromoji](https://github.com/atilika/kuromoji)** (Apache-2.0) and its bundled **mecab-ipadic** dictionary (IPADIC License): Used for Japanese furigana annotation.
- **[Silero VAD](https://github.com/snakers4/silero-vad)** (MIT): Bundled at `app/src/main/assets/silero_vad.onnx`.
- **Speech Recognition Models**: Downloaded on demand by the user within the app; each model is governed by its original repository's license.
