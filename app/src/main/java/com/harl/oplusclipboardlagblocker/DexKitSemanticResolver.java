package com.harl.oplusclipboardlagblocker;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import de.robv.android.xposed.XposedBridge;

/** DexKit 的类先、方法后唯一候选解析器。 */
final class DexKitSemanticResolver implements AutoCloseable {
    private static final String TAG = "🛡️ OplusClipboardLagBlocker";
    private static final Object NATIVE_LOAD_LOCK = new Object();
    private static volatile boolean nativeLibraryAvailable;
    private final DexKitBridge bridge;
    private final ClassLoader classLoader;

    private DexKitSemanticResolver(DexKitBridge bridge, ClassLoader classLoader) {
        this.bridge = bridge;
        this.classLoader = classLoader;
    }

    static DexKitSemanticResolver tryCreate(ClassLoader classLoader, boolean useMemoryDexFile) {
        if (!ensureNativeLibrary()) {
            return null;
        }

        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(classLoader, useMemoryDexFile);
            bridge.setThreadNum(1);
            bridge.setMaxConcurrentQueries(1);
            return new DexKitSemanticResolver(bridge, classLoader);
        } catch (Throwable throwable) {
            if (bridge != null) {
                bridge.close();
            }
            log("⚠️ DexKit bridge unavailable");
            XposedBridge.log(throwable);
            return null;
        }
    }

    private static boolean ensureNativeLibrary() {
        if (nativeLibraryAvailable) {
            return true;
        }
        synchronized (NATIVE_LOAD_LOCK) {
            if (nativeLibraryAvailable) {
                return true;
            }
            try {
                System.loadLibrary("dexkit");
                nativeLibraryAvailable = true;
                log("✅ DexKit native library loaded");
            } catch (Throwable throwable) {
                log("⚠️ DexKit native library unavailable; using named API fallback");
                XposedBridge.log(throwable);
            }
            return nativeLibraryAvailable;
        }
    }
    Method resolveUniqueMethodInPackage(
            String label,
            String[] classPackages,
            String[] classAnchors,
            boolean expectedStatic,
            String returnType,
            String[] parameterTypes,
            String... methodAnchors) throws NoSuchMethodException {
        MethodMatcher matcher = methodMatcher(returnType, parameterTypes, methodAnchors);
        ClassMatcher classMatcher = new ClassMatcher().addMethod(matcher);
        if (classAnchors != null && classAnchors.length > 0) {
            classMatcher.usingStrings(classAnchors);
        }
        FindClass finder = new FindClass();
        if (classPackages != null && classPackages.length > 0) {
            finder.searchPackages(classPackages);
        }

        ClassDataList classes = bridge.findClass(finder.matcher(classMatcher));
        List<MethodData> matches = new ArrayList<>();
        for (ClassData candidateClass : classes) {
            MethodDataList methods = candidateClass.findMethod(
                    new FindMethod().matcher(matcher));
            for (MethodData candidate : methods) {
                if (candidate.isMethod()
                        && Modifier.isStatic(candidate.getModifiers()) == expectedStatic) {
                    matches.add(candidate);
                }
            }
        }
        if (matches.size() != 1) {
            log("⚠️ " + label + " candidate count=" + matches.size()
                    + "; refusing ambiguous semantic resolution");
            return null;
        }

        MethodData candidate = matches.get(0);
        log("🔎 " + label + " -> " + candidate.getMethodSign());
        return candidate.getMethodInstance(classLoader);
    }

    private static MethodMatcher methodMatcher(
            String returnType, String[] parameterTypes, String[] methodAnchors) {
        if (returnType == null || parameterTypes == null) {
            throw new IllegalArgumentException("returnType and parameterTypes are required");
        }
        MethodMatcher matcher = new MethodMatcher()
                .returnType(returnType)
                .paramTypes(parameterTypes);
        if (methodAnchors != null && methodAnchors.length > 0) {
            matcher.usingStrings(methodAnchors);
        }
        return matcher;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " | " + message);
    }

    @Override
    public void close() {
        bridge.close();
    }
}
