# OplusClipboardLagBlocker

用于 ColorOS / realme Android 16 的 Xposed 剪贴板卡顿拦截模块。

## 作用

模块提供两层剪贴板拦截：

1. 在 `system_server` 中拦截 `ClipboardServiceExtImpl.onCommonSetPrimaryClipLocked(Context, boolean, ClipData)`，阻断剪贴板写入后的 `ClipData.toString()`、调用方哈希计算和统计上报。
2. 拦截 `OplusClipboardController.startAIClassification(...)`，并保留 `ClipboardServiceExtImpl.startAIClassificationLocked(...)` 回退入口。
3. 在 `com.oplus.appplatform` 中拦截 `ClipboardManagerProvider.addPrimaryClipChangedListener(...)`，作为应用平台监听链路的保险拦截。

以上均为入口级阻断，不修改系统设置或剪贴板内容。

## 已核对的 ROM 证据

- `ClipboardServiceExtImpl.onCommonSetPrimaryClipLocked(...)` 会读取 `ClipData` 扩展、调用 `ClipData.toString()`，把剪贴板内容保存到 `mPrimaryClip`。
- 随后计算调用方包名 SHA-256，并通过 `OplusStatistics.onCommon("SetPrimaryClip", ...)` 上报统计数据。
- `OplusClipboardController` 仍有 AI 分类 Handler 和远程分类服务链路；模块同时阻断该入口。
- `ClipboardManagerProvider` 会注册 `OnPrimaryClipChangedListener`，模块保留该 Hook 作为第二层保险。

模块不修改系统设置值，而是在已确认的入口处阻断处理，因此禁用模块即可恢复原行为。

## 使用条件

- 已解锁 Bootloader 或具备 root 权限的 Android 设备。
- 已安装支持传统 Xposed API 82 的 LSPosed / Xposed 管理器。
- 设备的系统框架类名和方法签名与本文档记录的 ColorOS Android 16 版本一致或兼容。

## 安装与启用

1. 安装 Release APK。
2. 在 LSPosed 中启用「Oplus剪贴板卡顿拦截」。
3. 作用域会由模块推荐为：
   - `system`（legacy 元数据使用 `android` 包名，由 LSPosed 映射为系统框架）
   - `com.oplus.appplatform`
4. 重启设备，或确保 `system_server` 与 `com.oplus.appplatform` 在启用模块后重新启动。

旧进程中已经存在的处理状态或监听对象不会被模块枚举清除；重启对应进程后才会完全应用新的 Hook。

## 构建

```bash
./gradlew :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

当前工程的 Release 构建沿用 Android Gradle Plugin 的 debug 签名，适合个人设备安装和验证，不代表正式发布签名。

## 验证

启用模块并重启后，可查看带醒目前缀的 Xposed 日志：

```bash
adb logcat -d | grep -F '🛡️ OplusClipboardLagBlocker'
```

可只读查看系统开关当前值：

```bash
adb shell settings get secure clipboard_ai_smart_protection
```

模块日志应出现系统框架 Hook 和应用平台保险 Hook 的安装记录；复制文本后不应再触发被拦截方法的原始预处理和分类逻辑。是否完全消除卡顿仍需在目标设备上以相同文本和前台应用复测。

## 回滚

- 在 LSPosed 中关闭本模块并重启设备；或
- 卸载模块 APK 并重启设备。

模块不会写入 `clipboard_ai_smart_protection`，也不会修改系统 APK、剪贴板内容或用户数据。关闭后系统原有剪贴板 AI 分类、预处理和应用平台监听逻辑会恢复。

## 风险与兼容性

- 拦截系统预处理和分类会同时停用 ColorOS 的剪贴板敏感内容标记和相关推荐能力，不只是降低耗时。
- 应用平台保险 Hook 会关闭依赖该 Provider 的剪贴板变化回调。
- ColorOS 更新可能修改类名、参数或分类链路；Hook 失败时模块只记录日志，不应阻塞系统启动。
- 该模块针对已确认的 Oplus/ColorOS 实现，不保证适用于 AOSP、其他厂商 ROM 或不同 ColorOS 大版本。

## 许可

本仓库暂未指定开源许可证。除非另有授权，代码使用和再分发按著作权法处理。
