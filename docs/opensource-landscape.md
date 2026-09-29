# 开源项目参考与合规边界

本项目可以借鉴公开项目的架构、交互模式和兼容性经验，但不能把“复刻付费小组件”理解为复制其代码、资源、接口或视觉资产。

## 优先参考

| 项目 | 参考价值 | 许可证 | 建议用法 |
| --- | --- | --- | --- |
| [AndroidX Glance](https://github.com/androidx/androidx/tree/androidx-main/glance) | 官方 Compose 风格组件 API、测试与底层实现 | Apache-2.0 | 作为技术基线，优先阅读官方 API 和限制 |
| [Prognoza](https://github.com/davidtakac/prognoza) | 天气数据、离线缓存、桌面组件和现代 Android 架构 | MIT | 研究数据层、缓存和组件状态 |
| [WiFi-Widget](https://github.com/w2sv/WiFi-Widget) | 可配置网络信息组件、系统权限和刷新行为 | GPL-3.0 | 只作为行为和兼容性参考，不直接复制代码进入闭源模块 |
| [Wireless ADB Switch](https://github.com/Smooth-E/wireless-adb-switch) | 多组件、快捷操作和系统能力边界 | GPL-3.0 | 研究组件入口和系统限制，不直接移植实现 |
| [Android Markdown Widget](https://github.com/Tiim/Android-Markdown-Widget) | 文件型数据源和简单 WidgetProvider 实现 | GPL-3.0 | 研究极简数据源与更新流程 |
| [WeatherGlanceWidget](https://github.com/PiotrPrus/WeatherGlanceWidget) | Jetpack Glance 的小型示例 | 需以仓库当前 LICENSE 为准 | 仅用于学习 API 组织方式 |

## 选型结论

- 组件底座优先采用 Android 标准 App Widget，并评估 Jetpack Glance 是否覆盖目标交互。
- 不把某一个第三方项目当作基础模板整体复制。
- 对 GPL 项目只做隔离式学习和行为验证；进入本项目的代码必须自行实现并完成许可证审查。
- MIT/Apache 项目也必须保留版权和许可证通知，不能因为“开源”就删除署名或限制条件。
- 无明确 LICENSE 的仓库默认视为不可直接复用代码、图片、字体和文案。

## 付费组件的合法替代路径

可以实现“相同需求”或“相同类别”的原创产品，例如重新设计一个倒计时、信息卡或设备状态组件；必须满足：

- 不复制付费产品的源代码、反编译产物或专有资源
- 不复用其品牌、图标、字体、文案、布局细节或独有动效
- 不绕过付费验证、授权校验、接口鉴权或服务端限制
- 不抓取对方未公开或受保护的数据接口
- 使用自己的产品定义、视觉设计、数据来源和实现方式

如果需要兼容公开协议或公开数据格式，应只实现公开规范，并记录来源和兼容范围。

