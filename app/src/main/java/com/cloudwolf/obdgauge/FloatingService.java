package com.cloudwolf.obdgauge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 悬浮窗服务：按用户配置的位置（屏幕百分比定位）与显示内容展示 OBD 数据。
 *
 * 悬浮窗使用 FLAG_NOT_TOUCHABLE，完全透传触摸事件，
 * 不影响车机原有界面操作；配合半透明深色底 + 高亮字保证辨识度。
 * 位置与内容均在 APP 内调节（悬浮窗不可触摸，无法拖动）。
 *
 * 窗口尺寸：部分旧车机（Android 5/6）的 WindowManager 对"垂直→水平→垂直"
 * 嵌套的 WRAP_CONTENT 窗口测量异常（多行时宽度塌缩），因此这里不依赖系统的
 * wrap_content 测量，而是显式测量内容后把窗口设为精确尺寸，
 * 内容 / 字号 / 数值文本变化时重新测量。
 */
public class FloatingService extends Service {

    private static final String CHANNEL_ID = "obd_gauge_floating";
    private static final int NOTIFICATION_ID = 1;

    /** 启动悬浮窗服务 */
    public static void start(Context context) {
        Intent intent = new Intent(context, FloatingService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // 后台启动受限等场景下静默失败
        }
    }

    /** 停止悬浮窗服务 */
    public static void stop(Context context) {
        context.stopService(new Intent(context, FloatingService.class));
    }

    private WindowManager windowManager;
    private LinearLayout floatView;
    private final List<GaugeItem> shownItems = new ArrayList<>();
    private final List<TextView> labelTvs = new ArrayList<>();
    private final List<TextView> valueTvs = new ArrayList<>();
    private Map<String, String> lastValues = Collections.emptyMap();

