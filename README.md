# OplusClipboardLagBlocker

用于 ColorOS / realme Android 16 的 Xposed 剪贴板卡顿拦截模块。

## 作用

模块提供四层剪贴板拦截：

1. 在 Zygote 阶段拦截框架类 `android.content.ClipboardManagerExtImpl.checkBeforeSetPrimaryClip(String, ClipData)`，跳过应用侧复制前的调用栈采集、写入路径遍历与规则匹配；保留来源包名写入，不主动重写已有的 `ClipDataExt` 用户写入标记。
2. 在 `system_server` 中拦截 `ClipboardServiceExtImpl.onCommonSetPrimaryClipLocked(Context, boolean, ClipData)` 与 `OplusClipboardController.updateClipboardOpRecord(String, int, boolean)`，阻断复制后的同步文本统计、调用方哈希计算、遥测上报和写入记录。
3. 拦截 ColorOS AI 分类链路：`OplusClipboardController.startAIClassification(...)`、`ClipboardServiceExtImpl.startAIClassificationLocked(...)`、`OplusClipboardController.handleAIClassification(...)`、`OplusClipboardController$ClassificationHandler.handleMessage(Message)` 与 `AITextClassifierDelegate.sendAITextClassification(...)`；分类发送 Hook 同时兼容带 `long` 时间戳和旧版无时间戳签名。保留 `com.oplus.appplatform` 监听 Hook 作为兼容保险。
4. 在 `com.coloros.colordirectservice` 中通过 DexKit 按包范围、方法签名、行为字符串和唯一候选约束解析 TextIntent 剪贴板入口；同时以稳定的 `ClipboardScene.detect(IntentInput, IntentOutput)` 与 `OIntentApi.detect(...)` 的 scene/type-list 重载作为回退入口，阻断复制内容进入异步意图识别、AI/NLP 和流动胶囊链路。

以上均为入口级阻断，不修改系统设置或剪贴板正文；应用侧 Hook 会跳过 ColorOS 的复制来源规则识别。

## 已核对的 ROM 证据

- `ClipboardManagerExtImpl.checkBeforeSetPrimaryClip(...)` 位于普通应用复制前路径，会读取写入路径规则、调用 `Thread.currentThread().getStackTrace()` 并遍历规则；这是应用侧卡顿候选。
- `com.coloros.colordirectservice` 的 TextIntent 入口会切换到 `Dispatchers.IO` 并启动协程；协程随后调用 OIntent 剪贴板检测并按检测结果继续处理。这是复制后异步识别链路，模块不再把混淆类名或方法名作为跨版本依据。
- `ClipboardScene.detect(...)` 是 `ClipboardScene` 自身声明的公开实例方法；`OIntentApi.detect(...)` 有 `IntentScene.CLIPBOARD` 与 `IntentType[]` 重载。模块只对剪贴板场景或包含 `TOKEN` 的类型列表返回空列表，不影响其他意图场景。
- `ClipboardScene` 的 `specialCheck(String)` 会匹配 URL、`http`、货币符号、支付宝等特征；`detect(...)` 随后执行多类意图检测。这是此前版本未覆盖的独立复制后消费者，当前版本在 TextIntent、Scene 和 API 三个入口阻断。
- `ClipboardServiceExtImpl.onCommonSetPrimaryClipLocked(...)` 是 ColorOS 扩展的复制后处理：读取扩展、调用 `ClipData.toString()`、保存统计字符串、计算调用方 SHA-256 并上报 `SetPrimaryClip`；它不是标准剪贴板保存本身。
- `OplusClipboardController.updateClipboardOpRecord(...)` 是复制写入记录入口；当前版本只拦截控制器入口，不拦截其内部状态记录器，避免破坏后续用于判断用户显式设置的状态。
- `OplusClipboardController.startAIClassification(...)` 受 `oplus.software.clipboard_ai_protect` 与 `clipboard_ai_smart_protection` 双重条件控制，随后向 `com.oplus.securitypermission` 的分类服务发送文本；当前版本同时拦截分类启动、内部 `handleAIClassification` 咽喉、分类 Handler 和发送代理。
- `ClipboardManagerProvider` 会注册 `OnPrimaryClipChangedListener`，模块保留该 Hook 作为应用平台保险。
- 已采集设备上该 Provider 属于 `com.oplus.appplatform`，但实际运行在 `system` 进程；模块同时尝试 system 进程与普通 appplatform 进程的对应 ClassLoader。
- `hookGetPrimaryClipResult(...)` 及其 `clipboardAccessResult(...)` 属于粘贴/读取路径，不与复制写入路径混同；本版本不默认禁用读取权限、敏感内容和剪贴板规则判断。

