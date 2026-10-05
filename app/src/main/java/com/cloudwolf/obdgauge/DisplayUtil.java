package com.cloudwolf.obdgauge;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 多屏（中控屏 + 仪表屏）支持工具。
 *
 * 比亚迪 DiLink 车机的仪表是由车机 Android 系统里的「投屏面」渲染的：原厂
 * com.xdja.containerservice（AutoContainer / fission）用 createVirtualDisplay 建出
 * 一块附加 Display（DL3 为 display 1，DL5 为 display 2，常见 1280×480@320dpi），
 * 名字形如 fission_bg_xdjaVirtualSurface / fission_bg_XDJAScreenProjection。
 * 只要这块屏对第三方应用可见（DiLink 5.0/DiLink 100 实测可见，DiLink 4.0 部分固件只返回
 * display 0），就能用 {@link Context#createDisplayContext(Display)} 拿到该屏自己的
 * WindowManager，把悬浮窗（TYPE_APPLICATION_OVERLAY）画到仪表上——不需要系统签名、
 * 不需要 root，也不需要比亚迪 SDK。
 *
 * 若看不到该屏或加窗被拒（车机把它作为私有屏幕、或投屏面未激活），
 * {@link #probeOverlay} 会失败，悬浮窗自动回退中控屏。所有尝试都写入
 * {@link Prefs#appendScreenLog}，供用户在车机上（没有 ADB）复制反馈。
 */
public final class DisplayUtil {

    private DisplayUtil() {
    }

    public static DisplayManager manager(Context context) {
        try {
            return (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本应用当前可见的全部屏幕（主屏 + 系统公开给第三方应用的附加屏）。 */
    public static List<Display> listDisplays(Context context) {
        List<Display> out = new ArrayList<>();
        DisplayManager dm = manager(context);
        if (dm != null) {
            try {
                Display[] all = dm.getDisplays();
                if (all != null) {
                    for (Display d : all) {
                        if (d != null) {
                            out.add(d);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            Display main = mainDisplay(context);
            if (main != null) {
                out.add(main);
            }
        }
        return out;
    }

    public static Display mainDisplay(Context context) {
        DisplayManager dm = manager(context);
        if (dm == null) {
            return null;
        }
        try {
            return dm.getDisplay(Display.DEFAULT_DISPLAY);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按设置里的屏幕 id（其次按记忆的屏幕名，id 会随固件/重启变化）找目标屏幕；
     * 返回 null 表示用中控主屏（含找不到时回退）。
     */
    public static Display findDisplay(Context context, int displayId, String displayName) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return null;
        }
        List<Display> all = listDisplays(context);
        for (Display d : all) {
            if (d.getDisplayId() == displayId && d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                return d;
            }
        }
        if (displayName != null && !displayName.isEmpty()) {
            for (Display d : all) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY
                        && displayName.equalsIgnoreCase(name(d))) {
                    return d;
                }
            }
        }
        return null;
    }

    /** 是否是比亚迪原厂仪表投屏面（AutoContainer / fission 虚拟屏）。 */
    public static boolean isClusterSurface(Display d) {
        String n = name(d).toLowerCase();
        return n.contains("xdja") || n.contains("fission") || n.contains("cluster")
                || n.contains("instrument");
    }

    public static String sizeText(Display d) {
        if (d == null) {
            return "";
        }
        try {
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            return m.widthPixels + "×" + m.heightPixels;
        } catch (Throwable t) {
            return "";
        }
    }

    public static String name(Display d) {
        try {
            String n = d == null ? null : d.getName();
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 选择屏幕弹窗里的一项：屏幕 id + 分辨率（+ 系统给出的屏幕名）。 */
    public static String label(Context context, Display d) {
        String size = sizeText(d);
        String text = context.getString(R.string.display_screen_label,
                d.getDisplayId(), size.isEmpty() ? "?" : size);
        String name = name(d);
        if (isClusterSurface(d)) {
            text = context.getString(R.string.display_screen_cluster, text);
        }
        return name.isEmpty() ? text : text + " · " + name;
    }

    /** 诊断日志中的一行：id / 名称 / 分辨率 / 密度 / 状态 / 旋转 / 标志。 */
    public static String describe(Display d) {
        if (d == null) {
            return "主屏（Display.DEFAULT_DISPLAY=0）";
        }
        try {
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            return "屏幕 " + d.getDisplayId()
                    + " · " + m.widthPixels + "×" + m.heightPixels
                    + " · 密度 " + m.density
                    + " · 状态 " + stateText(d.getState())
                    + " · 旋转 " + d.getRotation()
                    + " · flags 0x" + Integer.toHexString(d.getFlags())
                    + " · " + name(d);
        } catch (Throwable t) {
            return "屏幕 " + d.getDisplayId() + " · 读取信息失败：" + errorText(t);
        }
    }

    private static String stateText(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return "OFF";
            case Display.STATE_ON:
                return "ON";
            case Display.STATE_DOZE:
                return "DOZE";
            case Display.STATE_DOZE_SUSPEND:
                return "DOZE_SUSPEND";
            case Display.STATE_VR:
                return "VR";
            case Display.STATE_UNKNOWN:
                return "UNKNOWN";
            default:
                return String.valueOf(state);
        }
    }

    /**
     * 试探某块屏幕能否承载本应用的悬浮窗：在该屏加一个 1×1 全透明窗口后立刻移除。
     * 返回 null 表示可以投屏，否则返回失败原因。与 FloatingService 真正投屏走同一套 API
     * （DisplayContext + 该屏 WindowManager + overlay 窗口），因此结果具有参考价值。
     */
    public static String probeOverlay(Context context, Display display) {
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return null;
        }
        WindowManager wm = null;
        View probe = null;
        try {
            Context displayContext = context.createDisplayContext(display);
            wm = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return "该屏幕没有可用的 WindowManager";
            }
            probe = new View(displayContext);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.width = 1;
            lp.height = 1;
            lp.alpha = 0f;
            wm.addView(probe, lp);
        } catch (Throwable t) {
            return errorText(t);
        } finally {
            if (probe != null && wm != null) {
                try {
                    wm.removeView(probe);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static String errorText(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }
}