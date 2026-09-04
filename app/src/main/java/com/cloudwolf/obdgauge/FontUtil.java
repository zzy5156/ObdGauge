package com.cloudwolf.obdgauge;

import android.content.Context;
import android.graphics.Typeface;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 悬浮窗字体解析：系统默认 / 内置 assets 免费商用字体 / 用户导入的字体文件。
 *
 * 内置字体仅作用于数值大字；小标签始终用系统字体，避免纯西文字体缺中文
 * 字形时标签显示"豆腐块"。
 */
public final class FontUtil {

    /** 系统默认字体的偏好值。 */
    public static final String SYSTEM = "system";
    /** 用户导入字体的偏好值。 */
    public static final String CUSTOM = "custom";
    /** 标签字体：跟随数值字体。 */
    public static final String LABEL_VALUE = "value";

    private static final String ASSET_DIR = "fonts";
    private static final String CUSTOM_FILE = "custom_font.ttf";
    private static final String CUSTOM_LABEL_FILE = "custom_font_label.ttf";
    private static final String LABEL_CACHE_KEY = "label_custom";

    /** 内置字体注册表（assets 文件名 → 展示名），全部允许免费商用。 */
    private static final Map<String, String> BUILTIN = new LinkedHashMap<>();

    static {
        BUILTIN.put("Alibaba-PuHuiTi-Regular.ttf", "阿里巴巴普惠体（免费商用）");
        BUILTIN.put("OPPOSans-R.ttf", "OPPO Sans（免费商用）");
        BUILTIN.put("Orbitron.ttf", "Orbitron（数字风）");
    }

    private static final Map<String, Typeface> CACHE = new LinkedHashMap<>();

    private FontUtil() {
    }

    /** 实际存在的内置字体（文件名 → 展示名）。 */
    public static Map<String, String> builtinFonts(Context context) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] assets = null;
        try {
            assets = context.getAssets().list(ASSET_DIR);
        } catch (Exception ignored) {
        }
        for (Map.Entry<String, String> e : BUILTIN.entrySet()) {
            if (assets == null || contains(assets, e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static boolean contains(String[] arr, String value) {
        for (String s : arr) {
            if (s.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** 用户导入字体存放位置。 */
    public static File customFile(Context context) {
        return new File(context.getFilesDir(), CUSTOM_FILE);
    }

    /** 用户导入的标签字体存放位置。 */
    public static File customLabelFile(Context context) {
        return new File(context.getFilesDir(), CUSTOM_LABEL_FILE);
    }

    /** 自定义字体的展示名（设置时保存的原始文件名），未导入过返回 null。 */
    public static String customName(Context context) {
        return context.getSharedPreferences("obd_gauge", Context.MODE_PRIVATE)
                .getString(Prefs.KEY_CUSTOM_FONT_NAME, null);
    }

    /** 自定义标签字体的展示名，未导入过返回 null。 */
    public static String customLabelName(Context context) {
        return context.getSharedPreferences("obd_gauge", Context.MODE_PRIVATE)
                .getString(Prefs.KEY_CUSTOM_LABEL_FONT_NAME, null);
    }

    /**
     * 解析小标签字体：system → 系统默认；value → 与数值字体一致；
     * custom → 用户导入的标签字体文件。失败时回退系统默认。
     */
    public static Typeface resolveLabel(Context context, String pref) {
        if (LABEL_VALUE.equals(pref)) {
            return resolve(context, Prefs.getFont(context));
        }
        if (CUSTOM.equals(pref)) {
            Typeface cached = CACHE.get(LABEL_CACHE_KEY);
            if (cached != null) {
                return cached;
            }
            Typeface t = null;
            try {
                File f = customLabelFile(context);
                if (f.exists() && f.length() > 0) {
                    t = Typeface.createFromFile(f);
                }
            } catch (Throwable ignored) {
            }
            if (t == null) {
                t = Typeface.DEFAULT;
            }
            CACHE.put(LABEL_CACHE_KEY, t);
            return t;
        }
        return Typeface.DEFAULT;
    }

    /** 按偏好值解析数值字体，失败时回退系统粗体。 */
    public static Typeface resolve(Context context, String pref) {
        Typeface cached = CACHE.get(pref);
        if (cached != null) {
            return cached;
        }
        Typeface t = null;
        try {
            if (CUSTOM.equals(pref)) {
                File f = customFile(context);
                if (f.exists() && f.length() > 0) {
                    t = Typeface.createFromFile(f);
                }
            } else if (!SYSTEM.equals(pref) && BUILTIN.containsKey(pref)) {
                t = Typeface.createFromAsset(context.getAssets(), ASSET_DIR + "/" + pref);
            }
        } catch (Throwable ignored) {
        }
        if (t == null) {
            t = Typeface.DEFAULT_BOLD;
        }
        CACHE.put(pref, t);
        return t;
    }

    /** 导入/替换自定义字体后清掉缓存，让新文件立即生效。 */
    public static void invalidateCustom() {
        CACHE.remove(CUSTOM);
    }

    /** 导入/替换自定义标签字体后清掉缓存。 */
    public static void invalidateCustomLabel() {
        CACHE.remove(LABEL_CACHE_KEY);
    }
}