## 使用条件

- 已解锁 Bootloader 或具备 root 权限的 Android 设备。
- 已安装支持传统 Xposed API 82 的 LSPosed / Xposed 管理器。
- 设备的系统框架类名和方法签名与本文档记录的 ColorOS Android 16 版本一致或兼容。

## 安装与启用

1. 安装 Release APK。
2. 在 LSPosed 中启用「Oplus剪贴板卡顿拦截」。
3. 保持 `android`、`system`、`com.oplus.appplatform` 与 `com.coloros.colordirectservice` 作用域；其中 `system` 用于覆盖实际承载 `ClipboardManagerProvider` 的系统进程。框架类的 Zygote Hook 会在包含 ColorOS `ClipboardManagerExtImpl` 的应用进程中生效，应用平台保险 Hook 会优先覆盖实际承载 Provider 的 `system` 进程，ColorDirectService Hook 则在其自身进程中生效。
4. 重启设备，或确保相关应用进程、`system_server`、`system`、`com.oplus.appplatform` 与 `com.coloros.colordirectservice` 在启用模块后重新启动。

旧进程中已经存在的处理状态或监听对象不会被模块枚举清除；重启相关进程后才会完全应用新的 Hook。

## 构建

```bash
./gradlew :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

当前工程的 Release 构建沿用 Android Gradle Plugin 的 debug 签名，适合个人设备安装和验证，不代表正式发布签名。

启用模块并重启后，可查看带醒目前缀的 Xposed 日志：

```bash
adb logcat -d | grep -F '🛡️ OplusClipboardLagBlocker'
```

应看到 Zygote、`system_server`、`system`/`com.oplus.appplatform` 和 `ColorDirectService` 的安装结果，以及首次复制后的相应 `blocked ... (first hit)` 日志；`system_server` 安装行还会报告 `total=N`。复制文本后不应再执行应用侧写入路径规则匹配、系统侧预处理、分类排队、分类服务发送、写入记录或 ColorDirectService 的 TextIntent 识别。是否完全消除卡顿仍需在目标设备上以相同文本和前台应用复测。

## 回滚

- 在 LSPosed 中关闭本模块并重启设备；或
- 卸载模块 APK 并重启设备。

模块不会写入 `clipboard_ai_smart_protection`，也不会修改系统 APK、剪贴板内容或用户数据。关闭后系统原有剪贴板 AI 分类、预处理和应用平台监听逻辑会恢复。

## 风险与兼容性

- ColorDirectService 启动时会在其加载回调内同步初始化 DexKit，并使用单线程/单并发查询；这可能增加该进程启动阶段的时间和内存峰值。DexKit 无法加载、初始化失败或候选不唯一时，不会猜测或安装混淆 TextIntent 入口，仅保留已命名且精确签名的 `ClipboardScene.detect(...)` 与 `OIntentApi.detect(...)` Scene/API 回退；模块捕获初始化与解析异常，不应因 DexKit 失败阻塞系统启动。
- ColorDirectService Hook 在 DexKit 唯一解析成功时阻断语义 TextIntent 剪贴板入口，并始终尝试阻断 `ClipboardScene.detect(...)` 及剪贴板场景/`TOKEN` 类型列表的 `OIntentApi.detect(...)` 重载；DexKit 不可用时，TextIntent 入口层可能未覆盖，这是为避免混淆类名/方法名误拦其他逻辑而保留的明确边界。
- 应用平台保险 Hook 会关闭依赖该 Provider 的剪贴板变化回调；已确认该 Provider 可能在 `system` 进程中运行，模块会沿当前 ClassLoader 及其父加载器链尝试安装；当前工作环境未完成重启后的实际加载回调复测，若目标 ROM 的 Provider 位于不可见的独立加载器中，该层会记录失败，不影响其他 Hook。
- 本版本不拦截 `hookGetPrimaryClipResult` 读取路径，因此不会主动绕过 ColorOS 的粘贴权限、敏感内容和剪贴板规则判断；如果实测卡顿发生在粘贴而非复制，需要单独评估读取路径 Hook。
- 本版本针对已确认的 Oplus/ColorOS 实现，不保证适用于 AOSP、其他厂商 ROM 或不同 ColorOS 大版本。

## 许可

本仓库暂未指定开源许可证。除非另有授权，代码使用和再分发按著作权法处理。
