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

从 [Releases](https://github.com/cdyUuu/MOSS-TTS-Nano-Android/releases) 页面下载最新 APK。

首次启动后，在「模型管理」页面下载模型文件（约 700MB）。

## 作为系统 TTS 引擎使用

需要安装 **v1.0.9 或更新版本**；上游 v1.0.8 的 Release APK 尚未包含系统 TTS 服务。

1. 在应用内下载或导入模型，并确认可以正常合成语音。
2. 在应用「设置」页点击「打开系统文字转语音设置」。也可以在手机系统设置中搜索「文字转语音」或「TTS」。
3. 将「首选引擎」切换为「MOSS TTS 本地引擎」，选择中文（中国）并点击试听。

系统设置中的引擎配置按钮会打开应用设置；「安装语音数据」会打开模型管理。
系统 TTS 接口当前提供中文语音，模型在首次朗读时自动加载。

GitHub Actions 的普通分支构建和手动构建只生成 Actions 附件；推送 `v*` 标签才会发布新的 Release APK。

从上游 APK 切换到 fork 发布的 APK 时，签名可能不同。如果系统拒绝覆盖安装，请先在旧版「模型管理」中导出模型，再卸载旧版、安装 fork 版本并导入模型。

## 构建

```bash
git clone https://github.com/cdyUuu/MOSS-TTS-Nano-Android.git
cd MOSS-TTS-Nano-Android
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/release/`。

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
