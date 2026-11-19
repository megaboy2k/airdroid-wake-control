package io.github.megaboy2k.airdroidwakecontrol;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class AirDroidWakeHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.sand.airdroid";
    private static final String ADDON_PACKAGE = "com.sand.remotesupportaddon";
    private static final String REMOTE_IME_CLASS =
            "com.sand.airdroid.vnc.RemoteInputMethodService";
    private static final int OP_PROJECT_MEDIA = 46;
    private static final int MODE_ALLOWED = 0;
    private static final long APP_OPS_ENSURE_DELAY_MS = 2000L;
    private static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;
    private static final long PROJECTION_START_SETTLE_MS = 300L;
    private static final long BLACKOUT_TIMEOUT_MS = 10000L;
    private static final Object BLACKOUT_LOCK = new Object();
    private static final Set<Object> BLACKOUT_WAKE_LOCKS = new HashSet<>();
    private static Number savedBrightnessOverride;
    private static boolean panelBlackoutLogged;
    private static boolean blackoutActive;
    private static boolean blackoutEnding;
    private static long blackoutGeneration;
    private static Object blackoutPowerManagerService;
    private static boolean appOpsEnsureScheduled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!"android".equals(loadPackageParam.packageName)
                && !"system".equals(loadPackageParam.packageName)) {
            return;
        }

        Class<?> powerManagerService = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService",
                loadPackageParam.classLoader);

        XposedBridge.hookAllMethods(powerManagerService, "systemReady",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        synchronized (BLACKOUT_LOCK) {
                            if (appOpsEnsureScheduled) {
                                return;
                            }
                            appOpsEnsureScheduled = true;
                        }
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> ensureProjectMediaAllowed(param.thisObject),
                                APP_OPS_ENSURE_DELAY_MS);
                    }
                });

        XposedBridge.hookAllMethods(powerManagerService, "acquireWakeLockInternal",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int tagIndex = findTargetTagIndex(param.args);
                        if (tagIndex < 1 || !(param.args[tagIndex - 1] instanceof Integer)) {
                            return;
                        }

                        int flagsIndex = tagIndex - 1;
                        int flags = (Integer) param.args[flagsIndex];
                        if ((flags & ACQUIRE_CAUSES_WAKEUP) == 0) {
                            return;
                        }

                        String tag = (String) param.args[tagIndex];
                        String sourcePackage = (String) param.args[tagIndex + 1];
                        String normalizedTag = tag.toLowerCase(Locale.ROOT);
                        boolean airDroidWebRtc = TARGET_PACKAGE.equals(sourcePackage)
                                && normalizedTag.contains("webrtc");
                        boolean addonSmartService = ADDON_PACKAGE.equals(sourcePackage)
                                && "smartservice".equals(normalizedTag);
                        if (!airDroidWebRtc && !addonSmartService) {
                            return;
                        }

                        int remoteImeState = getRemoteImeState(param.thisObject);
                        boolean camera = normalizedTag.contains("initwebrtccameraactivity");
                        boolean genericWebRtc = "webrtc".equals(normalizedTag);
                        boolean taggedRemoteControl = "webrtc_screen".equals(normalizedTag);
                        boolean remoteControl = remoteImeState
                                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                || taggedRemoteControl;

                        boolean shouldBlockWake = addonSmartService
                                ? !remoteControl
                                : camera || (genericWebRtc && !remoteControl);
                        if (!shouldBlockWake) {
                            log("ALLOW", sourcePackage, tag, flags, remoteImeState);
                            return;
                        }

                        // Mirroring must preserve the user's current display state. Blackout is
                        // only needed when AirDroid is about to wake a previously sleeping phone.
                        // If the screen was already interactive, do not dim it and do not arm the
                        // projection-start goToSleep callback.
                        if (isDeviceInteractive(param.thisObject)) {
                            log("ALLOW_SCREEN_ON", sourcePackage, tag, flags, remoteImeState);
                            return;
                        }

                        Object wakeLockToken = param.args.length > 0 ? param.args[0] : null;
                        if (wakeLockToken != null
                                && startBlackout(param.thisObject, wakeLockToken)) {
                            // Keep ACQUIRE_CAUSES_WAKEUP: AirDroid needs an interactive window for
                            // its accessibility helper. Brightness zero keeps the OLED panel black.
                            log("BLACKOUT", sourcePackage, tag, flags, remoteImeState);
                        } else {
                            // Fail open. Blocking ACQUIRE_CAUSES_WAKEUP after AirDroid has
                            // initialized its privileged input helper can leave Remote Control in
                            // a half-started state on Android 10.
                            log("ALLOW_BLACKOUT_FAILED", sourcePackage, tag, flags,
                                    remoteImeState);
                        }
                    }
                });

        XposedBridge.hookAllMethods(powerManagerService, "releaseWakeLockInternal",
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || param.args[0] == null) {
                    return;
                }
                finishBlackout(param.thisObject, param.args[0]);
            }
        });

        Class<?> photonicModulator = XposedHelpers.findClass(
                "com.android.server.display.DisplayPowerState$PhotonicModulator",
                loadPackageParam.classLoader);
        XposedBridge.hookAllMethods(photonicModulator, "setState", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBlackoutActive()) {
                    return;
                }

                final String brightnessType;
                if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                    // Android 10: setState(int state, int backlight). Zero is the explicit
                    // BRIGHTNESS_OFF value and prevents the initial panel flash while the
                    // logical display remains interactive for MediaProjection startup.
                    param.args[1] = 0;
                    brightnessType = "int";
                } else if (param.args.length >= 3
                        && param.args[1] instanceof Float
                        && param.args[2] instanceof Float) {
                    // Newer OxygenOS: zero is clamped to panel minimum. This display pipeline
                    // uses -1 while keeping state=ON during color fade.
                    param.args[1] = -1.0f;
                    param.args[2] = -1.0f;
                    brightnessType = "float";
                } else {
                    return;
                }

                synchronized (BLACKOUT_LOCK) {
                    if (!panelBlackoutLogged) {
                        panelBlackoutLogged = true;
                        XposedBridge.log(
                                "AirDroidWakeControl: PANEL backlight forced off type="
                                        + brightnessType);
                    }
                }
            }
        });

        Class<?> mediaProjection = XposedHelpers.findClass(
                "com.android.server.media.projection.MediaProjectionManagerService$MediaProjection",
                loadPackageParam.classLoader);
        XposedBridge.hookAllMethods(mediaProjection, "start", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getThrowable() != null) {
                    return;
                }
                String packageName = (String) XposedHelpers.getObjectField(
                        param.thisObject, "packageName");
                if (!TARGET_PACKAGE.equals(packageName)) {
                    return;
                }

                final Object powerManagerService;
                final long generation;
                synchronized (BLACKOUT_LOCK) {
                    if (!blackoutActive || blackoutEnding) {
                        return;
                    }
                    powerManagerService = blackoutPowerManagerService;
                    generation = blackoutGeneration;
                }
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> endBlackout(powerManagerService, generation,
                                "projection-start"),
                        PROJECTION_START_SETTLE_MS);
            }
        });

        XposedBridge.log("AirDroidWakeControl: system_server hook installed");
    }

    private static boolean isDeviceInteractive(Object powerManagerService) {
        try {
            Context context = (Context) XposedHelpers.getObjectField(
                    powerManagerService, "mContext");
            PowerManager powerManager = (PowerManager) context.getSystemService(
                    Context.POWER_SERVICE);
            return powerManager != null && powerManager.isInteractive();
        } catch (Throwable throwable) {
            XposedBridge.log("AirDroidWakeControl: cannot read interactive state: "
                    + throwable);
            // Preserve the previous blackout behavior if the device state cannot be read.
            return false;
        }
    }

    private static void ensureProjectMediaAllowed(Object powerManagerService) {
        try {
            Context context = (Context) XposedHelpers.getObjectField(
                    powerManagerService, "mContext");
            int uid = context.getPackageManager()
                    .getApplicationInfo(TARGET_PACKAGE, 0).uid;
            AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(
                    Context.APP_OPS_SERVICE);
            if (appOpsManager == null) {
                throw new IllegalStateException("AppOpsManager unavailable");
            }

            // Keep both layers aligned. App Manager normally changes only the UID mode,
            // while AirDroid also needs the package-specific mode to stay allowed.
            XposedHelpers.callMethod(appOpsManager, "setUidMode",
                    OP_PROJECT_MEDIA, uid, MODE_ALLOWED);
            XposedHelpers.callMethod(appOpsManager, "setMode",
                    OP_PROJECT_MEDIA, uid, TARGET_PACKAGE, MODE_ALLOWED);
            XposedBridge.log("AirDroidWakeControl: PROJECT_MEDIA allowed for uid=" + uid
                    + " package=" + TARGET_PACKAGE);
        } catch (PackageManager.NameNotFoundException exception) {
            XposedBridge.log("AirDroidWakeControl: AirDroid not installed; "
                    + "PROJECT_MEDIA unchanged");
        } catch (Throwable throwable) {
            XposedBridge.log("AirDroidWakeControl: cannot ensure PROJECT_MEDIA: "
                    + throwable);
        }
    }

    private static int findTargetTagIndex(Object[] args) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (!(args[index] instanceof String) || !(args[index + 1] instanceof String)) {
                continue;
            }
            if (TARGET_PACKAGE.equals(args[index + 1])
                    || ADDON_PACKAGE.equals(args[index + 1])) {
                return index;
            }
        }
        return -1;
    }

    private static int getRemoteImeState(Object powerManagerService) {
        try {
            Context context = (Context) XposedHelpers.getObjectField(
                    powerManagerService, "mContext");
            ComponentName component = new ComponentName(TARGET_PACKAGE, REMOTE_IME_CLASS);
            return context.getPackageManager().getComponentEnabledSetting(component);
        } catch (Throwable throwable) {
            XposedBridge.log("AirDroidWakeControl: cannot read RemoteInputMethodService state: "
                    + throwable);
            return -1;
        }
    }

    private static boolean startBlackout(Object powerManagerService, Object wakeLockToken) {
        final boolean newSession;
        final long generation;
        final Number blackoutBrightness;
        try {
            synchronized (BLACKOUT_LOCK) {
                if (blackoutEnding) {
                    return false;
                }
                newSession = !blackoutActive;
                if (newSession) {
                    Object brightnessOverride = XposedHelpers.getObjectField(
                            powerManagerService, "mScreenBrightnessOverrideFromWindowManager");
                    if (!(brightnessOverride instanceof Number)) {
                        throw new IllegalStateException("unexpected brightness override type: "
                                + (brightnessOverride == null
                                ? "null" : brightnessOverride.getClass().getName()));
                    }
                    savedBrightnessOverride = (Number) brightnessOverride;
                    panelBlackoutLogged = false;
                    blackoutActive = true;
                    blackoutPowerManagerService = powerManagerService;
                    blackoutGeneration++;
                }
                BLACKOUT_WAKE_LOCKS.add(wakeLockToken);
                generation = blackoutGeneration;
                blackoutBrightness = newSession
                        ? zeroBrightnessFor(savedBrightnessOverride) : null;
            }
            if (newSession) {
                // Do not call into PowerManager while holding BLACKOUT_LOCK. Display updates can
                // synchronously reach the PhotonicModulator hook on older OxygenOS builds.
                setBrightnessOverride(powerManagerService, blackoutBrightness);
            }
            if (newSession) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> endBlackout(powerManagerService, generation, "timeout"),
                        BLACKOUT_TIMEOUT_MS);
            }
            return true;
        } catch (Throwable throwable) {
            synchronized (BLACKOUT_LOCK) {
                BLACKOUT_WAKE_LOCKS.remove(wakeLockToken);
                if (BLACKOUT_WAKE_LOCKS.isEmpty()) {
                    savedBrightnessOverride = null;
                    panelBlackoutLogged = false;
                    blackoutActive = false;
                    blackoutEnding = false;
                    blackoutPowerManagerService = null;
                }
            }
            XposedBridge.log("AirDroidWakeControl: cannot start brightness blackout: "
                    + throwable);
            return false;
        }
    }

    private static boolean isBlackoutActive() {
        synchronized (BLACKOUT_LOCK) {
            return blackoutActive;
        }
    }

    private static void finishBlackout(final Object powerManagerService,
            Object wakeLockToken) {
        synchronized (BLACKOUT_LOCK) {
            BLACKOUT_WAKE_LOCKS.remove(wakeLockToken);
        }
    }

    private static void endBlackout(final Object powerManagerService,
            long generation, String reason) {
        final Number brightnessToRestore;
        synchronized (BLACKOUT_LOCK) {
            if (!blackoutActive || blackoutEnding
                    || generation != blackoutGeneration
                    || powerManagerService == null) {
                return;
            }
            blackoutEnding = true;
            brightnessToRestore = savedBrightnessOverride;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Object binderService = XposedHelpers.getObjectField(
                        powerManagerService, "mBinderService");
                XposedHelpers.callMethod(binderService, "goToSleep",
                        SystemClock.uptimeMillis(), 4, 1);
                XposedBridge.log("AirDroidWakeControl: BLACKOUT sleep requested reason="
                        + reason);
            } catch (Throwable throwable) {
                XposedBridge.log("AirDroidWakeControl: cannot return display to sleep: "
                        + throwable);
            }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    setBrightnessOverride(powerManagerService, brightnessToRestore);
                    XposedBridge.log("AirDroidWakeControl: BLACKOUT brightness restored");
                } catch (Throwable throwable) {
                    XposedBridge.log("AirDroidWakeControl: cannot restore brightness: "
                            + throwable);
                } finally {
                    synchronized (BLACKOUT_LOCK) {
                        if (generation == blackoutGeneration) {
                            BLACKOUT_WAKE_LOCKS.clear();
                            savedBrightnessOverride = null;
                            panelBlackoutLogged = false;
                            blackoutActive = false;
                            blackoutEnding = false;
                            blackoutPowerManagerService = null;
                        }
                    }
                }
            }, 500L);
        });
    }

    private static Number zeroBrightnessFor(Number savedBrightness) {
        if (savedBrightness instanceof Integer) {
            return Integer.valueOf(0);
        }
        if (savedBrightness instanceof Float) {
            return Float.valueOf(0.0f);
        }
        throw new IllegalArgumentException("unsupported brightness override type: "
                + savedBrightness.getClass().getName());
    }

    private static void setBrightnessOverride(Object powerManagerService, Number brightness)
            throws Exception {
        // Android 10 uses an int in the -1..255 range, while newer Android releases use a
        // normalized float. Resolve the exact private framework signature instead of relying on
        // the legacy bridge's boxed-argument method matching.
        Class<?> parameterType;
        Object argument;
        if (brightness instanceof Integer) {
            parameterType = int.class;
            argument = brightness.intValue();
        } else if (brightness instanceof Float) {
            parameterType = float.class;
            argument = brightness.floatValue();
        } else {
            throw new IllegalArgumentException("unsupported brightness override type: "
                    + brightness.getClass().getName());
        }
        Method method = powerManagerService.getClass().getDeclaredMethod(
                "setScreenBrightnessOverrideFromWindowManagerInternal", parameterType);
        method.setAccessible(true);
        method.invoke(powerManagerService, argument);
    }

    private static void log(String action, String sourcePackage, String tag, int flags,
            int remoteImeState) {
        XposedBridge.log("AirDroidWakeControl: " + action
                + " package=" + sourcePackage
                + " tag=" + tag
                + " flags=0x" + Integer.toHexString(flags)
                + " remoteImeState=" + remoteImeState);
    }
}
