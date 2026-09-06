package com.omar.fix_fod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runtime reimplementation of flashbang_fix.patch's MTK GHBM dim-layer behavior.
 *
 * IMPORTANT: this does NOT touch UdfpsControllerOverlay's internals — those fields
 * (hbmView, dimView, useMtkGhbmDimming) only exist if the source patch is compiled
 * in. On stock SystemUI they don't exist, so this class manages its own independent
 * dim view instead, added straight to WindowManager, mirroring the real overlay's
 * show/hide lifecycle via hooks on UdfpsController.
 *
 * Toggle: /data/data/<module_pkg>/shared_prefs/fix_fod_prefs.xml -> "mtkghbm_enabled"
 * Re-read on every finger event, so no reboot/respawn needed to flip it.
 */
public class MtkGhbmDimHook {

    private static final String TAG = "PHH-MtkGhbmDim";

    private static final String PREFS_NAME = "fix_fod_prefs";
    private static final String PREF_KEY_ENABLED = "mtkghbm_enabled";

    private static final String CLS_UDFPS_CONTROLLER =
            "com.android.systemui.biometrics.UdfpsController";

    // Candidate private field names to try, in order — adjust if your build differs.
    private static final String[] CONTEXT_FIELD_CANDIDATES = {"mContext", "context"};
    private static final String[] WM_FIELD_CANDIDATES = {"mWindowManager", "windowManager"};

    private static XSharedPreferences sPrefs;

    private static volatile View sDimView;
    private static volatile WindowManager.LayoutParams sDimParams;
    private static volatile WindowManager sWindowManager;
    private static volatile boolean sDimAdded = false;
    private static final Object sLock = new Object();

    // TEMP: toggle bypassed for debugging — always on. Revert to the XSharedPreferences
    // check once the hook itself is confirmed working.
    private static boolean isEnabled() {
        return true;
    }

    public static void hook(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_CONTROLLER, cl);
            Log.d(TAG, "Found UdfpsController: " + cls.getName());
            dumpMethods(cls);

