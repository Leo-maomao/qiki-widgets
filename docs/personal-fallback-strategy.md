# 个人自用兜底方案

本项目不依赖小米应用商店，也不假设普通 APK 能获得小米签名权限。安装方式是本地构建后通过 ADB 旁加载。

## 数据源优先级

1. **HyperOS AirPods Provider**
   `content://com.android.bluetooth.ble.app.headsetdata.provider` 的 `getAirpodsState` 返回左右耳、充电盒和设备状态。真机验证过系统内部存在 1% 数据，但该 Provider 权限是 `signature|privileged`，普通 APK 通常会被拒绝。权限可用时才写入组件。
2. **BluetoothDevice metadata**
   部分 Android/HyperOS 版本会在已连接设备 metadata 中暴露左右耳/盒电量。代码只接受明确的组件字段，并且只针对已配对的 AirPods。
3. **AirPods BLE 广播**
   广播通常是 10% 档位、地址也可能随机，只用于识别型号/佩戴状态，不能作为 1% 电量来源，因此不会写入电量。
4. **普通 Bluetooth battery broadcast**
   只提供一个聚合值，无法区分左耳、右耳和充电盒，不写入任何组件槽位。

## 可信度规则

- 不把单一电量值当作充电盒电量。
- 不把附近设备的广播当作当前已连接设备。
- Provider/metadata 都不可用时保留上次可信缓存；从未成功读取时显示 `--`。
- 手动刷新只触发一次短扫描/读取，不启动常驻通知栏服务。

## 个人用户的操作路径

1. 打开 AirPods 盒盖并让耳机连接手机。
2. 打开一次 Qiki Widgets，授予“附近设备”权限。
3. 将组件添加到桌面。
4. 如果系统 Provider 对普通 APK 放行，组件显示左右耳和盒子的精确值；否则只显示曾经成功读取的缓存或 `--`。

## 后续可选增强

- 增加“诊断页”，显示当前数据源、权限拒绝原因、目标蓝牙地址和最后成功时间。
- 允许用户从已配对的 AirPods 列表中锁定目标设备，避免多个 AirPods 同时配对时选错。
- 对不同 HyperOS 版本做适配表；不通过反射或私有接口伪造 1% 数据。
