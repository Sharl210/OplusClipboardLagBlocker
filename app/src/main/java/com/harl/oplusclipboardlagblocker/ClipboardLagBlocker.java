package com.harl.oplusclipboardlagblocker;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ClipboardLagBlocker implements IXposedHookLoadPackage {
    private static final String TAG = "OplusClipboardLagBlocker";
    private static final String SYSTEM_SERVER_PACKAGE = "android";
    private static final String SYSTEM_SERVER_PROCESS = "android";
    private static final String TARGET_PACKAGE = "com.oplus.appplatform";
    private static final String TARGET_PROVIDER =
            "com.oplus.appplatform.providers.ClipboardManagerProvider";
    private static final String CLIPBOARD_CONTROLLER =
            "com.android.server.clipboard.OplusClipboardController";
    private static final String CLIPBOARD_SERVICE_EXT =
            "com.android.server.clipboard.ClipboardServiceExtImpl";
    private static final String CLASSIFICATION_METHOD = "startAIClassification";
    private static final String CLASSIFICATION_FORWARD_METHOD = "startAIClassificationLocked";
    private static final AtomicBoolean APP_PLATFORM_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean SYSTEM_SERVER_INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (SYSTEM_SERVER_PACKAGE.equals(loadPackageParam.packageName)
                && SYSTEM_SERVER_PROCESS.equals(loadPackageParam.processName)
                && SYSTEM_SERVER_INSTALLED.compareAndSet(false, true)) {
            installSystemServerHooks(loadPackageParam.classLoader);
            return;
        }

        // 不绑定默认进程名，兼容ROM将Provider迁移到命名进程的情况。
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)
                || !APP_PLATFORM_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        installAppPlatformHook(loadPackageParam.classLoader, loadPackageParam.processName);
    }

    private static void installSystemServerHooks(ClassLoader classLoader) {
        boolean controllerHooked = hookClassificationMethod(
                classLoader, CLIPBOARD_CONTROLLER, CLASSIFICATION_METHOD);
        boolean serviceForwardHooked = false;
        if (!controllerHooked) {
            serviceForwardHooked = hookClassificationMethod(
                    classLoader, CLIPBOARD_SERVICE_EXT, CLASSIFICATION_FORWARD_METHOD);
        }

        if (!controllerHooked && !serviceForwardHooked) {
            SYSTEM_SERVER_INSTALLED.set(false);
            XposedBridge.log(TAG + ": no system_server clipboard classification hook installed");
            return;
        }

        XposedBridge.log(TAG + ": system_server hook installed; controller="
                + controllerHooked + ", serviceForwardFallback=" + serviceForwardHooked);
    }

    private static boolean hookClassificationMethod(
            ClassLoader classLoader, String className, String methodName) {
        try {
            Class<?> looperClass = XposedHelpers.findClass("android.os.Looper", classLoader);
            Class<?> clipDataClass = XposedHelpers.findClass("android.content.ClipData", classLoader);

            XposedHelpers.findAndHookMethod(
                    className,
                    classLoader,
                    methodName,
                    looperClass,
                    clipDataClass,
                    String.class,
                    Integer.TYPE,
                    Integer.TYPE,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // void入口提前返回，阻断Handler消息和远程分类请求。
                            param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + ": hook installed: " + className + "." + methodName);
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": hook unavailable: " + className + "." + methodName);
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static void installAppPlatformHook(ClassLoader classLoader, String processName) {
        try {
            Class<?> requestClass = XposedHelpers.findClass(
                    "com.oplus.epona.Request", classLoader);
            Class<?> callbackClass = XposedHelpers.findClass(
                    "com.oplus.epona.Call$Callback", classLoader);

            XposedHelpers.findAndHookMethod(
                    TARGET_PROVIDER,
                    classLoader,
                    "addPrimaryClipChangedListener",
                    requestClass,
                    callbackClass,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 阻止Provider注册系统剪贴板监听，切断第二条文本意图处理路径。
                            param.setResult(null);
                        }
                    });

            XposedBridge.log(TAG + ": appplatform hook installed in " + processName);
        } catch (Throwable throwable) {
            APP_PLATFORM_INSTALLED.set(false);
            XposedBridge.log(TAG + ": appplatform hook installation failed");
            XposedBridge.log(throwable);
        }
    }
}
