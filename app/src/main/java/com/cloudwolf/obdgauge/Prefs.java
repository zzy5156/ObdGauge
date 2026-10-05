package com.cloudwolf.obdgauge;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.Display;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Prefs {

    private static final String FILE = "obd_gauge";
    public static final String KEY_FLOATING_ENABLED = "floating_enabled";
    public static final String KEY_AUTO_BOOT_ENABLED = "auto_boot_enabled";
    public static final String KEY_FONT_SIZE = "floating_font_size";
    /** 仪表屏（附加屏）的独立字号记忆：低分辨率屏用同样的 sp 会比中控显大。 */
    public static final String KEY_FONT_SIZE_ALT = "floating_font_size_alt";
    /** 悬浮窗样式：card（卡片）/ instrument（仪表风），按屏幕分别记忆。 */
    public static final String KEY_FLOAT_STYLE = "float_style";
    public static final String KEY_FLOAT_STYLE_ALT = "float_style_alt";
    public static final String KEY_TEXT_COLOR = "floating_text_color";
    public static final String KEY_BG_COLOR = "floating_bg_color";
    public static final String KEY_OBD_ADDRESS = "obd_address";
    public static final String KEY_FUEL_CALIB = "fuel_calib";
    /** 功率补偿倍率（显示值 = 解码值 × 倍率，用于对齐仪表）。 */
    public static final String KEY_POWER_CALIB = "power_calib";
    /** 悬浮窗显示的数据项 id 集合（StringSet）。 */
    public static final String KEY_FLOAT_ITEMS = "float_items";
    /** 悬浮窗数据项显示顺序（逗号分隔的 id 列表，缺省项按枚举顺序追加在末尾）。 */
    public static final String KEY_FLOAT_ORDER = "float_items_order";
    /** 功率取值方式记忆（0=未记录；1..4 对应探测链各方式，成功后写入，启动时直接使用）。 */
    public static final String KEY_POWER_METHOD = "power_method";
    /** SOC 取值来源记忆（0=未记录；1=比亚迪 221FFC；2=标准 015B）。 */
    public static final String KEY_SOC_SOURCE = "soc_source";
    /** 悬浮窗每行最多显示的数据项数（1–4）。 */
    public static final String KEY_FLOAT_COLUMNS = "float_columns";
    /** 悬浮窗水平位置（屏幕可用宽度的百分比 0–100）。 */
    public static final String KEY_FLOAT_POS_X = "float_pos_x";
    /** 悬浮窗垂直位置（屏幕可用高度的百分比 0–100）。 */
    public static final String KEY_FLOAT_POS_Y = "float_pos_y";
    /** 悬浮窗目标屏幕 id（0 = 中控主屏；其余为仪表投屏面等附加屏）。 */
    public static final String KEY_FLOAT_DISPLAY_ID = "float_display_id";
    /** 目标屏幕名（display id 会随固件/重启变化，按 id 找不到时用名字回退匹配）。 */
    public static final String KEY_FLOAT_DISPLAY_NAME = "float_display_name";
    /** 附加屏（仪表投屏面）的独立位置记忆：与中控屏分开记。 */
    public static final String KEY_FLOAT_POS_X_ALT = "float_pos_x_alt";
    public static final String KEY_FLOAT_POS_Y_ALT = "float_pos_y_alt";
    /** 投屏诊断日志（最新在前；车机上没有 ADB，这是唯一能把现场带回来的信息）。 */
    public static final String KEY_SCREEN_LOG = "screen_log";
    /** 悬浮窗数值字体：system / 内置 assets 文件名 / custom。 */
    public static final String KEY_FLOAT_FONT = "float_font";
    /** 用户导入字体的展示名。 */
    public static final String KEY_CUSTOM_FONT_NAME = "custom_font_name";
    /** 悬浮窗小标签字体：system（系统默认）/ value（与数值一致）/ custom。 */
    public static final String KEY_FLOAT_LABEL_FONT = "float_label_font";
    /** 用户导入标签字体的展示名。 */
    public static final String KEY_CUSTOM_LABEL_FONT_NAME = "custom_label_font_name";

    public static final int DEFAULT_FONT_SIZE = 24;
    /** 仪表屏默认字号：仪表屏分辨率低（常见 1280×480@320dpi），同样的 sp 会比中控显大。 */
    public static final int DEFAULT_FONT_SIZE_ALT = 10;
    /** 悬浮窗样式取值：中控屏默认卡片，仪表屏默认仪表风。 */
    public static final String STYLE_CARD = "card";
    public static final String STYLE_INSTRUMENT = "instrument";
    public static final int DEFAULT_COLUMNS = 2;
    public static final int DEFAULT_POS_X = 50;
    public static final int DEFAULT_POS_Y = 0;
    /** 仪表投屏面的默认位置：贴底居中（仪表上悬浮窗默认在底部，仍可随时调）。 */
    public static final int DEFAULT_POS_X_ALT = 50;
    public static final int DEFAULT_POS_Y_ALT = 100;
    /** 投屏诊断日志最多保留的条数。 */
    private static final int SCREEN_LOG_MAX_LINES = 16;
    public static final String DEFAULT_FONT = "system";
    /** 油量校准系数默认 1.0（不校准）。 */
    public static final float FUEL_CALIB_DEFAULT = 1.0f;
    /** 功率补偿倍率默认 1.0（不校准）。 */
    public static final float POWER_CALIB_DEFAULT = 1.0f;

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isFloatingEnabled(Context context) {
        return sp(context).getBoolean(KEY_FLOATING_ENABLED, false);
    }

    public static void setFloatingEnabled(Context context, boolean enabled) {
        sp(context).edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply();
    }

    public static boolean isAutoBootEnabled(Context context) {
        return sp(context).getBoolean(KEY_AUTO_BOOT_ENABLED, false);
    }

    public static void setAutoBootEnabled(Context context, boolean enabled) {
        sp(context).edit().putBoolean(KEY_AUTO_BOOT_ENABLED, enabled).apply();
    }

    /** 悬浮窗数值字号（按屏幕分别记忆：仪表屏默认更小）。 */
    public static int getFontSize(Context context) {
        boolean alt = isSecondaryDisplay(context);
        return sp(context).getInt(alt ? KEY_FONT_SIZE_ALT : KEY_FONT_SIZE,
                alt ? DEFAULT_FONT_SIZE_ALT : DEFAULT_FONT_SIZE);
    }

    public static void setFontSize(Context context, int size) {
        sp(context).edit().putInt(isSecondaryDisplay(context) ? KEY_FONT_SIZE_ALT : KEY_FONT_SIZE, size).apply();
    }

    /** 悬浮窗样式（按屏幕分别记忆）：中控默认卡片，仪表默认仪表风。 */
    public static String getFloatStyle(Context context) {
        boolean alt = isSecondaryDisplay(context);
        String def = alt ? STYLE_INSTRUMENT : STYLE_CARD;
        String saved = sp(context).getString(alt ? KEY_FLOAT_STYLE_ALT : KEY_FLOAT_STYLE, def);
        return saved == null ? def : saved;
    }

    public static void setFloatStyle(Context context, String style) {
        sp(context).edit()
                .putString(isSecondaryDisplay(context) ? KEY_FLOAT_STYLE_ALT : KEY_FLOAT_STYLE, style)
                .apply();
    }

    /** 当前屏幕是否用仪表风（透明底、细字重、淡分隔线）。 */
    public static boolean isInstrumentStyle(Context context) {
        return STYLE_INSTRUMENT.equals(getFloatStyle(context));
    }

    public static int getTextColor(Context context) {
        return sp(context).getInt(KEY_TEXT_COLOR, 0xFFFFFFFF);
    }

    public static void setTextColor(Context context, int color) {
        sp(context).edit().putInt(KEY_TEXT_COLOR, color).apply();
    }

    public static int getBgColor(Context context) {
        return sp(context).getInt(KEY_BG_COLOR, 0xB3000000);
    }

    public static void setBgColor(Context context, int color) {
        sp(context).edit().putInt(KEY_BG_COLOR, color).apply();
    }

    public static String getObdAddress(Context context) {
        return sp(context).getString(KEY_OBD_ADDRESS, null);
    }

    public static void setObdAddress(Context context, String address) {
        sp(context).edit().putString(KEY_OBD_ADDRESS, address).apply();
    }

    public static float getFuelCalib(Context context) {
        return sp(context).getFloat(KEY_FUEL_CALIB, FUEL_CALIB_DEFAULT);
    }

    public static void setFuelCalib(Context context, float factor) {
        sp(context).edit().putFloat(KEY_FUEL_CALIB, factor).apply();
    }

    /** 功率补偿倍率（0.3–3.0，默认 1.0 不校准）。 */
    public static float getPowerCalib(Context context) {
        return sp(context).getFloat(KEY_POWER_CALIB, POWER_CALIB_DEFAULT);
    }

    public static void setPowerCalib(Context context, float factor) {
        sp(context).edit().putFloat(KEY_POWER_CALIB, factor).apply();
    }

    /** 功率取值方式记忆（0=未记录，探测成功后写入，下次启动直接按此方式读取）。 */
    public static int getPowerMethod(Context context) {
        return sp(context).getInt(KEY_POWER_METHOD, 0);
    }

    public static void setPowerMethod(Context context, int method) {
        sp(context).edit().putInt(KEY_POWER_METHOD, method).apply();
    }

    /** SOC 取值来源记忆（0=未记录，成功后写入，下次启动直接按此来源读取）。 */
    public static int getSocSource(Context context) {
        return sp(context).getInt(KEY_SOC_SOURCE, 0);
    }

    public static void setSocSource(Context context, int source) {
        sp(context).edit().putInt(KEY_SOC_SOURCE, source).apply();
    }

    /** 悬浮窗当前显示的数据项 id 集合，默认转速 + 油量。 */
    public static Set<String> getFloatItems(Context context) {
        Set<String> saved = sp(context).getStringSet(KEY_FLOAT_ITEMS, null);
        if (saved == null || saved.isEmpty()) {
            Set<String> def = new LinkedHashSet<>();
            def.add(GaugeItem.RPM.id);
            def.add(GaugeItem.FUEL.id);
            return def;
        }
        // getStringSet 返回的集合只读且顺序不定，复制一份保证可写与稳定顺序
        return new LinkedHashSet<>(saved);
    }

    public static void setFloatItems(Context context, Set<String> ids) {
        sp(context).edit().putStringSet(KEY_FLOAT_ITEMS, new LinkedHashSet<>(ids)).apply();
    }

    /** 用户自定义的显示顺序（原始 id 列表，可能包含已取消勾选的项）。 */
    public static List<String> getFloatOrder(Context context) {
        String saved = sp(context).getString(KEY_FLOAT_ORDER, null);
        List<String> out = new ArrayList<>();
        if (saved != null && !saved.isEmpty()) {
            for (String part : saved.split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    public static void setFloatOrder(Context context, List<String> ids) {
        sp(context).edit().putString(KEY_FLOAT_ORDER, TextUtils.join(",", ids)).apply();
    }

    /**
     * 悬浮窗当前显示项，按用户排序：先按自定义顺序列出已启用项，
     * 未出现在顺序表中的启用项按枚举声明顺序追加在末尾。
     */
    public static List<GaugeItem> getOrderedFloatItems(Context context) {
        Set<String> enabled = getFloatItems(context);
        List<GaugeItem> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String id : getFloatOrder(context)) {
            GaugeItem item = GaugeItem.byId(id);
            if (item != null && enabled.contains(id) && added.add(id)) {
                out.add(item);
            }
        }
        for (GaugeItem item : GaugeItem.values()) {
            if (enabled.contains(item.id) && added.add(item.id)) {
                out.add(item);
            }
        }
        return out;
    }

    public static int getColumns(Context context) {
        return clamp(sp(context).getInt(KEY_FLOAT_COLUMNS, DEFAULT_COLUMNS), 1, 4);
    }

    public static void setColumns(Context context, int columns) {
        sp(context).edit().putInt(KEY_FLOAT_COLUMNS, clamp(columns, 1, 4)).apply();
    }

    public static int getPosX(Context context) {
        boolean alt = isSecondaryDisplay(context);
        return clamp(sp(context).getInt(alt ? KEY_FLOAT_POS_X_ALT : KEY_FLOAT_POS_X,
                alt ? DEFAULT_POS_X_ALT : DEFAULT_POS_X), 0, 100);
    }

    public static void setPosX(Context context, int pct) {
        sp(context).edit().putInt(isSecondaryDisplay(context) ? KEY_FLOAT_POS_X_ALT : KEY_FLOAT_POS_X,
                clamp(pct, 0, 100)).apply();
    }

    public static int getPosY(Context context) {
        boolean alt = isSecondaryDisplay(context);
        return clamp(sp(context).getInt(alt ? KEY_FLOAT_POS_Y_ALT : KEY_FLOAT_POS_Y,
                alt ? DEFAULT_POS_Y_ALT : DEFAULT_POS_Y), 0, 100);
    }

    public static void setPosY(Context context, int pct) {
        sp(context).edit().putInt(isSecondaryDisplay(context) ? KEY_FLOAT_POS_Y_ALT : KEY_FLOAT_POS_Y,
                clamp(pct, 0, 100)).apply();
    }

    // ------------------------------------------------------------------ 悬浮窗目标屏幕（中控 / 仪表）

    /** 悬浮窗目标屏幕 id（0 = 中控主屏）。 */
    public static int getFloatDisplayId(Context context) {
        return sp(context).getInt(KEY_FLOAT_DISPLAY_ID, Display.DEFAULT_DISPLAY);
    }

    public static void setFloatDisplayId(Context context, int displayId) {
        sp(context).edit().putInt(KEY_FLOAT_DISPLAY_ID, displayId).apply();
    }

    public static String getFloatDisplayName(Context context) {
        String name = sp(context).getString(KEY_FLOAT_DISPLAY_NAME, "");
        return name == null ? "" : name;
    }

    public static void setFloatDisplayName(Context context, String name) {
        sp(context).edit().putString(KEY_FLOAT_DISPLAY_NAME, name == null ? "" : name).apply();
    }

    /** 是否选了中控屏以外的屏幕（即仪表投屏面）。 */
    public static boolean isSecondaryDisplay(Context context) {
        return getFloatDisplayId(context) != Display.DEFAULT_DISPLAY;
    }

    public static String getScreenLog(Context context) {
        String log = sp(context).getString(KEY_SCREEN_LOG, "");
        return log == null ? "" : log;
    }

    /**
     * 追加一条投屏记录（最新在前，最多保留 {@link #SCREEN_LOG_MAX_LINES} 条）。
     * 车机上无法连 ADB，用户可直接在「诊断日志」里复制这些记录反馈适配。
     */
    public static void appendScreenLog(Context context, String line) {
        if (TextUtils.isEmpty(line)) {
            return;
        }
        try {
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String old = getScreenLog(context);
            StringBuilder sb = new StringBuilder(stamp).append(' ').append(line);
            int lines = 1;
            if (!old.isEmpty()) {
                for (String l : old.split("\n")) {
                    if (lines >= SCREEN_LOG_MAX_LINES) {
                        break;
                    }
                    sb.append('\n').append(l);
                    lines++;
                }
            }
            sp(context).edit().putString(KEY_SCREEN_LOG, sb.toString()).apply();
        } catch (Throwable ignored) {
            // 日志失败不能影响投屏本身
        }
    }

    public static void clearScreenLog(Context context) {
        sp(context).edit().remove(KEY_SCREEN_LOG).apply();
    }

    public static String getFont(Context context) {
        return sp(context).getString(KEY_FLOAT_FONT, DEFAULT_FONT);
    }

    public static void setFont(Context context, String font) {
        sp(context).edit().putString(KEY_FLOAT_FONT, font).apply();
    }

    public static void setCustomFontName(Context context, String name) {
        sp(context).edit().putString(KEY_CUSTOM_FONT_NAME, name).apply();
    }

    public static String getLabelFont(Context context) {
        return sp(context).getString(KEY_FLOAT_LABEL_FONT, "system");
    }

    public static void setLabelFont(Context context, String font) {
        sp(context).edit().putString(KEY_FLOAT_LABEL_FONT, font).apply();
    }

    public static void setCustomLabelFontName(Context context, String name) {
        sp(context).edit().putString(KEY_CUSTOM_LABEL_FONT_NAME, name).apply();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
