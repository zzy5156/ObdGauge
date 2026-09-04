package com.cloudwolf.obdgauge;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Prefs {

    private static final String FILE = "obd_gauge";
    public static final String KEY_FLOATING_ENABLED = "floating_enabled";
    public static final String KEY_AUTO_BOOT_ENABLED = "auto_boot_enabled";
    public static final String KEY_FONT_SIZE = "floating_font_size";
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
    /** 悬浮窗数值字体：system / 内置 assets 文件名 / custom。 */
    public static final String KEY_FLOAT_FONT = "float_font";
    /** 用户导入字体的展示名。 */
    public static final String KEY_CUSTOM_FONT_NAME = "custom_font_name";
    /** 悬浮窗小标签字体：system（系统默认）/ value（与数值一致）/ custom。 */
    public static final String KEY_FLOAT_LABEL_FONT = "float_label_font";
    /** 用户导入标签字体的展示名。 */
    public static final String KEY_CUSTOM_LABEL_FONT_NAME = "custom_label_font_name";

    public static final int DEFAULT_FONT_SIZE = 24;
    public static final int DEFAULT_COLUMNS = 2;
    public static final int DEFAULT_POS_X = 50;
    public static final int DEFAULT_POS_Y = 0;
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

    public static int getFontSize(Context context) {
        return sp(context).getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    public static void setFontSize(Context context, int size) {
        sp(context).edit().putInt(KEY_FONT_SIZE, size).apply();
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
        return clamp(sp(context).getInt(KEY_FLOAT_POS_X, DEFAULT_POS_X), 0, 100);
    }

    public static void setPosX(Context context, int pct) {
        sp(context).edit().putInt(KEY_FLOAT_POS_X, clamp(pct, 0, 100)).apply();
    }

    public static int getPosY(Context context) {
        return clamp(sp(context).getInt(KEY_FLOAT_POS_Y, DEFAULT_POS_Y), 0, 100);
    }

    public static void setPosY(Context context, int pct) {
        sp(context).edit().putInt(KEY_FLOAT_POS_Y, clamp(pct, 0, 100)).apply();
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
