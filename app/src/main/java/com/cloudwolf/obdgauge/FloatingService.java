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
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
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
    /** 字号下限：仪表屏分辨率低（常见 1280×480@320dpi），需要比中控小得多的字。 */
    private static final int MIN_FONT_SIZE = 6;
    /** 小标签字号下限，避免字号很小时标签被算成 0。 */
    private static final int MIN_LABEL_SIZE = 6;

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
    /** 悬浮窗所在屏幕的 Context：仪表投屏面时为其 DisplayContext（密度等资源随该屏）。 */
    private Context windowContext;
    /** 悬浮窗目标屏幕；null = 中控主屏。 */
    private Display targetDisplay;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
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
                    if (Prefs.KEY_FLOAT_DISPLAY_ID.equals(key)) {
                        // 目标屏幕改了（中控 ⇄ 仪表）：整个窗口搬到新屏幕重建
                        reattach();
                    } else if (Prefs.KEY_FLOAT_ITEMS.equals(key)
                            || Prefs.KEY_FLOAT_ORDER.equals(key)
                            || Prefs.KEY_FLOAT_COLUMNS.equals(key)
                            || Prefs.KEY_FLOAT_FONT.equals(key)
                            || Prefs.KEY_FLOAT_LABEL_FONT.equals(key)
                            || Prefs.KEY_FLOAT_STYLE.equals(key)
                            || Prefs.KEY_FLOAT_STYLE_ALT.equals(key)) {
                        // 样式影响分隔线尺寸与容器外观，整窗重建最省心
                        rebuildContent();
                        applyStyle();
                    } else if (Prefs.KEY_FONT_SIZE.equals(key)
                            || Prefs.KEY_FONT_SIZE_ALT.equals(key)
                            || Prefs.KEY_TEXT_COLOR.equals(key)
                            || Prefs.KEY_BG_COLOR.equals(key)
                            || Prefs.KEY_FLOAT_POS_X.equals(key)
                            || Prefs.KEY_FLOAT_POS_Y.equals(key)
                            || Prefs.KEY_FLOAT_POS_X_ALT.equals(key)
                            || Prefs.KEY_FLOAT_POS_Y_ALT.equals(key)) {
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

        registerDisplayListener();
        try {
            showFloating();
        } catch (Throwable ignored) {
            // 悬浮窗创建失败（无权限、机型差异、目标屏幕不可用等）不能拖垮进程，
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
        if (displayManager != null && displayListener != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Throwable ignored) {
            }
            displayListener = null;
        }
        if (client != null) {
            client.detach(dataListener);
        }
        removeFloatingView();
        floatView = null;
        windowManager = null;
        windowContext = null;
        targetDisplay = null;
        labelTvs.clear();
        valueTvs.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 创建悬浮窗：默认画在中控主屏；用户在「悬浮窗显示屏幕」里选了仪表投屏面时，
     * 用该屏的 DisplayContext 取到它自己的 WindowManager 再加窗（窗口类型仍是
     * TYPE_APPLICATION_OVERLAY）。车机若把仪表当私有屏幕、或加窗被拒，
     * 自动回退中控屏并把原因写进投屏诊断日志。
     */
    private void showFloating() {
        if (floatView != null) {
            return;
        }
        Context ctx = this;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display want = null;
        int wantId = Prefs.getFloatDisplayId(this);
        if (wantId != Display.DEFAULT_DISPLAY) {
            want = DisplayUtil.findDisplay(this, wantId, Prefs.getFloatDisplayName(this));
            if (want == null) {
                Prefs.appendScreenLog(this, "设置里的屏幕 " + wantId + " 当前不可见，回退中控屏");
            } else {
                try {
                    Context displayContext = createDisplayContext(want);
                    WindowManager displayWm =
                            (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
                    if (displayWm == null) {
                        Prefs.appendScreenLog(this, "屏幕 " + wantId + " 没有可用的 WindowManager，回退中控屏");
                        want = null;
                    } else {
                        ctx = displayContext;
                        wm = displayWm;
                    }
                } catch (Throwable t) {
                    Prefs.appendScreenLog(this, "屏幕 " + wantId + " 上下文创建失败："
                            + DisplayUtil.errorText(t) + "，回退中控屏");
                    want = null;
                }
            }
        }
        windowContext = ctx;
        windowManager = wm;
        targetDisplay = want;

        buildFloatingView();
        String error = null;
        try {
            windowManager.addView(floatView, buildParams());
        } catch (Throwable t) {
            error = DisplayUtil.errorText(t);
        }
        // 加窗没抛异常不代表真的挂上了（私有屏可能被静默拒绝），两种信号都查一下
        boolean attached = floatView.isAttachedToWindow() || floatView.getParent() != null;
        if (targetDisplay != null && (error != null || !attached)) {
            // 仪表投屏面不接受第三方窗口：回退中控屏并记录原因（车机上没 ADB，靠日志反馈）
            Prefs.appendScreenLog(this, "仪表屏投屏失败（"
                    + (error == null ? "窗口未挂载" : error) + "），已回退中控屏");
            removeFloatingView();
            floatView = null;
            windowContext = null;
            targetDisplay = null;
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            buildFloatingView();
            try {
                windowManager.addView(floatView, buildParams());
            } catch (Throwable t) {
                Prefs.appendScreenLog(this, "中控屏悬浮窗创建失败：" + DisplayUtil.errorText(t));
            }
        } else if (targetDisplay != null) {
            Prefs.appendScreenLog(this, "悬浮窗已投到仪表屏（屏幕 " + targetDisplay.getDisplayId()
                    + " · " + DisplayUtil.sizeText(targetDisplay)
                    + "）；若仪表上看不到，请先在原车导航里把导航投到仪表一次");
        }
        applyStyle();
    }

    /** 用当前屏幕的 Context 构建视图：仪表投屏面的密度与中控可能不同，dp/sp 要跟着该屏走。 */
    private void buildFloatingView() {
        floatView = new LinearLayout(ui());
        floatView.setOrientation(LinearLayout.VERTICAL);
        floatView.setGravity(Gravity.CENTER);
        applyContainerStyle();

        rebuildContent();
    }

    private boolean isInstrumentStyle() {
        return Prefs.isInstrumentStyle(this);
    }

    /**
     * 容器外观：卡片风＝深色圆角底 + 细边框 + 投影；仪表风＝完全透明、无边框、更小内边距
     * 配细字重——让悬浮窗看着像原厂仪表自己的一块信息，而不是扣上去的一个黑框。
     */
    private void applyContainerStyle() {
        if (floatView == null) {
            return;
        }
        if (isInstrumentStyle()) {
            floatView.setPadding(dp(6), dp(2), dp(6), dp(2));
            floatView.setBackground(null);
            floatView.setElevation(0f);
        } else {
            floatView.setPadding(dp(14), dp(6), dp(14), dp(6));
            floatView.setBackground(createBgDrawable());
            floatView.setElevation(dp(4));
        }
    }

    private WindowManager.LayoutParams buildParams() {
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
        if (targetDisplay != null) {
            // 仪表投屏面（附加屏）：按整屏坐标定位，避免系统装饰 inset 影响百分比换算
            params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        return params;
    }

    /** 当前悬浮窗所在屏幕的 Context；仪表屏时为其 DisplayContext。 */
    private Context ui() {
        return windowContext != null ? windowContext : this;
    }

    /** 目标屏幕变化（在 APP 里改设置、仪表屏插拔）时，把窗口搬到当前目标屏幕重建。 */
    private void reattach() {
        removeFloatingView();
        floatView = null;
        windowManager = null;
        windowContext = null;
        targetDisplay = null;
        try {
            showFloating();
        } catch (Throwable ignored) {
        }
    }

    private void removeFloatingView() {
        if (floatView != null && windowManager != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 监听屏幕插拔：车机熄火/休眠时仪表投屏面可能从系统里消失，重新上电后再出现；
     * 这里让悬浮窗自动跟着回中控屏、再回到仪表。
     */
    private void registerDisplayListener() {
        displayManager = DisplayUtil.manager(this);
        if (displayManager == null) {
            return;
        }
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY
                        && displayId == Prefs.getFloatDisplayId(FloatingService.this)) {
                    reattach();
                }
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (targetDisplay != null && targetDisplay.getDisplayId() == displayId) {
                    Prefs.appendScreenLog(FloatingService.this,
                            "仪表屏（屏幕 " + displayId + "）已移除，悬浮窗回到中控屏");
                    reattach();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
                // 分辨率/旋转变化由系统重新布局，无需重挂窗口
            }
        };
        try {
            displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            displayListener = null;
        }
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
        // 仪表风用更淡、更短的分隔线，贴合原厂仪表的细线观感
        boolean instrument = isInstrumentStyle();
        int dividerColor = instrument ? 0x33FFFFFF : 0x66FFFFFF;
        int dividerHeight = dp(instrument ? 20 : 30);
        int dividerMargin = dp(instrument ? 8 : 12);
        int rowGap = dp(instrument ? 6 : 8);

        for (int start = 0; start < shownItems.size(); start += columns) {
            int end = Math.min(start + columns, shownItems.size());
            if (start > 0) {
                View gap = new View(ui());
                LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(dp(1), rowGap);
                gap.setLayoutParams(gapLp);
                floatView.addView(gap);
            }
            LinearLayout row = new LinearLayout(ui());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // 竖向 LinearLayout 的默认子项宽度是 MATCH_PARENT，在 UNSPECIFIED
            // 测量规格下会把宽度 0 级联传给文本导致整窗塌缩，必须显式 WRAP_CONTENT
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            for (int i = start; i < end; i++) {
                if (i > start) {
                    View divider = new View(ui());
                    divider.setBackgroundColor(dividerColor);
                    LinearLayout.LayoutParams divLp =
                            new LinearLayout.LayoutParams(dp(1), dividerHeight);
                    divLp.setMargins(dividerMargin, 0, dividerMargin, 0);
                    divider.setLayoutParams(divLp);
                    row.addView(divider);
                }
                GaugeItem item = shownItems.get(i);
                TextView label = createLabel(getString(item.labelRes));
                TextView value = createValue();
                labelTvs.add(label);
                valueTvs.add(value);

                LinearLayout group = new LinearLayout(ui());
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
        TextView tv = new TextView(ui());
        tv.setText(text);
        tv.setTextColor(0xCCFFFFFF);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private TextView createValue() {
        TextView tv = new TextView(ui());
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

    /** 根据设置刷新容器外观、字体、字号与颜色。 */
    private void applyStyle() {
        if (floatView == null) {
            return;
        }
        int fontSize = Math.max(MIN_FONT_SIZE, Prefs.getFontSize(this));
        int textColor = Prefs.getTextColor(this);
        boolean instrument = isInstrumentStyle();
        // 仪表风且字体是「系统默认」时改用系统细体：原厂仪表是细字重，系统默认的粗体
        // 放上去明显不像原车风格；用户显式选过字体则尊重其选择
        String fontPref = Prefs.getFont(this);
        Typeface valueFace = instrument && FontUtil.SYSTEM.equals(fontPref)
                ? FontUtil.light() : FontUtil.resolve(this, fontPref);
        String labelPref = Prefs.getLabelFont(this);
        Typeface labelFace = instrument && FontUtil.SYSTEM.equals(labelPref)
                ? FontUtil.light() : FontUtil.resolveLabel(this, labelPref);

        for (TextView tv : valueTvs) {
            tv.setTextSize(fontSize);
            tv.setTextColor(textColor);
            tv.setTypeface(valueFace);
        }
        int labelAlpha = instrument ? 170 : 200;
        int labelColor = Color.argb(labelAlpha,
                Color.red(textColor), Color.green(textColor), Color.blue(textColor));
        int labelSize = Math.max(MIN_LABEL_SIZE, Math.round(fontSize * 0.62f));
        for (TextView tv : labelTvs) {
            tv.setTextSize(labelSize);
            tv.setTextColor(labelColor);
            tv.setTypeface(labelFace);
        }
        applyContainerStyle();
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
            if (targetDisplay != null) {
                // 仪表投屏面：窗口坐标以该屏为准（附加屏一般没有系统装饰，取真实尺寸）
                targetDisplay.getRealSize(screen);
            } else {
                windowManager.getDefaultDisplay().getSize(screen);
            }
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
        return Math.round(value * ui().getResources().getDisplayMetrics().density);
    }
}
