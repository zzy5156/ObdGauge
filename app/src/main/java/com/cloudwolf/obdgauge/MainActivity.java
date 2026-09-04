package com.cloudwolf.obdgauge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CODE_OVERLAY = 101;
    private static final int REQ_CODE_BT = 102;
    private static final int REQ_CODE_FONT = 103;
    private static final int REQ_CODE_FONT_LABEL = 104;

    private static final int FONT_MIN = 12;
    private static final int FONT_MAX = 48;
    private static final int FONT_STEP = 2;

    private LinearLayout mainMetricsBox;
    private final Map<String, TextView> mainValueTvs = new LinkedHashMap<>();
    private TextView stateTv;
    private TextView deviceValueTv;
    private TextView fuelCalibValueTv;
    private TextView powerCalibValueTv;
    private TextView itemsValueTv;
    private TextView sortValueTv;
    private TextView columnsValueTv;
    private TextView fontValueTv;
    private TextView labelFontValueTv;
    private TextView fontSizeValueTv;
    private TextView posXValueTv;
    private TextView posYValueTv;
    private androidx.appcompat.widget.SwitchCompat floatingSwitch;
    private androidx.appcompat.widget.SwitchCompat autoBootSwitch;
    private SeekBar fontSeekBar;
    private SeekBar posXSeekBar;
    private SeekBar posYSeekBar;

    private ObdClient client;

    /** 功率诊断弹窗每进程只提示一次。 */
    private boolean powerDebugShown = false;

    private final ObdClient.Listener dataListener = new ObdClient.Listener() {
        @Override
        public void onObdData(Map<String, String> values) {
            runOnUiThread(() -> {
                for (Map.Entry<String, TextView> e : mainValueTvs.entrySet()) {
                    String text = values.get(e.getKey());
                    e.getValue().setText(text == null ? "--" : text);
                }
                maybeShowPowerDebug(values);
            });
        }

        @Override
        public void onObdState(String message, boolean connected) {
            runOnUiThread(() -> {
                if (stateTv != null) {
                    stateTv.setText(message);
                    stateTv.setTextColor(connected
                            ? ContextCompat.getColor(MainActivity.this, R.color.text_secondary)
                            : ContextCompat.getColor(MainActivity.this, R.color.accent));
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        setContentView(R.layout.activity_main);

        mainMetricsBox = findViewById(R.id.ll_main_metrics);
        stateTv = findViewById(R.id.tv_obd_state);
        deviceValueTv = findViewById(R.id.tv_device_value);
        fuelCalibValueTv = findViewById(R.id.tv_fuel_calib_value);
        powerCalibValueTv = findViewById(R.id.tv_power_calib_value);
        itemsValueTv = findViewById(R.id.tv_items_value);
        sortValueTv = findViewById(R.id.tv_sort_value);
        columnsValueTv = findViewById(R.id.tv_columns_value);
        fontValueTv = findViewById(R.id.tv_font_value);
        labelFontValueTv = findViewById(R.id.tv_label_font_value);
        posXValueTv = findViewById(R.id.tv_pos_x_value);
        posYValueTv = findViewById(R.id.tv_pos_y_value);
        floatingSwitch = findViewById(R.id.switch_floating);
        autoBootSwitch = findViewById(R.id.switch_auto_boot);
        fontSeekBar = findViewById(R.id.seek_font_size);
        fontSizeValueTv = findViewById(R.id.tv_font_size_value);
        posXSeekBar = findViewById(R.id.seek_pos_x);
        posYSeekBar = findViewById(R.id.seek_pos_y);
        findViewById(R.id.btn_font_minus).setOnClickListener(v -> changeFont(-FONT_STEP));
        findViewById(R.id.btn_font_plus).setOnClickListener(v -> changeFont(FONT_STEP));
        findViewById(R.id.row_obd_device).setOnClickListener(v -> pickDevice());
        findViewById(R.id.row_fuel_calib).setOnClickListener(v -> showFuelCalibDialog());
        findViewById(R.id.row_power_calib).setOnClickListener(v -> showPowerCalibDialog());
        findViewById(R.id.row_float_items).setOnClickListener(v -> showItemsDialog());
        findViewById(R.id.row_float_sort).setOnClickListener(v -> showSortDialog());
        findViewById(R.id.row_float_columns).setOnClickListener(v -> showColumnsDialog());
        findViewById(R.id.row_float_font).setOnClickListener(v -> showFontDialog());
        findViewById(R.id.row_float_label_font).setOnClickListener(v -> showLabelFontDialog());

        client = ObdClient.get(this);
        initControls();

        // 展示上次崩溃记录（若有）：车机没有 ADB，弹窗是唯一的现场反馈途径
        final String crash = CrashGuard.readLast(this);
        if (crash != null) {
            mainMetricsBox.post(() -> showCrashDialog(crash));
        }
    }

    private void showCrashDialog(String crash) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_dialog_title)
                .setMessage(crash)
                .setPositiveButton(R.string.crash_clear, (dialog, which) -> CrashGuard.clear(this))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        client.attach(dataListener);
        // Android 5.x 无 canDrawOverlays（API 23+），该版本悬浮窗权限随安装默认授予
        boolean overlayGranted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean floatingOn = Prefs.isFloatingEnabled(this) && overlayGranted;
        floatingSwitch.setOnCheckedChangeListener(null);
        floatingSwitch.setChecked(floatingOn);
        floatingSwitch.setOnCheckedChangeListener(floatingListener);
        // 先设状态再挂监听，避免每次进界面都误触发一次"已开启开机自启"
        autoBootSwitch.setOnCheckedChangeListener(null);
        autoBootSwitch.setChecked(Prefs.isAutoBootEnabled(this));
        autoBootSwitch.setOnCheckedChangeListener(autoBootListener);
        refreshDeviceLabel();
        refreshFuelCalibLabel();
        refreshPowerCalibLabel();
        refreshItemsLabel();
        refreshSortLabel();
        refreshColumnsLabel();
        refreshFontLabel();
        refreshLabelFontLabel();
        rebuildMainMetrics();
        // 悬浮窗开关为开：应用启动即自动拉起（车机开机自启受限场景的兜底）
        if (floatingOn) {
            FloatingService.start(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        client.detach(dataListener);
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    private void initControls() {
        floatingSwitch.setOnCheckedChangeListener(floatingListener);

        autoBootSwitch.setOnCheckedChangeListener(autoBootListener);

        fontSeekBar.setMax(FONT_MAX - FONT_MIN);
        int fontSize = clampFont(Prefs.getFontSize(this));
        fontSeekBar.setProgress(fontSize - FONT_MIN);
        updateFontLabel(fontSize);
        fontSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    applyFontSize(progress + FONT_MIN);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        posXSeekBar.setMax(100);
        posYSeekBar.setMax(100);
        posXSeekBar.setProgress(Prefs.getPosX(this));
        posYSeekBar.setProgress(Prefs.getPosY(this));
        updatePosLabels();
        posXSeekBar.setOnSeekBarChangeListener(posListener);
        posYSeekBar.setOnSeekBarChangeListener(posListener);
    }

    private final SeekBar.OnSeekBarChangeListener posListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser) {
                return;
            }
            if (seekBar == posXSeekBar) {
                Prefs.setPosX(MainActivity.this, progress);
            } else if (seekBar == posYSeekBar) {
                Prefs.setPosY(MainActivity.this, progress);
            }
            updatePosLabels();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    };

    private void updatePosLabels() {
        posXValueTv.setText(getString(R.string.pos_value, Prefs.getPosX(this)));
        posYValueTv.setText(getString(R.string.pos_value, Prefs.getPosY(this)));
    }

    private final androidx.appcompat.widget.SwitchCompat.OnCheckedChangeListener floatingListener =
            (buttonView, isChecked) -> {
                if (isChecked) {
                    enableFloating();
                } else {
                    Prefs.setFloatingEnabled(this, false);
                    FloatingService.stop(this);
                }
            };

    private final androidx.appcompat.widget.SwitchCompat.OnCheckedChangeListener autoBootListener =
            (buttonView, isChecked) -> {
                Prefs.setAutoBootEnabled(this, isChecked);
                setBootReceiverEnabled(isChecked);
                Toast.makeText(this, isChecked ? R.string.toast_autoboot_on : R.string.toast_autoboot_off, Toast.LENGTH_SHORT).show();
            };

    private void enableFloating() {
        if (!Settings.canDrawOverlays(this)) {
            // 尚无悬浮窗权限，跳转授权
            floatingSwitch.setChecked(false);
            Prefs.setFloatingEnabled(this, false);
            Toast.makeText(this, R.string.toast_overlay_missing, Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_CODE_OVERLAY);
            } catch (Exception ignored) {
            }
            return;
        }
        Prefs.setFloatingEnabled(this, true);
        FloatingService.start(this);
        floatingSwitch.setChecked(true);
        Toast.makeText(this, R.string.toast_floating_on, Toast.LENGTH_SHORT).show();
    }

    private void setBootReceiverEnabled(boolean enabled) {
        try {
            ComponentName receiver = new ComponentName(this, BootReceiver.class);
            getPackageManager().setComponentEnabledSetting(receiver,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_autoboot_fail, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ 显示内容 / 每行项数

    private void showItemsDialog() {
        final GaugeItem[] all = GaugeItem.values();
        Set<String> saved = Prefs.getFloatItems(this);
        final boolean[] checked = new boolean[all.length];
        for (int i = 0; i < all.length; i++) {
            checked[i] = saved.contains(all[i].id);
        }
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            names[i] = getString(all[i].nameRes);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.items_dialog_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Set<String> picked = new LinkedHashSet<>();
                    for (int i = 0; i < all.length; i++) {
                        if (checked[i]) {
                            picked.add(all[i].id);
                        }
                    }
                    if (picked.isEmpty()) {
                        Toast.makeText(this, R.string.toast_items_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 保存时保留现有显示顺序，新勾选的项按枚举顺序追加在末尾
                    List<String> orderedIds = new ArrayList<>();
                    for (GaugeItem item : Prefs.getOrderedFloatItems(this)) {
                        if (picked.contains(item.id)) {
                            orderedIds.add(item.id);
                        }
                    }
                    for (GaugeItem item : all) {
                        if (picked.contains(item.id) && !orderedIds.contains(item.id)) {
                            orderedIds.add(item.id);
                        }
                    }
                    Prefs.setFloatItems(this, picked);
                    Prefs.setFloatOrder(this, orderedIds);
                    refreshItemsLabel();
                    refreshSortLabel();
                    rebuildMainMetrics();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshItemsLabel() {
        itemsValueTv.setText(getString(R.string.items_value, Prefs.getFloatItems(this).size()));
    }

    private void refreshSortLabel() {
        List<GaugeItem> items = Prefs.getOrderedFloatItems(this);
        if (items.isEmpty()) {
            items = new ArrayList<>();
            items.add(GaugeItem.RPM);
        }
        List<String> names = new ArrayList<>();
        for (GaugeItem item : items) {
            names.add(getString(item.nameRes));
        }
        sortValueTv.setText(TextUtils.join("、", names));
    }

    // ------------------------------------------------------------------ 显示顺序

    /**
     * 调整悬浮窗数据项的显示顺序：列表每行带上移/下移按钮，点按即时交换位置，
     * 确认后持久化（悬浮窗与主界面列表随之实时更新）。
     */
    private void showSortDialog() {
        final List<GaugeItem> items = Prefs.getOrderedFloatItems(this);
        if (items.isEmpty()) {
            items.add(GaugeItem.RPM);
        }
        ScrollView scroll = new ScrollView(this);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(R.string.sort_dialog_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> ids = new ArrayList<>();
                    for (GaugeItem item : items) {
                        ids.add(item.id);
                    }
                    Prefs.setFloatOrder(this, ids);
                    refreshSortLabel();
                    rebuildMainMetrics();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        renderSortRows(box, items);
    }

    /** 重建排序弹窗的列表内容；交换位置后整列重画（项数最多十余项，开销可忽略）。 */
    private void renderSortRows(LinearLayout box, List<GaugeItem> items) {
        box.removeAllViews();
        int pad = dp(6);
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad, 0, pad);

            TextView name = new TextView(this);
            name.setText((i + 1) + ". " + getString(items.get(i).nameRes));
            name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            name.setTextSize(16);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            TextView up = createSortButton("↑", i > 0);
            up.setOnClickListener(v -> {
                if (index > 0) {
                    Collections.swap(items, index, index - 1);
                    renderSortRows(box, items);
                }
            });
            row.addView(up);

            TextView down = createSortButton("↓", i < items.size() - 1);
            down.setOnClickListener(v -> {
                if (index < items.size() - 1) {
                    Collections.swap(items, index, index + 1);
                    renderSortRows(box, items);
                }
            });
            row.addView(down);

            box.addView(row);
        }
    }

    private TextView createSortButton(String arrow, boolean enabled) {
        TextView tv = new TextView(this);
        tv.setText(arrow);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(18);
        tv.setEnabled(enabled);
        tv.setTextColor(ContextCompat.getColor(this, enabled ? R.color.accent : R.color.divider));
        tv.setBackgroundResource(R.drawable.bg_font_btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(dp(6), 0, 0, 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 主界面数据列表：与悬浮窗显示同一组数据项（同顺序、含展示格式）。 */
    private void rebuildMainMetrics() {
        if (mainMetricsBox == null) {
            return;
        }
        mainMetricsBox.removeAllViews();
        mainValueTvs.clear();
        List<GaugeItem> items = Prefs.getOrderedFloatItems(this);
        if (items.isEmpty()) {
            items.add(GaugeItem.RPM);
        }
        int padding = dp(10);
        for (int i = 0; i < items.size(); i++) {
            GaugeItem item = items.get(i);
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
                mainMetricsBox.addView(divider);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, padding, 0, padding);

            TextView label = new TextView(this);
            label.setText(getString(item.nameRes));
            label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            label.setTextSize(15);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView value = new TextView(this);
            value.setText("--");
            value.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            value.setTextSize(24);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            value.setIncludeFontPadding(false);
            // 重建列表时立即回填最近一轮读数，避免改显示内容后所有数值闪回 --
            String text = client.getLastValues().get(item.id);
            if (text != null) {
                value.setText(text);
            }

            row.addView(label);
            row.addView(value);
            mainMetricsBox.addView(row);
            mainValueTvs.put(item.id, value);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showColumnsDialog() {
        int current = Prefs.getColumns(this);
        String[] options = new String[]{"1", "2", "3", "4"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.columns_dialog_title)
                .setSingleChoiceItems(options, current - 1, (dialog, which) -> {
                    Prefs.setColumns(this, which + 1);
                    refreshColumnsLabel();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshColumnsLabel() {
        columnsValueTv.setText(getString(R.string.columns_value, Prefs.getColumns(this)));
    }

    // ------------------------------------------------------------------ 悬浮窗字体

    private void showFontDialog() {
        Map<String, String> builtins = FontUtil.builtinFonts(this);
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        values.add(FontUtil.SYSTEM);
        labels.add(getString(R.string.font_system));
        values.addAll(builtins.keySet());
        labels.addAll(builtins.values());
        String customName = FontUtil.customName(this);
        values.add(FontUtil.CUSTOM);
        labels.add(customName == null
                ? getString(R.string.font_custom)
                : getString(R.string.font_custom_named, customName));

        String current = Prefs.getFont(this);
        int checked = values.indexOf(current);
        if (checked < 0) {
            checked = 0;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.font_dialog_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    String picked = values.get(which);
                    if (FontUtil.CUSTOM.equals(picked) && !FontUtil.customFile(this).exists()) {
                        // 尚未导入过字体文件，直接打开选择器
                        pickFontFile(false);
                    } else {
                        Prefs.setFont(this, picked);
                        refreshFontLabel();
                    }
                    dialog.dismiss();
                })
                .setNeutralButton(R.string.font_pick, (dialog, which) -> pickFontFile(false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshFontLabel() {
        String font = Prefs.getFont(this);
        String label;
        if (FontUtil.SYSTEM.equals(font)) {
            label = getString(R.string.font_system);
        } else if (FontUtil.CUSTOM.equals(font)) {
            String name = FontUtil.customName(this);
            label = name == null ? getString(R.string.font_custom) : name;
        } else {
            String name = FontUtil.builtinFonts(this).get(font);
            label = name == null ? getString(R.string.font_system) : name;
        }
        fontValueTv.setText(label);
    }

    // ------------------------------------------------------------------ 标签字体

    private void showLabelFontDialog() {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        values.add(FontUtil.SYSTEM);
        labels.add(getString(R.string.label_font_system));
        values.add(FontUtil.LABEL_VALUE);
        labels.add(getString(R.string.label_font_value));
        String customName = FontUtil.customLabelName(this);
        values.add(FontUtil.CUSTOM);
        labels.add(customName == null
                ? getString(R.string.font_custom)
                : getString(R.string.label_font_custom_named, customName));

        String current = Prefs.getLabelFont(this);
        int checked = values.indexOf(current);
        if (checked < 0) {
            checked = 0;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_font_dialog_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    String picked = values.get(which);
                    if (FontUtil.CUSTOM.equals(picked) && !FontUtil.customLabelFile(this).exists()) {
                        // 尚未导入过标签字体文件，直接打开选择器
                        pickFontFile(true);
                    } else {
                        Prefs.setLabelFont(this, picked);
                        refreshLabelFontLabel();
                    }
                    dialog.dismiss();
                })
                .setNeutralButton(R.string.font_pick, (dialog, which) -> pickFontFile(true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshLabelFontLabel() {
        String font = Prefs.getLabelFont(this);
        String label;
        if (FontUtil.LABEL_VALUE.equals(font)) {
            label = getString(R.string.label_font_value);
        } else if (FontUtil.CUSTOM.equals(font)) {
            String name = FontUtil.customLabelName(this);
            label = name == null ? getString(R.string.font_custom) : name;
        } else {
            label = getString(R.string.label_font_system);
        }
        labelFontValueTv.setText(label);
    }

    private void pickFontFile(boolean forLabel) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, forLabel ? REQ_CODE_FONT_LABEL : REQ_CODE_FONT);
        } catch (Exception e) {
            Toast.makeText(this, R.string.font_pick_fail, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_CODE_FONT) {
            importFont(data.getData(), false);
        } else if (requestCode == REQ_CODE_FONT_LABEL) {
            importFont(data.getData(), true);
        }
    }

    /** 把用户选中的字体文件复制到应用私有目录，立即应用到悬浮窗。 */
    private void importFont(final Uri uri, final boolean forLabel) {
        final String name = queryDisplayName(uri);
        new Thread(() -> {
            boolean ok = false;
            try {
                File out = forLabel ? FontUtil.customLabelFile(this) : FontUtil.customFile(this);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.close();
                    in.close();
                    ok = out.length() > 0;
                }
            } catch (Exception ignored) {
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                if (success) {
                    if (forLabel) {
                        FontUtil.invalidateCustomLabel();
                        Prefs.setCustomLabelFontName(this, name);
                        Prefs.setLabelFont(this, FontUtil.CUSTOM);
                        refreshLabelFontLabel();
                    } else {
                        FontUtil.invalidateCustom();
                        Prefs.setCustomFontName(this, name);
                        Prefs.setFont(this, FontUtil.CUSTOM);
                        refreshFontLabel();
                    }
                    Toast.makeText(this, R.string.font_pick_ok, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.font_pick_fail, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) {
                        return n;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "自定义字体";
    }

    // ------------------------------------------------------------------ OBD 设备

    /**
     * 应用内扫描选择读头：比亚迪等车机的系统蓝牙界面只显示"手机"类设备，
     * 读头无法在系统设置里配对，因此这里直接扫描附近经典蓝牙并列出未配对设备，
     * 选中后由 ObdClient 以 RFCOMM 连接（未配对时自动触发配对弹窗或走免认证串口）。
     */
    private AlertDialog deviceDialog;
    private ArrayAdapter<String> deviceAdapter;
    private final List<BluetoothDevice> deviceList = new ArrayList<>();
    private final Set<String> deviceAddresses = new HashSet<>();
    private BroadcastReceiver discoveryReceiver;

    private void pickDevice() {
        ensureBluetoothPermission();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, R.string.state_bt_off, Toast.LENGTH_LONG).show();
            return;
        }

        stopDiscovery();
        deviceList.clear();
        deviceAddresses.clear();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                deviceList.add(d);
                deviceAddresses.add(d.getAddress());
            }
        } catch (SecurityException ignored) {
        }

        List<String> labels = new ArrayList<>();
        for (BluetoothDevice d : deviceList) {
            labels.add(deviceLabel(d, false));
        }
        if (labels.isEmpty()) {
            labels.add(getString(R.string.pick_scanning));
        }

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        deviceDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pick_device_title)
                .setAdapter(deviceAdapter, (dialog, which) -> {
                    if (which < deviceList.size()) {
                        selectDevice(deviceList.get(which));
                    }
                })
                .setNeutralButton(R.string.pick_rescan, (dialog, which) -> {
                    // 摘掉旧弹窗的 dismiss 监听，避免关闭旧弹窗时误取消新一轮扫描
                    ((AlertDialog) dialog).setOnDismissListener(null);
                    pickDevice();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        deviceDialog.setOnDismissListener(d -> stopDiscovery());
        deviceDialog.show();

        startDiscovery(adapter);
    }

    private String deviceLabel(BluetoothDevice d, boolean unpaired) {
        String n;
        try {
            n = d.getName();
        } catch (SecurityException e) {
            n = null;
        }
        return (n == null || n.isEmpty() ? d.getAddress() : n)
                + (unpaired ? getString(R.string.pick_unpaired) : "")
                + "\n" + d.getAddress();
    }

    private void startDiscovery(BluetoothAdapter adapter) {
        if (discoveryReceiver != null) {
            return;
        }
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    if (deviceDialog != null && deviceDialog.isShowing() && deviceList.isEmpty()) {
                        deviceDialog.setTitle(R.string.pick_none_found);
                    }
                    return;
                }
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || deviceAddresses.contains(d.getAddress())) {
                    return;
                }
                String n = null;
                try {
                    n = d.getName();
                } catch (SecurityException ignored) {
                }
                if (n == null || n.isEmpty()) {
                    return; // 尚未广播名称的设备，等后续刷新
                }
                if (deviceAdapter != null && deviceList.isEmpty()) {
                    deviceAdapter.remove(MainActivity.this.getString(R.string.pick_scanning));
                }
                deviceAddresses.add(d.getAddress());
                deviceList.add(d);
                if (deviceAdapter != null) {
                    deviceAdapter.add(deviceLabel(d, true));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
        boolean started;
        try {
            started = adapter.startDiscovery();
        } catch (SecurityException e) {
            started = false;
        }
        if (!started && deviceDialog != null && deviceDialog.isShowing()) {
            deviceDialog.setTitle(R.string.pick_scan_unavailable);
        }
    }

    private void stopDiscovery() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
        }
        if (discoveryReceiver != null) {
            try {
                unregisterReceiver(discoveryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            discoveryReceiver = null;
        }
        deviceDialog = null;
        deviceAdapter = null;
    }

    private void selectDevice(BluetoothDevice d) {
        stopDiscovery();
        boolean bonded;
        try {
            bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            bonded = false;
        }
        Prefs.setObdAddress(this, d.getAddress());
        refreshDeviceLabel();
        client.reconnect();
        if (bonded) {
            Toast.makeText(this, R.string.toast_device_saved, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.toast_device_unpaired, Toast.LENGTH_LONG).show();
        }
    }

    private void ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> need = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!need.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        need.toArray(new String[0]), REQ_CODE_BT);
            }
        } else if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Android 6–11 的蓝牙扫描按系统要求需定位权限
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQ_CODE_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE_BT) {
            client.reconnect();
        }
    }

    private void refreshDeviceLabel() {
        String address = Prefs.getObdAddress(this);
        if (address == null) {
            deviceValueTv.setText(R.string.device_not_selected);
        } else {
            String name = address;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    BluetoothDevice d = adapter.getRemoteDevice(address);
                    String n = d.getName();
                    if (n != null) {
                        name = n;
                    }
                }
            } catch (Throwable ignored) {
            }
            deviceValueTv.setText(name);
        }
    }

    // ------------------------------------------------------------------ 油量校准

    /**
     * OBD PID 012F 是 ECU 的标准线性化值，与仪表/比亚迪 APP 的私有 CAN 油量存在固有偏差；
     * 这里以比亚迪 APP 的显示值为目标按比例校准（显示值 = 公式值 × 系数）。
     */
    private void showFuelCalibDialog() {
        int uncal = client.getFuelUncalibrated();
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (uncal > 0) {
            input.setText(String.valueOf(uncal));
            input.setSelection(input.getText().length());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.fuel_calib_dialog_title)
                .setMessage(getString(R.string.fuel_calib_message, Math.max(0, uncal)))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        applyFuelCalib(input.getText().toString()))
                .setNeutralButton(R.string.fuel_calib_reset, (dialog, which) -> {
                    Prefs.setFuelCalib(this, Prefs.FUEL_CALIB_DEFAULT);
                    refreshFuelCalibLabel();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyFuelCalib(String text) {
        int uncal = client.getFuelUncalibrated();
        int target;
        try {
            target = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            target = -1;
        }
        if (uncal <= 0 || target < 1 || target > 100) {
            Toast.makeText(this, R.string.fuel_calib_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        float factor = target / (float) uncal;
        if (factor < 0.3f) {
            factor = 0.3f;
        } else if (factor > 3f) {
            factor = 3f;
        }
        Prefs.setFuelCalib(this, factor);
        refreshFuelCalibLabel();
    }

    private void refreshFuelCalibLabel() {
        float factor = Prefs.getFuelCalib(this);
        fuelCalibValueTv.setText(Math.abs(factor - Prefs.FUEL_CALIB_DEFAULT) < 0.005f
                ? getString(R.string.fuel_calib_not_set)
                : "×" + String.format(Locale.getDefault(), "%.2f", factor));
    }

    // ------------------------------------------------------------------ 功率校准

    /**
     * 功率补偿倍率：019A/比亚迪 DID 解码出的功率与仪表存在固定比例偏差时
     * （实测纯电行驶约差 1.5 倍），按倍率对齐（显示值 = 解码值 × 倍率）。
     */
    private void showPowerCalibDialog() {
        float factor = Prefs.getPowerCalib(this);
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (Math.abs(factor - Prefs.POWER_CALIB_DEFAULT) > 0.005f) {
            input.setText(String.format(Locale.getDefault(), "%.2f", factor));
            input.setSelection(input.getText().length());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.power_calib_dialog_title)
                .setMessage(getString(R.string.power_calib_message))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        applyPowerCalib(input.getText().toString()))
                .setNeutralButton(R.string.power_calib_reset, (dialog, which) -> {
                    Prefs.setPowerCalib(this, Prefs.POWER_CALIB_DEFAULT);
                    refreshPowerCalibLabel();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyPowerCalib(String text) {
        float factor;
        try {
            factor = Float.parseFloat(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            factor = -1f;
        }
        if (factor < 0.3f || factor > 3f) {
            Toast.makeText(this, R.string.power_calib_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setPowerCalib(this, factor);
        refreshPowerCalibLabel();
    }

    private void refreshPowerCalibLabel() {
        float factor = Prefs.getPowerCalib(this);
        powerCalibValueTv.setText(Math.abs(factor - Prefs.POWER_CALIB_DEFAULT) < 0.005f
                ? getString(R.string.power_calib_not_set)
                : "×" + String.format(Locale.getDefault(), "%.2f", factor));
    }

    // ------------------------------------------------------------------ 电池功率诊断

    /**
     * 勾选了「电池功率」但所有探测路径都无应答时，展示各探测命令的原始应答记录，
     * 供用户复制反馈、据此适配具体车型的 PID（每次进程只提示一次）。
     */
    private void maybeShowPowerDebug(Map<String, String> values) {
        if (powerDebugShown
                || !Prefs.getFloatItems(this).contains(GaugeItem.POWER.id)
                || values.containsKey(GaugeItem.POWER.id)
                || !client.isPowerUnsupported()) {
            return;
        }
        String log = client.getPowerProbeLog();
        if (log == null || log.isEmpty()) {
            return;
        }
        powerDebugShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.power_debug_title)
                .setMessage(getString(R.string.power_debug_message) + "\n\n" + log)
                .setPositiveButton(R.string.power_debug_copy, (dialog, which) -> copyPowerDebug(log))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void copyPowerDebug(String log) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("obd_power_probe", log));
            Toast.makeText(this, R.string.power_debug_copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 字号

    private void changeFont(int delta) {
        applyFontSize(Prefs.getFontSize(this) + delta);
    }

    private void applyFontSize(int size) {
        size = clampFont(size);
        Prefs.setFontSize(this, size);
        fontSeekBar.setProgress(size - FONT_MIN);
        updateFontLabel(size);
    }

    private void updateFontLabel(int size) {
        fontSizeValueTv.setText(size + "sp");
    }

    private int clampFont(int size) {
        return Math.max(FONT_MIN, Math.min(FONT_MAX, size));
    }
}