            int hooked = 0;
            try {
                XposedBridge.hookAllMethods(cls, "hideUdfpsOverlay", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.d(TAG, "hideUdfpsOverlay fired");
                        if (!isEnabled()) return;
                        removeDimView();
                    }
                });
                hooked++;
                Log.d(TAG, "hooked hideUdfpsOverlay OK");
            } catch (Throwable t) {
                Log.e(TAG, "FAILED to hook hideUdfpsOverlay", t);
            }

            for (String showMethod : new String[]{"showUdfpsOverlay", "onUdfpsOverlayShown"}) {
                try {
                    XposedBridge.hookAllMethods(cls, showMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Log.d(TAG, showMethod + " fired");
                            if (!isEnabled()) return;
                            ensureDimView(param.thisObject);
                        }
                    });
                    hooked++;
                    Log.d(TAG, "hooked " + showMethod + " OK");
                } catch (Throwable t) {
                    Log.w(TAG, "could not hook " + showMethod + " (may not exist in this build)");
                }
            }

            // Alpha follows brightness while the finger is down, same formula as the patch.
            try {
                XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Log.d(TAG, "onFingerDown fired, isEnabled=" + isEnabled());
                        if (!isEnabled()) return;
                        ensureDimView(param.thisObject);
                        updateAlpha(param.thisObject);
                    }
                });
                hooked++;
                Log.d(TAG, "hooked onFingerDown OK");
            } catch (Throwable t) {
                Log.e(TAG, "FAILED to hook onFingerDown", t);
            }

            try {
                XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Log.d(TAG, "onFingerUp fired");
                        if (!isEnabled()) return;
                        setDimAlpha(0f);
                    }
                });
                hooked++;
                Log.d(TAG, "hooked onFingerUp OK");
            } catch (Throwable t) {
                Log.e(TAG, "FAILED to hook onFingerUp", t);
            }

            Log.d(TAG, "Total methods hooked: " + hooked);

        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "UdfpsController not found", e);
        }
    }

    // One-time dump so we can see the real method/field names on this device's build,
    // instead of guessing. Check logcat for "PHH-MtkGhbmDim" after this fires.
    private static void dumpMethods(Class<?> cls) {
        try {
            StringBuilder sb = new StringBuilder("Methods on ").append(cls.getName()).append(":\n");
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                sb.append("  ").append(m.getName()).append("(")
                        .append(m.getParameterTypes().length).append(" args)\n");
            }
            Log.d(TAG, sb.toString());

            StringBuilder fb = new StringBuilder("Fields on ").append(cls.getName()).append(":\n");
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                fb.append("  ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append("\n");
            }
            Log.d(TAG, fb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "dumpMethods failed", t);
        }
    }

    private static Object getFieldAny(Object obj, String[] candidates) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            for (String name : candidates) {
                try {
                    Field f = cls.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static void ensureDimView(Object udfpsController) {
        synchronized (sLock) {
            if (sDimAdded) return;

            Context context = (Context) getFieldAny(udfpsController, CONTEXT_FIELD_CANDIDATES);
            WindowManager wm = (WindowManager) getFieldAny(udfpsController, WM_FIELD_CANDIDATES);
            if (context == null || wm == null) {
                Log.e(TAG, "Could not resolve context/windowManager fields — check field names");
                return;
            }
            sWindowManager = wm;

            if (sDimView == null) {
                sDimView = new View(context);
                sDimView.setBackgroundColor(Color.BLACK);
                sDimView.setVisibility(View.VISIBLE);
            }

            if (sDimParams == null) {
                // TYPE_NAVIGATION_BAR_PANEL is a hidden constant — not in the public SDK
                // stubs this module compiles against, so pull it via reflection. Falls
                // back to the public TYPE_APPLICATION_OVERLAY if the hidden field ever
                // moves/renames.
                int type;
                try {
                    type = XposedHelpers.getStaticIntField(WindowManager.LayoutParams.class,
                            "TYPE_NAVIGATION_BAR_PANEL");
                } catch (Throwable t) {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                }

                sDimParams = new WindowManager.LayoutParams(
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT);

                try {
                    XposedHelpers.setObjectField(sDimParams, "title", "MtkGhbmDim");
                } catch (Throwable ignored) {}

                sDimParams.width = WindowManager.LayoutParams.MATCH_PARENT;
                sDimParams.height = WindowManager.LayoutParams.MATCH_PARENT;
                sDimParams.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                sDimParams.alpha = 0f;

                try {
                    int trustedOverlayFlag = XposedHelpers.getStaticIntField(
                            WindowManager.LayoutParams.class, "PRIVATE_FLAG_TRUSTED_OVERLAY");
                    XposedHelpers.setIntField(sDimParams, "privateFlags", trustedOverlayFlag);
                } catch (Throwable ignored) {}
            }

            try {
                wm.addView(sDimView, sDimParams);
                sDimAdded = true;
                Log.d(TAG, "MTK GHBM dim view added");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to add dim view", t);
            }
        }
    }

    private static void removeDimView() {
        synchronized (sLock) {
            if (!sDimAdded || sDimView == null || sWindowManager == null) return;
            try {
                sWindowManager.removeViewImmediate(sDimView);
            } catch (Throwable ignored) {
            } finally {
                sDimAdded = false;
            }
        }
    }

    private static void updateAlpha(Object udfpsController) {
        Context context = (Context) getFieldAny(udfpsController, CONTEXT_FIELD_CANDIDATES);
        int brightness = getSystemBrightness(context);
        float alpha = calculateAlpha(brightness);
        setDimAlpha(alpha);
    }

    private static void setDimAlpha(float alpha) {
        synchronized (sLock) {
            if (!sDimAdded || sDimView == null || sDimParams == null || sWindowManager == null) return;
            if (Math.abs(sDimParams.alpha - alpha) < 0.001f) return;
            sDimParams.alpha = Math.max(0f, Math.min(1f, alpha));
            try {
                sWindowManager.updateViewLayout(sDimView, sDimParams);
            } catch (Throwable t) {
                Log.w(TAG, "updateViewLayout failed", t);
            }
        }
    }

    // Ported directly from MtkUdfpsScrimController in the patch.
    private static float calculateAlpha(int brightness) {
        float alpha = 1.0f - (brightness / 255.0f);
        if (brightness < 25) {
            alpha = alpha * 0.95f;
        }
        return Math.max(0.0f, Math.min(1.0f, alpha));
    }

    private static int getSystemBrightness(Context context) {
        if (context == null) return 127;
        try {
            float brightFloat = Settings.System.getFloat(
                    context.getContentResolver(), "screen_brightness_float", -1.0f);
            if (brightFloat >= 0.0f) {
                return (int) (brightFloat * 255.0f);
            }
        } catch (Throwable ignored) {}
        try {
            return Settings.System.getInt(
                    context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 127);
        } catch (Throwable ignored) {
            return 127;
        }
    }
}
