# Qiki Widgets

面向小米 18 Pro / 澎湃 OS 4.0.11.0 的组件中心 App，采用“一个宿主 App + 多个桌面小组件”的长期架构。

## 当前状态

- 已建立 Kotlin + Jetpack Compose 的 Android 工程骨架
- 已加入 AirPods 电量示例组件，用于验证桌面添加、BLE 扫描、刷新和点击回到宿主 App
- 已预留宿主 App 与独立小组件模块的演进边界
- 已接入标准 Android `SampleWidgetProvider`，等待 HyperOS 4.0 真机能力调研
- 当前工作区未安装 Java、Gradle 或 Android SDK，暂未完成构建验证

## 本地开发前置条件

1. JDK 17
2. Android SDK Platform 35 和对应 Build Tools
3. 一台小米 18 Pro 真机用于桌面组件验证

本项目包含 Gradle Wrapper，可以直接使用命令行构建，不要求安装或使用 Android Studio：

```bash
export JAVA_HOME=/Users/mac142/Library/Java/temurin-17
export ANDROID_HOME=/Users/mac142/Library/Android/sdk
./gradlew assembleDebug
```

首次构建会从 Maven Central 和 Google Maven 下载依赖。

## 目录约定

- `app/`：宿主 App、设置和组件入口
- `docs/`：平台调研、产品规范和发布记录
- `widget-*`：未来按组件拆分的独立模块

## 当前示例组件

安装后可在系统桌面的小组件列表中找到“AirPods 电量”。首次打开 App 时需要授予附近设备和通知权限；组件会监听 AirPods 的 BLE 广播并显示左右耳与充电盒电量。

AirPods 电量读取依赖 Apple 未公开的 BLE 广播格式，不保证所有型号、固件或 HyperOS 版本都能读取。无法读取时组件会显示等待状态，不会伪造电量。