    private ObdClient client;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sharedPreferences, key) -> {
                try {
                    if (Prefs.KEY_FLOAT_ITEMS.equals(key)
                            || Prefs.KEY_FLOAT_ORDER.equals(key)
                            || Prefs.KEY_FLOAT_COLUMNS.equals(key)
                            || Prefs.KEY_FLOAT_FONT.equals(key)
                            || Prefs.KEY_FLOAT_LABEL_FONT.equals(key)) {
                        rebuildContent();
                        applyStyle();
                    } else if (Prefs.KEY_FONT_SIZE.equals(key)
                            || Prefs.KEY_TEXT_COLOR.equals(key)
                            || Prefs.KEY_BG_COLOR.equals(key)
                            || Prefs.KEY_FLOAT_POS_X.equals(key)
                            || Prefs.KEY_FLOAT_POS_Y.equals(key)) {
                        applyStyle();
                    }
                } catch (Throwable ignored) {
                    // 悬浮窗异常不能拖垮进程
                }
            };

    private final ObdClient.Listener dataListener = new ObdClient.Listener() {
        @Override
        public void onObdData(Map<String, String> values) {
            lastValues = values;
            try {
                updateValues();
            } catch (Throwable ignored) {
                // 悬浮窗异常不能拖垮进程
            }
        }

        @Override
        public void onObdState(String message, boolean connected) {
            // 悬浮窗不展示状态文字，避免闪烁；仅数据异常时置灰
            float alpha = connected ? 1f : 0.6f;
            for (TextView tv : valueTvs) {
                tv.setAlpha(alpha);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        CrashGuard.install(this);
        preferences = getSharedPreferences("obd_gauge", Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(prefsListener);

        client = ObdClient.get(this);
        client.attach(dataListener);

        try {
            showFloating();
        } catch (Throwable ignored) {
            // 悬浮窗创建失败（无权限、机型差异等）不能拖垮进程，
            // 前台服务仍在，用户可关闭再打开悬浮窗开关重试
        }
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (client != null) {
            client.detach(dataListener);
        }
        if (floatView != null && windowManager != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Exception ignored) {
            }
        }
        floatView = null;
        labelTvs.clear();
        valueTvs.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showFloating() {
        if (floatView != null) {
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        floatView = new LinearLayout(this);
        floatView.setOrientation(LinearLayout.VERTICAL);
        floatView.setGravity(Gravity.CENTER);
        floatView.setPadding(dp(14), dp(6), dp(14), dp(6));
        floatView.setBackground(createBgDrawable());
        floatView.setElevation(dp(4));

        rebuildContent();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params.format = PixelFormat.TRANSLUCENT;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        // 不拦截任何触摸事件，不影响原界面操作
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;

        try {
            windowManager.addView(floatView, params);
        } catch (Exception ignored) {
            // 无悬浮窗权限等场景
        }
        applyStyle();
    }

    /** 按「显示项 + 每行项数」重建内容；每行各项之间加竖分隔线。 */
    private void rebuildContent() {
        if (floatView == null) {
            return;
        }
        floatView.removeAllViews();
        labelTvs.clear();
        valueTvs.clear();
        shownItems.clear();

        shownItems.addAll(Prefs.getOrderedFloatItems(this));
        if (shownItems.isEmpty()) {
            shownItems.add(GaugeItem.RPM);
        }
        int columns = Prefs.getColumns(this);

        for (int start = 0; start < shownItems.size(); start += columns) {
            int end = Math.min(start + columns, shownItems.size());
            if (start > 0) {
                View gap = new View(this);
                LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(dp(1), dp(8));
                gap.setLayoutParams(gapLp);
                floatView.addView(gap);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // 竖向 LinearLayout 的默认子项宽度是 MATCH_PARENT，在 UNSPECIFIED
            // 测量规格下会把宽度 0 级联传给文本导致整窗塌缩，必须显式 WRAP_CONTENT
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            for (int i = start; i < end; i++) {
                if (i > start) {
                    View divider = new View(this);
                    divider.setBackgroundColor(0x66FFFFFF);
                    LinearLayout.LayoutParams divLp =
                            new LinearLayout.LayoutParams(dp(1), dp(30));
                    divLp.setMargins(dp(12), 0, dp(12), 0);
                    divider.setLayoutParams(divLp);
                    row.addView(divider);
                }
                GaugeItem item = shownItems.get(i);
                TextView label = createLabel(getString(item.labelRes));
                TextView value = createValue();
                labelTvs.add(label);
                valueTvs.add(value);

                LinearLayout group = new LinearLayout(this);
                group.setOrientation(LinearLayout.VERTICAL);
                group.setGravity(Gravity.CENTER_HORIZONTAL);
                group.addView(label);
                group.addView(value);
                row.addView(group);
            }
            floatView.addView(row, rowLp);
        }
        updateValues();
    }

    private android.graphics.drawable.Drawable createBgDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Prefs.getBgColor(this));
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), 0x33FFFFFF);
        return drawable;
    }

    private TextView createLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xCCFFFFFF);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private TextView createValue() {
        TextView tv = new TextView(this);
        tv.setText("--");
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private void updateValues() {
        for (int i = 0; i < shownItems.size() && i < valueTvs.size(); i++) {
            String text = lastValues.get(shownItems.get(i).id);
            valueTvs.get(i).setText(text == null ? "--" : text);
        }
        // 数值文本宽度可能变化，窗口是精确尺寸，需要随之重测
        relayoutWindow();
    }

    /** 根据设置刷新字体、字号与颜色。 */
    private void applyStyle() {
        if (floatView == null) {
            return;
        }
        int fontSize = Math.max(12, Prefs.getFontSize(this));
        int textColor = Prefs.getTextColor(this);
        int bgColor = Prefs.getBgColor(this);
        Typeface valueFace = FontUtil.resolve(this, Prefs.getFont(this));
        Typeface labelFace = FontUtil.resolveLabel(this, Prefs.getLabelFont(this));

        for (TextView tv : valueTvs) {
            tv.setTextSize(fontSize);
            tv.setTextColor(textColor);
            tv.setTypeface(valueFace);
        }
        int labelColor = Color.argb(200, Color.red(textColor), Color.green(textColor), Color.blue(textColor));
        for (TextView tv : labelTvs) {
            tv.setTextSize(Math.max(9, fontSize * 6 / 10));
            tv.setTextColor(labelColor);
            tv.setTypeface(labelFace);
        }
        if (floatView.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) floatView.getBackground()).setColor(bgColor);
        }
        relayoutWindow();
    }

    /**
     * 显式测量内容并把窗口设为精确尺寸，同时按屏幕百分比定位：
     * x/y =（屏幕尺寸 − 窗口尺寸）× 百分比。默认水平 50%、垂直 0%，
     * 即旧版的"顶部居中"。
     */
    private void relayoutWindow() {
        if (floatView == null || floatView.getParent() == null || windowManager == null) {
            return;
        }
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        try {
            floatView.measure(spec, spec);
        } catch (Exception ignored) {
            return;
        }
        int w = floatView.getMeasuredWidth();
        int h = floatView.getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Point screen = new Point();
        try {
            windowManager.getDefaultDisplay().getSize(screen);
        } catch (Exception ignored) {
            return;
        }
        int x = Math.round((screen.x - w) * Prefs.getPosX(this) / 100f);
        int y = Math.round((screen.y - h) * Prefs.getPosY(this) / 100f);
        x = Math.max(0, Math.min(x, Math.max(0, screen.x - w)));
        y = Math.max(0, Math.min(y, Math.max(0, screen.y - h)));

        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) floatView.getLayoutParams();
        if (lp.width != w || lp.height != h || lp.x != x || lp.y != y) {
            lp.width = w;
            lp.height = h;
            lp.x = x;
            lp.y = y;
            try {
                windowManager.updateViewLayout(floatView, lp);
            } catch (Exception ignored) {
            }
        }
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "车况悬浮窗", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
