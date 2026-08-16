package com.harl.oplusclipboardlagblocker;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XC_MethodHook.PRIORITY_HIGHEST;

public final class ClipboardLagBlocker implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "🛡️ OplusClipboardLagBlocker";
    private static final String SYSTEM_SERVER_PACKAGE = "android";
    private static final String SYSTEM_SERVER_PROCESS = "android";
    private static final String TARGET_PACKAGE = "com.oplus.appplatform";
    private static final String TARGET_PROVIDER =
            "com.oplus.appplatform.providers.ClipboardManagerProvider";
    private static final String CLIPBOARD_CONTROLLER =
            "com.android.server.clipboard.OplusClipboardController";
    private static final String CLASSIFICATION_HANDLER =
            "com.android.server.clipboard.OplusClipboardController$ClassificationHandler";
    private static final String CLASSIFIER_DELEGATE =
            "com.android.server.clipboard.classifier.AITextClassifierDelegate";
    private static final String CLASSIFIER_CALLBACK =
            "com.android.server.clipboard.classifier.IAITextClassifierCallback";
    private static final String CLIPBOARD_SERVICE_EXT =
            "com.android.server.clipboard.ClipboardServiceExtImpl";
    private static final String FRAMEWORK_CLIPBOARD_EXT =
            "android.content.ClipboardManagerExtImpl";
    private static final String CLASSIFICATION_METHOD = "startAIClassification";
    private static final String CLASSIFICATION_FORWARD_METHOD = "startAIClassificationLocked";
    private static final String CLASSIFICATION_HANDLER_METHOD = "handleMessage";
    private static final String CLASSIFIER_SEND_METHOD = "sendAITextClassification";
    private static final String PREPROCESS_METHOD = "onCommonSetPrimaryClipLocked";
    private static final String FRAMEWORK_PREPROCESS_METHOD = "checkBeforeSetPrimaryClip";
    private static final AtomicBoolean APP_PLATFORM_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean SYSTEM_SERVER_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_PREPROCESS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean PREPROCESS_HIT_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_PREPROCESS_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_METADATA_FAILURE_LOGGED =
            new AtomicBoolean(false);

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) {
        installFrameworkClipboardHook();
    }

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

    private static void installFrameworkClipboardHook() {
        if (!FRAMEWORK_PREPROCESS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> clipDataClass = XposedHelpers.findClass("android.content.ClipData", null);
            XposedHelpers.findAndHookMethod(
                    FRAMEWORK_CLIPBOARD_EXT,
                    null,
                    FRAMEWORK_PREPROCESS_METHOD,
                    String.class,
                    clipDataClass,
                    new XC_MethodHook(PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (FRAMEWORK_PREPROCESS_HIT_LOGGED.compareAndSet(false, true)) {
                                log("🧹 blocked app-side clipboard pre-processing (first hit)");
                            }
                            preserveBasicClipboardMetadata(param.args[0], param.args[1]);
                            param.setResult(null);
                        }
                    });
            log("✅ zygote hook installed: " + FRAMEWORK_CLIPBOARD_EXT + "."
                    + FRAMEWORK_PREPROCESS_METHOD);
        } catch (Throwable throwable) {
            FRAMEWORK_PREPROCESS_INSTALLED.set(false);
            log("⚠️ hook unavailable: " + FRAMEWORK_CLIPBOARD_EXT + "."
                    + FRAMEWORK_PREPROCESS_METHOD);
            XposedBridge.log(throwable);
        }
    }

    private static void preserveBasicClipboardMetadata(Object packageName, Object clipData) {
        if (clipData == null) {
            return;
        }
        try {
            Object clipDataWrapper = XposedHelpers.callMethod(clipData, "getClipDataWrapper");
            Object clipDataExt = XposedHelpers.callMethod(clipDataWrapper, "getExtImpl");
            XposedHelpers.callMethod(clipDataExt, "setPrimaryClipPackage", packageName);
        } catch (Throwable throwable) {
            if (FRAMEWORK_METADATA_FAILURE_LOGGED.compareAndSet(false, true)) {
                log("⚠️ basic clipboard package preservation failed");
                XposedBridge.log(throwable);
            }
        }
    }

    private static void installSystemServerHooks(ClassLoader classLoader) {
        boolean preprocessHooked = hookPreprocessMethod(classLoader);
        boolean controllerHooked = hookClassificationMethod(
                classLoader, CLIPBOARD_CONTROLLER, CLASSIFICATION_METHOD);
        boolean serviceForwardHooked = false;
        if (!controllerHooked) {
            serviceForwardHooked = hookClassificationMethod(
                    classLoader, CLIPBOARD_SERVICE_EXT, CLASSIFICATION_FORWARD_METHOD);
        }
        boolean pendingMessageHooked = hookClassificationHandler(classLoader);
        boolean classifierSendHooked = hookClassifierSend(classLoader);

        if (!preprocessHooked && !controllerHooked && !serviceForwardHooked
                && !pendingMessageHooked && !classifierSendHooked) {
            SYSTEM_SERVER_INSTALLED.set(false);
            log("❌ no system_server clipboard hook installed");
            return;
        }

        log("✅ system_server hooks installed; preprocess=" + preprocessHooked
                + ", controller=" + controllerHooked
                + ", serviceForwardFallback=" + serviceForwardHooked
                + ", pendingMessage=" + pendingMessageHooked
                + ", classifierSend=" + classifierSendHooked);
    }

    private static boolean hookPreprocessMethod(ClassLoader classLoader) {
        try {
            Class<?> contextClass = XposedHelpers.findClass("android.content.Context", classLoader);
            Class<?> clipDataClass = XposedHelpers.findClass("android.content.ClipData", classLoader);

            XposedHelpers.findAndHookMethod(
                    CLIPBOARD_SERVICE_EXT,
                    classLoader,
                    PREPROCESS_METHOD,
                    contextClass,
                    Boolean.TYPE,
                    clipDataClass,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (PREPROCESS_HIT_LOGGED.compareAndSet(false, true)) {
                                log("🧹 blocked clipboard pre-processing (first hit)");
                            }
                            param.setResult(null);
                        }
                    });
            log("✅ hook installed: " + CLIPBOARD_SERVICE_EXT + "." + PREPROCESS_METHOD);
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + CLIPBOARD_SERVICE_EXT + "." + PREPROCESS_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
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
                            param.setResult(null);
                        }
                    });
            log("✅ hook installed: " + className + "." + methodName);
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + className + "." + methodName);
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static boolean hookClassificationHandler(ClassLoader classLoader) {
        try {
            Class<?> messageClass = XposedHelpers.findClass("android.os.Message", classLoader);

            XposedHelpers.findAndHookMethod(
                    CLASSIFICATION_HANDLER,
                    classLoader,
                    CLASSIFICATION_HANDLER_METHOD,
                    messageClass,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
            log("✅ hook installed: " + CLASSIFICATION_HANDLER + "."
                    + CLASSIFICATION_HANDLER_METHOD);
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + CLASSIFICATION_HANDLER + "."
                    + CLASSIFICATION_HANDLER_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static boolean hookClassifierSend(ClassLoader classLoader) {
        try {
            Class<?> callbackClass = XposedHelpers.findClass(CLASSIFIER_CALLBACK, classLoader);

            XposedHelpers.findAndHookMethod(
                    CLASSIFIER_DELEGATE,
                    classLoader,
                    CLASSIFIER_SEND_METHOD,
                    CharSequence.class,
                    Long.TYPE,
                    callbackClass,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
            log("✅ hook installed: " + CLASSIFIER_DELEGATE + "." + CLASSIFIER_SEND_METHOD);
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + CLASSIFIER_DELEGATE + "." + CLASSIFIER_SEND_METHOD);
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
                            param.setResult(null);
                        }
                    });

            log("✅ appplatform hook installed in " + processName);
        } catch (Throwable throwable) {
            APP_PLATFORM_INSTALLED.set(false);
            log("❌ appplatform hook installation failed");
            XposedBridge.log(throwable);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " | " + message);
    }
}
