package com.harl.oplusclipboardlagblocker;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
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
    private static final String SYSTEM_APP_PROCESS = "system";
    private static final String TARGET_PACKAGE = "com.oplus.appplatform";
    private static final String COLOR_DIRECT_SERVICE_PACKAGE = "com.coloros.colordirectservice";
    private static final String TARGET_PROVIDER =
            "com.oplus.appplatform.providers.ClipboardManagerProvider";
    private static final String OINTENT_API_CLASS =
            "com.oplus.ointent.detect.OIntentApi";
    private static final String OINTENT_DETECT_METHOD = "detect";
    private static final String OINTENT_INTENT_SCENE =
            "com.oplus.ointent.detect.scene.IntentScene";
    private static final String OINTENT_INTENT_TYPE_ARRAY =
            "[Lcom.oplus.ointent.api.config.IntentType;";
    private static final String OINTENT_INTENT_OPTIONS =
            "com.oplus.ointent.api.base.IntentOptions";
    private static final String TEXT_INTENT_MANAGER =
            "com.oplus.textintent.manager.impl.a";
    private static final String TEXT_INTENT_ENTRY_METHOD = "K";
    private static final String CLIPBOARD_SCENE_CLASS =
            "com.oplus.ointent.detect.scene.ClipboardScene";
    private static final String CLIPBOARD_SCENE_DETECT_METHOD = "detect";
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
    private static final String CLASSIFICATION_DISPATCH_METHOD = "handleAIClassification";
    private static final String CLASSIFICATION_HANDLER_METHOD = "handleMessage";
    private static final String CLASSIFIER_SEND_METHOD = "sendAITextClassification";
    private static final String PREPROCESS_METHOD = "onCommonSetPrimaryClipLocked";
    private static final String CLIPBOARD_RECORD_METHOD = "updateClipboardOpRecord";
    private static final String FRAMEWORK_PREPROCESS_METHOD = "checkBeforeSetPrimaryClip";
    private static final AtomicBoolean APP_PLATFORM_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean APP_PLATFORM_SYSTEM_INSTALLED =
            new AtomicBoolean(false);
    private static final AtomicBoolean COLOR_DIRECT_SERVICE_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean OINTENT_DETECT_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean OINTENT_TYPE_LIST_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean CLIPBOARD_SCENE_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean TEXT_INTENT_ENTRY_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean CLIPBOARD_RECORD_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean CLASSIFICATION_DISPATCH_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean SYSTEM_SERVER_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_PREPROCESS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean PREPROCESS_HIT_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_PREPROCESS_HIT_LOGGED =
            new AtomicBoolean(false);
    private static final AtomicBoolean FRAMEWORK_METADATA_FAILURE_LOGGED =
            new AtomicBoolean(false);
    private static final Set<Method> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<Method>());

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

        // Provider可能归属于android或com.oplus.appplatform，但已证实例运行在system进程。
        if (SYSTEM_APP_PROCESS.equals(loadPackageParam.processName)
                && APP_PLATFORM_SYSTEM_INSTALLED.compareAndSet(false, true)) {
            installAppPlatformHook(loadPackageParam.classLoader,
                    loadPackageParam.processName + "/" + loadPackageParam.packageName,
                    APP_PLATFORM_SYSTEM_INSTALLED);
            return;
        }

        // 普通ROM中Provider仍可能运行在com.oplus.appplatform自身进程。
        if (TARGET_PACKAGE.equals(loadPackageParam.packageName)
                && APP_PLATFORM_INSTALLED.compareAndSet(false, true)) {
            installAppPlatformHook(loadPackageParam.classLoader, loadPackageParam.processName,
                    APP_PLATFORM_INSTALLED);
            return;
        }

        if (COLOR_DIRECT_SERVICE_PACKAGE.equals(loadPackageParam.packageName)
                && COLOR_DIRECT_SERVICE_INSTALLED.compareAndSet(false, true)) {
            installColorDirectServiceHook(
                    loadPackageParam.classLoader, loadPackageParam.processName);
        }
    }

    private static void installFrameworkClipboardHook() {
        if (!FRAMEWORK_PREPROCESS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            Class<?> targetClass = XposedHelpers.findClass(FRAMEWORK_CLIPBOARD_EXT, null);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    FRAMEWORK_PREPROCESS_METHOD,
                    new XC_MethodHook(PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (FRAMEWORK_PREPROCESS_HIT_LOGGED.compareAndSet(false, true)) {
                                log("🧹 blocked app-side clipboard pre-processing (first hit)");
                            }
                            preserveBasicClipboardMetadata(
                                    param.args.length > 0 ? param.args[0] : null,
                                    param.args.length > 1 ? param.args[1] : null);
                            param.setResult(null);
                        }
                    },
                    false,
                    "void",
                    "java.lang.String",
                    "android.content.ClipData");
            if (hooked == 0) {
                throw new NoSuchMethodException(
                        FRAMEWORK_CLIPBOARD_EXT + "." + FRAMEWORK_PREPROCESS_METHOD);
            }
            log("✅ semantic zygote hook installed: " + FRAMEWORK_CLIPBOARD_EXT + "."
                    + FRAMEWORK_PREPROCESS_METHOD + " (overloads=" + hooked + ")");
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
        boolean dispatchHooked = hookClassificationDispatchMethod(classLoader);
        boolean pendingMessageHooked = hookClassificationHandler(classLoader);
        boolean classifierSendHooked = hookClassifierSend(classLoader);
        boolean clipboardRecordHooked = hookClipboardRecordMethod(classLoader);

        if (!preprocessHooked && !controllerHooked && !serviceForwardHooked
                && !dispatchHooked && !pendingMessageHooked && !classifierSendHooked
                && !clipboardRecordHooked) {
            SYSTEM_SERVER_INSTALLED.set(false);
            log("❌ no system_server clipboard hook installed");
            return;
        }

        log("✅ system_server hooks installed; preprocess=" + preprocessHooked
                + ", controller=" + controllerHooked
                + ", serviceForwardFallback=" + serviceForwardHooked
                + ", dispatch=" + dispatchHooked
                + ", pendingMessage=" + pendingMessageHooked
                + ", classifierSend=" + classifierSendHooked
                + ", clipboardRecord=" + clipboardRecordHooked
                + ", total=" + HOOKED_METHODS.size());
    }

    private static boolean hookPreprocessMethod(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(CLIPBOARD_SERVICE_EXT, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    PREPROCESS_METHOD,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (PREPROCESS_HIT_LOGGED.compareAndSet(false, true)) {
                                log("🧹 blocked clipboard pre-processing (first hit)");
                            }
                            param.setResult(null);
                        }
                    },
                    false,
                    "void",
                    "android.content.Context",
                    "boolean",
                    "android.content.ClipData");
            if (hooked == 0) {
                throw new NoSuchMethodException(CLIPBOARD_SERVICE_EXT + "." + PREPROCESS_METHOD);
            }
            log("✅ semantic hook installed: " + CLIPBOARD_SERVICE_EXT + "."
                    + PREPROCESS_METHOD + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + CLIPBOARD_SERVICE_EXT + "." + PREPROCESS_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
    }
    private static boolean hookClipboardRecordMethod(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(CLIPBOARD_CONTROLLER, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    CLIPBOARD_RECORD_METHOD,
                    new XC_MethodHook(PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (CLIPBOARD_RECORD_HIT_LOGGED.compareAndSet(false, true)) {
                                log("🧹 blocked clipboard write recorder (first hit)");
                            }
                            param.setResult(null);
                        }
                    },
                    false,
                    "void",
                    "java.lang.String",
                    "int",
                    "boolean");
            if (hooked == 0) {
                throw new NoSuchMethodException(CLIPBOARD_CONTROLLER + "."
                        + CLIPBOARD_RECORD_METHOD);
            }
            log("✅ semantic hook installed: " + CLIPBOARD_CONTROLLER + "."
                    + CLIPBOARD_RECORD_METHOD + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + CLIPBOARD_CONTROLLER + "."
                    + CLIPBOARD_RECORD_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
    }


    private static boolean hookClassificationDispatchMethod(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(CLIPBOARD_CONTROLLER, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    CLASSIFICATION_DISPATCH_METHOD,
                    new XC_MethodHook(PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (CLASSIFICATION_DISPATCH_HIT_LOGGED.compareAndSet(false, true)) {
                                log("🧹 blocked clipboard AI classification dispatch (first hit)");
                            }
                            param.setResult(null);
                        }
                    },
                    false,
                    "void",
                    "android.content.ClipData",
                    "com.android.server.clipboard.classifier.IAITextClassifier",
                    "int",
                    "int");
            if (hooked == 0) {
                throw new NoSuchMethodException(CLIPBOARD_CONTROLLER + "."
                        + CLASSIFICATION_DISPATCH_METHOD);
            }
            log("✅ semantic hook installed: " + CLIPBOARD_CONTROLLER + "."
                    + CLASSIFICATION_DISPATCH_METHOD + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + CLIPBOARD_CONTROLLER + "."
                    + CLASSIFICATION_DISPATCH_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
    }


    private static boolean hookClassificationMethod(
            ClassLoader classLoader, String className, String methodName) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(className, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    methodName,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    },
                    false,
                    "void",
                    "android.os.Looper",
                    "android.content.ClipData",
                    "java.lang.String",
                    "int",
                    "int");
            if (hooked == 0) {
                throw new NoSuchMethodException(className + "." + methodName);
            }
            log("✅ semantic hook installed: " + className + "." + methodName
                    + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + className + "." + methodName);
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static boolean hookClassificationHandler(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(CLASSIFICATION_HANDLER, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    CLASSIFICATION_HANDLER_METHOD,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    },
                    false,
                    "void",
                    "android.os.Message");
            if (hooked == 0) {
                throw new NoSuchMethodException(
                        CLASSIFICATION_HANDLER + "." + CLASSIFICATION_HANDLER_METHOD);
            }
            log("✅ semantic hook installed: " + CLASSIFICATION_HANDLER + "."
                    + CLASSIFICATION_HANDLER_METHOD + " (overloads=" + hooked + ")");
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
            Class<?> targetClass = XposedHelpers.findClass(CLASSIFIER_DELEGATE, classLoader);
            XC_MethodHook callback = new XC_MethodHook(PRIORITY_HIGHEST) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            };
            int hooked = hookDeclaredMethod(
                    targetClass,
                    CLASSIFIER_SEND_METHOD,
                    callback,
                    false,
                    "void",
                    "java.lang.CharSequence",
                    "long",
                    CLASSIFIER_CALLBACK);
            // Older ColorOS builds omitted the request timestamp parameter.
            hooked += hookDeclaredMethod(
                    targetClass,
                    CLASSIFIER_SEND_METHOD,
                    callback,
                    false,
                    "void",
                    "java.lang.CharSequence",
                    CLASSIFIER_CALLBACK);
            if (hooked == 0) {
                throw new NoSuchMethodException(CLASSIFIER_DELEGATE + "."
                        + CLASSIFIER_SEND_METHOD);
            }
            log("✅ semantic hook installed: " + CLASSIFIER_DELEGATE + "."
                    + CLASSIFIER_SEND_METHOD + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + CLASSIFIER_DELEGATE + "."
                    + CLASSIFIER_SEND_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
    }


    private static void installAppPlatformHook(
            ClassLoader classLoader, String processName, AtomicBoolean installState) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(TARGET_PROVIDER, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    "addPrimaryClipChangedListener",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    },
                    true,
                    "void",
                    "com.oplus.epona.Request",
                    "com.oplus.epona.Call$Callback");
            if (hooked == 0) {
                throw new NoSuchMethodException(TARGET_PROVIDER + ".addPrimaryClipChangedListener");
            }
            log("✅ semantic appplatform hook installed in " + processName
                    + " (overloads=" + hooked + ")");
        } catch (Throwable throwable) {
            installState.set(false);
            log("❌ appplatform hook installation failed in " + processName);
            XposedBridge.log(throwable);
        }
    }

    private static void installColorDirectServiceHook(ClassLoader classLoader, String processName) {
        boolean semanticHooked = false;
        DexKitSemanticResolver resolver = DexKitSemanticResolver.tryCreate(classLoader, false);
        if (resolver != null) {
            try {
                semanticHooked = hookColorDirectSemanticMethods(resolver);
            } finally {
                resolver.close();
            }
        }

        boolean textIntentHooked = hookTextIntentClipboardEntry(classLoader);
        boolean sceneHooked = hookClipboardSceneDetect(classLoader);
        boolean apiHooked = hookOIntentClipboardDetect(classLoader);

        if (!semanticHooked && !textIntentHooked && !sceneHooked && !apiHooked) {
            COLOR_DIRECT_SERVICE_INSTALLED.set(false);
            log("❌ no semantic ColorDirectService clipboard hook installed");
            return;
        }

        log("✅ semantic ColorDirectService hooks installed in " + processName
                + "; dexkit=" + semanticHooked
                + ", textIntent=" + textIntentHooked
                + ", scene=" + sceneHooked
                + ", ointent=" + apiHooked);
    }

    private static boolean hookTextIntentClipboardEntry(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(TEXT_INTENT_MANAGER, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    TEXT_INTENT_ENTRY_METHOD,
                    blockTextIntentEntryHook(),
                    false,
                    "void",
                    "android.content.Context",
                    "java.lang.Object",
                    "java.lang.String",
                    "java.lang.String");
            if (hooked == 0) {
                throw new NoSuchMethodException(TEXT_INTENT_MANAGER + "."
                        + TEXT_INTENT_ENTRY_METHOD);
            }
            log("✅ named TextIntent fallback installed: " + TEXT_INTENT_MANAGER + "."
                    + TEXT_INTENT_ENTRY_METHOD + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ named TextIntent fallback unavailable: " + TEXT_INTENT_MANAGER + "."
                    + TEXT_INTENT_ENTRY_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static boolean hookColorDirectSemanticMethods(DexKitSemanticResolver resolver) {
        boolean hooked = false;
        hooked |= hookResolvedMethod(
                "TextIntent clipboard entry",
                resolveSemanticMethod(resolver, "TextIntent clipboard entry",
                        new String[]{"com.oplus.textintent"}, null, false, "void",
                        new String[]{"android.content.Context", "java.lang.Object",
                                "java.lang.String", "java.lang.String"}, "context"),
                blockTextIntentEntryHook());
        hooked |= hookResolvedMethod(
                "ClipboardScene.detect",
                resolveSemanticMethod(resolver, "ClipboardScene.detect", new String[]{
                        "com.oplus.ointent.detect.scene"}, new String[]{"ClipboardScene",
                        "specialCheck start"}, false, "void", new String[]{
                        "com.oplus.ointent.api.config.IntentInput",
                        "com.oplus.ointent.api.config.IntentOutput"}, "ClipboardScene",
                        "specialCheck start"),
                blockClipboardSceneHook());
        hooked |= hookResolvedMethod(
                "OIntentApi.detect scene",
                resolveSemanticMethod(resolver, "OIntentApi.detect scene", new String[]{
                        "com.oplus.ointent.detect"}, new String[]{"OIntentApi"}, true,
                        "java.util.List", new String[]{"android.content.Context",
                                "com.oplus.ointent.detect.scene.IntentScene", "java.lang.String",
                                "com.oplus.ointent.api.base.IntentOptions"}, "sceneType", "text"),
                blockOIntentSceneHook());
        hooked |= hookResolvedMethod(
                "OIntentApi.detect type list",
                resolveSemanticMethod(resolver, "OIntentApi.detect type list", new String[]{
                        "com.oplus.ointent.detect"}, new String[]{"OIntentApi"}, true,
                        "java.util.List", new String[]{"android.content.Context",
                                "com.oplus.ointent.api.config.IntentType[]", "java.lang.String",
                                "java.lang.String", "com.oplus.ointent.api.base.IntentOptions"},
                        "typeList", "name", "text"),
                blockOIntentTypeListHook());
        hooked |= hookResolvedMethod(
                "OIntentApi.detect type list without options",
                resolveSemanticMethod(resolver, "OIntentApi.detect type list without options",
                        new String[]{"com.oplus.ointent.detect"}, new String[]{"OIntentApi"},
                        true, "java.util.List", new String[]{"android.content.Context",
                                "com.oplus.ointent.api.config.IntentType[]", "java.lang.String",
                                "java.lang.String"}, "typeList", "name", "text"),
                blockOIntentTypeListHook());
        return hooked;
    }

    private static Method resolveSemanticMethod(
            DexKitSemanticResolver resolver,
            String label,
            String[] packages,
            String[] classAnchors,
            boolean expectedStatic,
            String returnType,
            String[] parameterTypes,
            String... methodAnchors) {
        try {
            return resolver.resolveUniqueMethodInPackage(
                    label, packages, classAnchors, expectedStatic,
                    returnType, parameterTypes, methodAnchors);
        } catch (Throwable throwable) {
            log("⚠️ DexKit semantic resolution unavailable: " + label);
            XposedBridge.log(throwable);
            return null;
        }
    }

    private static boolean hookResolvedMethod(String label, Method method, XC_MethodHook callback) {
        if (method == null) {
            return false;
        }
        synchronized (HOOKED_METHODS) {
            if (HOOKED_METHODS.contains(method)) {
                return true;
            }
            try {
                XposedBridge.hookMethod(method, callback);
                HOOKED_METHODS.add(method);
                log("✅ DexKit semantic hook installed: " + label + " -> "
                        + method.toGenericString());
                return true;
            } catch (Throwable throwable) {
                log("⚠️ DexKit semantic hook failed: " + label);
                XposedBridge.log(throwable);
                return false;
            }
        }
    }

    private static XC_MethodHook blockTextIntentEntryHook() {
        return new XC_MethodHook(PRIORITY_HIGHEST) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (TEXT_INTENT_ENTRY_HIT_LOGGED.compareAndSet(false, true)) {
                    log("🧹 blocked TextIntent clipboard entry (first hit)");
                }
                param.setResult(null);
            }
        };
    }

    private static XC_MethodHook blockClipboardSceneHook() {
        return new XC_MethodHook(PRIORITY_HIGHEST) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (CLIPBOARD_SCENE_HIT_LOGGED.compareAndSet(false, true)) {
                    log("🧹 blocked semantic ClipboardScene detection (first hit)");
                }
                param.setResult(null);
            }
        };
    }

    private static XC_MethodHook blockOIntentSceneHook() {
        return new XC_MethodHook(PRIORITY_HIGHEST) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object scene = param.args.length > 1 ? param.args[1] : null;
                if (!isClipboardIntentScene(scene)) {
                    return;
                }
                if (OINTENT_DETECT_HIT_LOGGED.compareAndSet(false, true)) {
                    log("🧹 blocked OIntentApi clipboard detection (first hit)");
                }
                param.setResult(new ArrayList<>());
            }
        };
    }

    private static XC_MethodHook blockOIntentTypeListHook() {
        return new XC_MethodHook(PRIORITY_HIGHEST) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 4 || !isClipboardIntentTypeList(param.args[1])) {
                    return;
                }
                if (OINTENT_TYPE_LIST_HIT_LOGGED.compareAndSet(false, true)) {
                    log("🧹 blocked OIntentApi clipboard type-list detection (first hit)");
                }
                param.setResult(new ArrayList<>());
            }
        };
    }



    private static boolean hookClipboardSceneDetect(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(CLIPBOARD_SCENE_CLASS, classLoader);
            int hooked = hookDeclaredMethod(
                    targetClass,
                    CLIPBOARD_SCENE_DETECT_METHOD,
                    new XC_MethodHook(PRIORITY_HIGHEST) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (CLIPBOARD_SCENE_HIT_LOGGED.compareAndSet(false, true)) {
                                log("🧹 blocked semantic ClipboardScene detection (first hit)");
                            }
                            param.setResult(null);
                        }
                    },
                    false,
                    "void",
                    "com.oplus.ointent.api.config.IntentInput",
                    "com.oplus.ointent.api.config.IntentOutput");
            if (hooked == 0) {
                throw new NoSuchMethodException(CLIPBOARD_SCENE_CLASS + "."
                        + CLIPBOARD_SCENE_DETECT_METHOD);
            }
            log("✅ semantic hook installed: " + CLIPBOARD_SCENE_CLASS + "."
                    + CLIPBOARD_SCENE_DETECT_METHOD + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ semantic ClipboardScene hook unavailable");
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static boolean hookOIntentClipboardDetect(ClassLoader classLoader) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(OINTENT_API_CLASS, classLoader);
            XC_MethodHook sceneCallback = new XC_MethodHook(PRIORITY_HIGHEST) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object scene = param.args.length > 1 ? param.args[1] : null;
                    if (!isClipboardIntentScene(scene)) {
                        return;
                    }
                    if (OINTENT_DETECT_HIT_LOGGED.compareAndSet(false, true)) {
                        log("🧹 blocked OIntentApi clipboard detection (first hit)");
                    }
                    param.setResult(new ArrayList<>());
                }
            };
            int hooked = hookDeclaredMethod(
                    targetClass,
                    OINTENT_DETECT_METHOD,
                    sceneCallback,
                    true,
                    "java.util.List",
                    "android.content.Context",
                    OINTENT_INTENT_SCENE,
                    "java.lang.String");
            hooked += hookDeclaredMethod(
                    targetClass,
                    OINTENT_DETECT_METHOD,
                    sceneCallback,
                    true,
                    "java.util.List",
                    "android.content.Context",
                    OINTENT_INTENT_SCENE,
                    "java.lang.String",
                    OINTENT_INTENT_OPTIONS);
            hooked += hookDeclaredMethod(
                    targetClass,
                    OINTENT_DETECT_METHOD,
                    blockOIntentTypeListHook(),
                    true,
                    "java.util.List",
                    "android.content.Context",
                    OINTENT_INTENT_TYPE_ARRAY,
                    "java.lang.String",
                    "java.lang.String",
                    OINTENT_INTENT_OPTIONS);
            hooked += hookDeclaredMethod(
                    targetClass,
                    OINTENT_DETECT_METHOD,
                    blockOIntentTypeListHook(),
                    true,
                    "java.util.List",
                    "android.content.Context",
                    OINTENT_INTENT_TYPE_ARRAY,
                    "java.lang.String",
                    "java.lang.String");
            if (hooked == 0) {
                throw new NoSuchMethodException(OINTENT_API_CLASS + "."
                        + OINTENT_DETECT_METHOD);
            }
            log("✅ semantic hook installed: " + OINTENT_API_CLASS + "."
                    + OINTENT_DETECT_METHOD + " (overloads=" + hooked + ")");
            return true;
        } catch (Throwable throwable) {
            log("⚠️ hook unavailable: " + OINTENT_API_CLASS + "."
                    + OINTENT_DETECT_METHOD);
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static boolean isClipboardIntentScene(Object scene) {
        if (scene instanceof Enum<?>) {
            return "CLIPBOARD".equals(((Enum<?>) scene).name());
        }
        return scene != null && "CLIPBOARD".equals(String.valueOf(scene));
    }

    private static boolean isClipboardIntentTypeList(Object typeList) {
        if (!(typeList instanceof Object[])) {
            return false;
        }
        Object[] types = (Object[]) typeList;
        for (Object type : types) {
            if (type instanceof Enum<?> && "TOKEN".equals(((Enum<?>) type).name())) {
                return true;
            }
        }
        return false;
    }


    private static int hookDeclaredMethod(
            Class<?> targetClass,
            String methodName,
            XC_MethodHook callback,
            boolean expectedStatic,
            String expectedReturnTypeName,
            String... expectedParameterTypeNames) {
        final Method[] declaredMethods;
        try {
            declaredMethods = targetClass.getDeclaredMethods();
        } catch (Throwable throwable) {
            log("⚠️ unable to enumerate methods: " + targetClass.getName());
            XposedBridge.log(throwable);
            return 0;
        }

        int hooked = 0;
        for (Method method : declaredMethods) {
            if (!methodName.equals(method.getName())
                    || method.isBridge()
                    || method.isSynthetic()
                    || Modifier.isStatic(method.getModifiers()) != expectedStatic
                    || !expectedReturnTypeName.equals(method.getReturnType().getName())
                    || !hasExactParameterTypes(
                            method.getParameterTypes(), expectedParameterTypeNames)) {
                continue;
            }

            synchronized (HOOKED_METHODS) {
                if (HOOKED_METHODS.contains(method)) {
                    hooked++;
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, callback);
                    HOOKED_METHODS.add(method);
                    hooked++;
                } catch (Throwable throwable) {
                    log("⚠️ hook failed: " + method.toGenericString());
                    XposedBridge.log(throwable);
                }
            }
        }
        return hooked;
    }

    private static boolean hasExactParameterTypes(
            Class<?>[] parameterTypes, String... expectedParameterTypeNames) {
        if (parameterTypes.length != expectedParameterTypeNames.length) {
            return false;
        }
        for (int index = 0; index < parameterTypes.length; index++) {
            if (!expectedParameterTypeNames[index].equals(parameterTypes[index].getName())) {
                return false;
            }
        }
        return true;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " | " + message);
    }

}
