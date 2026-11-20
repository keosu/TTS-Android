# MOSS TTS Nano Android

基于 [OpenMOSS/MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano) 的 Android 端侧语音合成应用。采用 ONNX Runtime 在设备本地进行推理，无需联网即可完成语音合成和声音克隆。

## 功能特性

- 完全离线运行，保护隐私
- 内置多种预设音色
- 支持声音克隆（录制/导入参考音频）
- 支持多个克隆音色管理与自定义命名
- 合成音频保存与历史记录
- 支持模型导入导出
- 多镜像源下载
- 深色模式适配
- Material 3 设计

## 系统要求

- Android 8.0 (API 26) 及以上
- 至少 2GB RAM
- 推荐 4GB 以上存储空间（用于模型文件）

## 下载安装

从 [Releases](https://github.com/keosu/MOSS-TTS-Nano-Android/releases) 页面下载最新 APK。

首次启动后，在「模型管理」页面下载模型文件（约 700MB）。

## 作为系统 TTS 引擎使用

需要安装 **v1.0.9 或更新版本**；上游 v1.0.8 的 Release APK 尚未包含系统 TTS 服务。

1. 在应用内下载或导入模型，并确认可以正常合成语音。
2. 在应用「设置」页点击「打开系统文字转语音设置」。也可以在手机系统设置中搜索「文字转语音」或「TTS」。
3. 将「首选引擎」切换为「MOSS TTS 本地引擎」，选择中文（中国）并点击试听。

系统设置中的引擎配置按钮会打开应用设置；「安装语音数据」会打开模型管理。
系统 TTS 接口当前提供中文语音，模型在首次朗读时自动加载。

## Edge TTS 在线系统引擎

仓库中的 `edgeapp` 模块提供独立的 **Edge TTS 在线引擎**，包名为 `com.mosstts.edge`。
它和 MOSS 的 `com.mosstts.app` 可同时安装，并在 Android 系统的「首选引擎」中分别选择。
Android 以包名识别 TTS 引擎，所以两个引擎使用不同 APK。Edge APK 不包含 ONNX 或 MOSS 模型。

1. 安装 `edgeapp-release.apk`，打开 **Edge TTS**。
2. 选择默认音色；首次打开自动刷新完整在线列表，网络不可用时保留缓存和内置常用声音。
3. 可以选择晓晓、晓伊、云希、云健、台湾中文、香港粤语等；刷新后提供服务端返回的全部语言和声音。
4. 调整默认语速、音调并点击「试听」。阅读器传入的语速和音调与默认值相乘，音量由 Android 控制。
5. 点击「打开系统文字转语音设置」，将首选引擎切换为 **Edge TTS 在线引擎**。

Edge 需要网络，朗读文本直接发送至 Microsoft 的 `speech.platform.bing.com`，
无需 API Key，不经过 `edge-tts.com` 网站或项目自建服务器。
这是独立客户端，使用 Edge Read Aloud 的非官方接口，服务端协议变更可能影响可用性。

实现采用原生 Kotlin + OkHttp，包含 Sec-MS-GEC v2、403 服务器时间校正重试、
SSML 转义、长文本分段、WebSocket 音频帧解析和超时/停止处理。
MP3 通过 Android MediaCodec 解码为 24kHz 单声道 PCM16，再交给系统 TTS 播放、排队和控制音量。
当前不提供 WordBoundary / SentenceBoundary 高亮和 Azure 情感风格。

`EdgeGec.kt` 改编自 LGPLv3 的 Python `edge-tts` 实现；许可文本随 APK 打包，
详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

GitHub Actions 的 `main`、`master`、`feature/**` 分支构建和手动构建生成 Actions 附件；
`edge-tts-release` 附件包含 Edge APK，`release-universal` 包含 MOSS APK。
推送 `v*` 标签才会发布新的 Release APK，Release 包含两个应用。

从上游 APK 切换到 fork 发布的 APK 时，签名可能不同。如果系统拒绝覆盖安装，请先在旧版「模型管理」中导出模型，再卸载旧版、安装 fork 版本并导入模型。

## 构建

```bash
git clone https://github.com/keosu/MOSS-TTS-Nano-Android.git
cd MOSS-TTS-Nano-Android
./gradlew assembleRelease
```

需要 Android SDK 34 和 JDK 17 或 21。MOSS 构建产物位于 `app/build/outputs/apk/release/`，
Edge 构建产物位于 `edgeapp/build/outputs/apk/release/`。

只构建 Edge：

```bash
./gradlew :edgeapp:testDebugUnitTest :edgeapp:assembleRelease :edgeapp:lintDebug
```

Windows 可通过 Android Studio 构建，或设置 `JAVA_HOME` 后使用：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :edgeapp:assembleRelease
```

协议和网络异常测试使用本地 WebSocket 服务，不依赖公网。
要验证当前 Microsoft 接口，可设置 `EDGE_TTS_LIVE_TEST=1` 后运行 `:edgeapp:testDebugUnitTest --rerun-tasks`。
设备测试使用仓库中的真实 Edge MP3 样本，验证系统引擎发现、Voice API、语音数据检查、同步回调和 PCM 解码：

```bash
./gradlew :edgeapp:connectedDebugAndroidTest
```

GitHub Actions 会在 Android 29 模拟器运行这些设备测试。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- ONNX Runtime 端侧推理
- kotlinx.coroutines + OkHttp

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。

MOSS-TTS-Nano 模型版权归 OpenMOSS 团队所有。

## 致谢

- [OpenMOSS](https://github.com/OpenMOSS) - MOSS-TTS-Nano 模型
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) - 推理引擎
