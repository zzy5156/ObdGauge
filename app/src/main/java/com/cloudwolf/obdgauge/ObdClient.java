package com.cloudwolf.obdgauge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ELM327 兼容 OBD-II 蓝牙读头客户端（单例）。
 *
 * 蓝牙 SPP 串口同一时间只能有一个连接，主界面与悬浮窗服务共用本实例：
 * 第一个使用者 attach 时建立连接并开始轮询，最后一个使用者 detach 时断开。
 *
 * 轮询策略：按悬浮窗启用项 + 主界面必需项（转速/油量）构建列表，
 * 逐项发标准 OBD-II PID（公开 SAE J1979 协议），一轮结束后立即开始下一轮。
 * 每项在连接刚建立、短暂丢包时保留旧值，连续多轮无效才显示 --。
 */
public final class ObdClient {

    /** 标准 SPP 串口 UUID。 */
    static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** 每项连续无效多少轮后显示 --。 */
    private static final int MISS_LIMIT = 5;

    /** values：数据项 id → 展示文本（如 "3250"、"85%"、"13.8V"），缺失表示无效显示 --。 */
    public interface Listener {
        void onObdData(Map<String, String> values);

        /** 连接状态变化（用于界面状态行）。 */
        void onObdState(String message, boolean connected);
    }

    private static volatile ObdClient sInstance;

    public static ObdClient get(Context context) {
        if (sInstance == null) {
            synchronized (ObdClient.class) {
                if (sInstance == null) {
                    sInstance = new ObdClient(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Thread worker;
    private volatile boolean running;

    /** 最近一轮各数据项展示文本（不可变快照）。 */
    private volatile Map<String, String> lastValues = Collections.emptyMap();
    /** 未经校准的油量（012F 公式值），供校准对话框计算系数。 */
    private volatile int lastFuelUncal = -1;
    /** 最近一次连接内功率/SOC 探测的原始应答记录（诊断弹窗展示）。 */
    private volatile String powerProbeLog = "";
    /** 最近一次连接内功率探测是否全部无应答。 */
    private volatile boolean powerUnsupportedFlag = false;
    private volatile String state = "";
    private volatile boolean connected = false;

    private ObdClient(Context appContext) {
        this.appContext = appContext;
    }

    public void attach(Listener l) {
        listeners.add(l);
        synchronized (this) {
            if (worker == null) {
                running = true;
                worker = new Thread(this::loop, "obd-client");
                worker.setDaemon(true);
                worker.start();
            }
        }
        // 立即回放当前状态
        main.post(() -> {
            l.onObdState(state, connected);
            l.onObdData(lastValues);
        });
    }

    public void detach(Listener l) {
        listeners.remove(l);
        synchronized (this) {
            if (listeners.isEmpty() && worker != null) {
                running = false;
                worker.interrupt();
                worker = null;
            }
        }
    }

    /** 选择设备后调用，立即重建连接。 */
    public void reconnect() {
        synchronized (this) {
            if (worker != null) {
                running = false;
                worker.interrupt();
                worker = null;
            }
            if (!listeners.isEmpty()) {
                running = true;
                worker = new Thread(this::loop, "obd-client");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    /** 最近的未校准油量（012F 公式值，0–100），无效为 -1；校准对话框用它计算系数。 */
    public int getFuelUncalibrated() {
        return lastFuelUncal;
    }

    /** 最近一次连接内功率/SOC 探测的原始应答记录，供诊断弹窗展示。 */
    public String getPowerProbeLog() {
        return powerProbeLog;
    }

    /** 最近一次连接内功率探测（功能/物理寻址）是否全部无应答。 */
    public boolean isPowerUnsupported() {
        return powerUnsupportedFlag;
    }

    /** 最近一轮各数据项展示文本快照；界面重建列表时回填，避免新行一直显示 --。 */
    public Map<String, String> getLastValues() {
        return lastValues;
    }

    // ------------------------------------------------------------------

    private void loop() {
        while (running) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice device = pickDevice(adapter);
                if (adapter == null || !adapter.isEnabled() || device == null) {
                    updateState(adapter != null && adapter.isEnabled()
                            ? appContext.getString(R.string.state_no_device)
                            : appContext.getString(R.string.state_bt_off), false);
                    Thread.sleep(3000);
                    continue;
                }

                updateState(appContext.getString(R.string.state_connecting, name(device)), false);
                Socket conn = new Socket(appContext,
                        Prefs.getPowerMethod(appContext), Prefs.getSocSource(appContext));
                if (!conn.connect(device)) {
                    updateState(appContext.getString(R.string.state_connect_fail, name(device)), false);
                    Thread.sleep(5000);
                    continue;
                }

                connected = true;
                updateState(appContext.getString(R.string.state_connected, name(device)), true);
                pollLoop(conn);
                conn.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                connected = false;
                updateState(appContext.getString(R.string.state_error, String.valueOf(t)), false);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        connected = false;
    }

    /** 连接成功后的多 PID 轮询；链路整体失效（连续多轮全部无数据）时返回以重建连接。 */
    private void pollLoop(Socket conn) throws InterruptedException {
        Map<String, Integer> miss = new HashMap<>();
        List<GaugeItem> items = activeItems();
        int cycleMiss = 0;
        while (running && conn.isOpen()) {
            boolean anyValid = false;
            for (GaugeItem item : items) {
                if (!running) {
                    return;
                }
                Number raw = conn.readRaw(item);
                if (raw != null) {
                    miss.put(item.id, 0);
                    anyValid = true;
                    publish(item, raw);
                } else if (increment(miss, item.id) < MISS_LIMIT) {
                    // 短暂读不到时保留上次值避免闪烁
                } else {
                    unpublish(item);
                }
                Thread.sleep(20);
            }
            cycleMiss = anyValid ? 0 : cycleMiss + 1;
            if (cycleMiss >= MISS_LIMIT) {
                return; // 链路失效，重建连接
            }
            items = activeItems(); // 用户可能中途改了显示项
            powerProbeLog = conn.dumpProbeLog();
            powerUnsupportedFlag = conn.isPowerUnsupported();
            postSnapshot();
        }
    }

    private static int increment(Map<String, Integer> miss, String id) {
        Integer v = miss.get(id);
        int next = (v == null ? 0 : v) + 1;
        miss.put(id, next);
        return next;
    }

    /** 轮询项 = 主界面必需（转速/油量）∪ 悬浮窗启用项，按枚举声明顺序保证轮询稳定。 */
    private List<GaugeItem> activeItems() {
        Set<String> enabled = Prefs.getFloatItems(appContext);
        List<GaugeItem> items = new ArrayList<>();
        for (GaugeItem item : GaugeItem.values()) {
            if (enabled.contains(item.id) || item == GaugeItem.RPM || item == GaugeItem.FUEL) {
                items.add(item);
            }
        }
        return items;
    }

    private final Map<String, String> pending = new LinkedHashMap<>();

    /** 把一次有效读数按展示格式写入待发布快照；油量在此应用校准系数。 */
    private void publish(GaugeItem item, Number raw) {
        String text;
        switch (item) {
            case FUEL:
                if (raw.intValue() >= 0) {
                    lastFuelUncal = raw.intValue();
                }
                float k = Prefs.getFuelCalib(appContext);
                float v = raw.floatValue() * k;
                v = Math.max(0f, Math.min(100f, v));
                text = Math.round(v) + "%";
                break;
            case BATTERY:
                text = Math.round(raw.floatValue()) + "%";
                break;
            case POWER:
                // 放电为正、充电为负；负值自带 "-" 号；应用用户校准倍率对齐仪表
                float pw = raw.floatValue() * Prefs.getPowerCalib(appContext);
                text = String.format(Locale.US, "%.1fkW", pw);
                break;
            case VOLTAGE:
                text = String.format(Locale.US, "%.1fV", raw.floatValue());
                break;
            case COOLANT:
            case INTAKE:
            case OILTEMP:
                text = Math.round(raw.floatValue()) + "°C";
                break;
            default:
                text = String.valueOf(Math.round(raw.floatValue()));
        }
        synchronized (pending) {
            pending.put(item.id, text);
        }
    }

    private void unpublish(GaugeItem item) {
        synchronized (pending) {
            pending.remove(item.id);
        }
    }

    private void postSnapshot() {
        Map<String, String> snapshot;
        synchronized (pending) {
            snapshot = pending.isEmpty()
                    ? Collections.<String, String>emptyMap()
                    : new LinkedHashMap<>(pending);
        }
        if (snapshot.equals(lastValues)) {
            return;
        }
        lastValues = snapshot;
        main.post(() -> {
            for (Listener l : listeners) {
                l.onObdData(snapshot);
            }
        });
    }

    private BluetoothDevice pickDevice(BluetoothAdapter adapter) {
        try {
            String saved = Prefs.getObdAddress(appContext);
            if (saved != null && adapter != null) {
                return adapter.getRemoteDevice(saved);
            }
            if (adapter == null) {
                return null;
            }
            // 未手动选择时，按名称自动识别常见读头
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n != null && n.matches("(?i).*(obd|elm|vgate|v-link|vlink|mlink).*")) {
                    return d;
                }
            }
        } catch (SecurityException e) {
            // Android 12+ 缺少 BLUETOOTH_CONNECT，由界面层申请后重建
        }
        return null;
    }

    private static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    private void updateState(String msg, boolean isConn) {
        state = msg;
        connected = isConn;
        main.post(() -> {
            for (Listener l : listeners) {
                l.onObdState(msg, isConn);
            }
        });
    }

    /** 阻塞式 ELM327 协议实现。 */
    static final class Socket {
        private final Context context;
        private BluetoothSocket socket;
        private InputStream in;
        private OutputStream out;

        /** savedPowerMethod/savedSocSource：上次成功取值方式的记忆（0=无记忆，走探测）。 */
        private Socket(Context context, int savedPowerMethod, int savedSocSource) {
            this.context = context;
            powerMethod = (savedPowerMethod >= PWR_FUNC && savedPowerMethod <= PWR_9A_FUNC)
                    ? savedPowerMethod : PWR_TRY;
            socSource = (savedSocSource >= SOC_DID && savedSocSource <= SOC_PID)
                    ? savedSocSource : SOC_AUTO;
        }

        boolean connect(BluetoothDevice device) {
            // 扫描会干扰 RFCOMM 建链，连接前先停掉
            cancelDiscovery();
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            } catch (IOException | SecurityException e) {
                // 车机系统蓝牙常只允许配对"手机"类设备，读头无法预先配对；
                // 对未配对设备走 insecure RFCOMM，多数 ELM327 读头（含 Vgate）接受免认证串口
                close();
                try {
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                } catch (IOException | SecurityException e2) {
                    close();
                    return false;
                }
            }
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
            } catch (IOException e) {
                close();
                return false;
            }
            // 复位 + 关回显/头/空格 + 自动协议检测
            sendIgnore("ATZ");
            sleep(400);
            sendIgnore("ATE0");
            sendIgnore("ATL0");
            sendIgnore("ATS0");
            sendIgnore("ATH0");
            sendIgnore("ATSP0");
            // ISO-TP 流控帧设置：BMS(7E7) 的 mode 22 应答可能是多帧，ELM 默认把流控帧
            // 发给 7E0（发动机 ECU），BMS 收不到会导致应答永远不完整而超时 NO DATA。
            // 标准 PID 均为单帧应答不触发流控，此设置对其他轮询无影响（与 WiCAN 等社区
            // 实现的 ATFCSH7E7;ATFCSD300000;ATFCSM1 初始化一致）。
            sendIgnore("ATFCSH7E7");
            sendIgnore("ATFCSD300000");
            sendIgnore("ATFCSM1");
            String ok = send("0100");
            return ok != null && ok.contains("41");
        }

        private static void cancelDiscovery() {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
            } catch (SecurityException ignored) {
                // Android 12+ 取消扫描需 BLUETOOTH_SCAN，未授权时忽略（界面层负责申请）
            }
        }

        boolean isOpen() {
            return socket != null && socket.isConnected();
        }

        /**
         * 读取一个数据项的原始数值（已按 SAE J1979 公式换算，未做油量校准）。
         * 无数据 / ECU 不支持返回 null。
         */
        Number readRaw(GaugeItem item) {
            switch (item) {
                case RPM: {
                    int[] d = payload(send(item.command), 0x0C, 2);
                    return d == null ? null : (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) / 4;
                }
                case FUEL:
                    return readFuelRaw();
                case SPEED: {
                    int[] d = payload(send(item.command), 0x0D, 1);
                    return d == null ? null : d[0] & 0xFF;
                }
                case COOLANT: {
                    int[] d = payload(send(item.command), 0x05, 1);
                    return d == null ? null : (d[0] & 0xFF) - 40;
                }
                case BATTERY:
                    return readBatteryRaw();
                case POWER:
                    return readPowerRaw();
                case VOLTAGE: {
                    int[] d = payload(send(item.command), 0x42, 2);
                    return d == null ? null : (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) / 1000f;
                }
                case INTAKE: {
                    int[] d = payload(send(item.command), 0x0F, 1);
                    return d == null ? null : (d[0] & 0xFF) - 40;
                }
                case LOAD: {
                    int[] d = payload(send(item.command), 0x04, 1);
                    return d == null ? null : (d[0] & 0xFF) * 100f / 255f;
                }
                case THROTTLE: {
                    int[] d = payload(send(item.command), 0x11, 1);
                    return d == null ? null : (d[0] & 0xFF) * 100f / 255f;
                }
                case OILTEMP: {
                    int[] d = payload(send(item.command), 0x5C, 1);
                    return d == null ? null : (d[0] & 0xFF) - 40;
                }
                default:
                    return null;
            }
        }

        /** 原始字节连续 0xFF 达到该次数视为无效，避免把满箱偶发的 0xFF 误判为无效。 */
        private static final int FUEL_FF_INVALID_THRESHOLD = 5;

        private int fuelFfStreak = 0;
        private boolean fuelInvalid = false;

        /** 功率取值方式：探测成功后锁定；失败不锁死，跳过数轮后自动重探。 */
        private static final int PWR_TRY = 0;      // 尚未探测成功，周期性重探
        private static final int PWR_FUNC = 1;     // 比亚迪 220008/220009 功能寻址
        private static final int PWR_PHYS = 2;     // 比亚迪 220008/220009 物理寻址 7E7
        private static final int PWR_9A_PHYS = 3;  // 标准 019A 物理寻址 7E7（仅 BMS 应答，无歧义）
        private static final int PWR_9A_FUNC = 4;  // 标准 019A 功能寻址（多 ECU 应答，按合理性筛选）
        private int powerMethod = PWR_TRY;
        /** 探测失败后的重试节流：跳过若干轮再重探。车辆上电状态会变化——
         * 解锁后仅低压上电时 BMS 的 019A 应答不全（连续帧迟到被读超时截断），
         * 踩刹车上了高压后恢复完整应答，重探可自动接上，无需重连。 */
        private int powerProbeSkip = 0;
        /** 已锁定取值方式的连续失效计数（车辆状态变化/OTA 失效时回退探测）。 */
        private int powerFailCount = 0;
        /** 最近一次探测是否全路径失败（诊断弹窗依据）。 */
        private boolean powerProbeFailed = false;

        /** SOC 取值来源：成功后记忆；来源失效不锁死、连续 5 轮后回到自动重探。 */
        private static final int SOC_AUTO = 0;  // 每轮先 221FFC 后 015B
        private static final int SOC_DID = 1;   // 仅比亚迪 221FFC
        private static final int SOC_PID = 2;   // 仅标准 015B
        private int socSource;                  // 构造时从记忆恢复
        private int socFailCount = 0;

        /** 功率/SOC 探测的原始应答记录（诊断用，最多保留 40 行）。 */
        private final ArrayDeque<String> probeLog = new ArrayDeque<>();

        private void logProbe(String line) {
            synchronized (probeLog) {
                if (probeLog.size() >= 40) {
                    probeLog.poll();
                }
                probeLog.add(line.length() > 96 ? line.substring(0, 96) : line);
            }
        }

        /** 导出探测记录（换行分隔）。 */
        String dumpProbeLog() {
            synchronized (probeLog) {
                StringBuilder sb = new StringBuilder();
                for (String line : probeLog) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(line);
                }
                return sb.toString();
            }
        }

        boolean isPowerUnsupported() {
            return powerProbeFailed;
        }

        private static String respText(String resp) {
            return resp == null ? "无应答" : (resp.isEmpty() ? "空" : resp);
        }

        /** 油箱液位 012F：100×A/255；不少 ECU（比亚迪常见）以 0xFF 填充不支持的应答。 */
        private Integer readFuelRaw() {
            int[] d = payload(send("012F"), 0x2F, 1);
            if (d == null) {
                fuelFfStreak = 0;
                fuelInvalid = false;
                return null;
            }
            int raw = d[0] & 0xFF;
            if (raw == 0xFF) {
                if (++fuelFfStreak >= FUEL_FF_INVALID_THRESHOLD) {
                    fuelInvalid = true;
                }
                return fuelInvalid ? null : 100;
            }
            fuelFfStreak = 0;
            fuelInvalid = false;
            return Math.round(raw * 100f / 255f);
        }

        /**
         * 电池电量：优先比亚迪私有 PID（mode 22 DID FFC，社区通用命令 221FFC，
         * 应答 61FFC 后首字节即电量百分数）；不支持时回退标准混动 PID 015B（100×A/256）。
         * 成功后记忆有效来源，后续轮次直接读取（省去对失效来源的查询）；
         * 来源连续 5 轮失效自动回到自动探测（适配车辆状态/固件变化），不锁死。
         */
        private Integer readBatteryRaw() {
            if (socSource == SOC_DID) {
                Integer v = querySocDid();
                if (v != null) {
                    socFailCount = 0;
                    return v;
                }
                return onSocSourceFailed();
            }
            if (socSource == SOC_PID) {
                Integer v = querySocPid();
                if (v != null) {
                    socFailCount = 0;
                    return v;
                }
                return onSocSourceFailed();
            }
            // 自动：按优先级尝试，首个有效来源记忆下来
            Integer v = querySocDid();
            if (v != null) {
                latchSocSource(SOC_DID);
                return v;
            }
            v = querySocPid();
            if (v != null) {
                latchSocSource(SOC_PID);
                return v;
            }
            return null;
        }

        /** 比亚迪 221FFC：应答 61FFC 后首字节即电量百分数。 */
        private Integer querySocDid() {
            String resp = send("221FFC");
            logProbe("SOC 221FFC → " + respText(resp));
            String up = resp == null ? "" : resp.toUpperCase();
            if (up.isEmpty() || up.contains("NODATA") || up.contains("ERROR")
                    || up.contains("UNABLE") || up.contains("?")) {
                return null;
            }
            int idx = up.indexOf("61FFC");
            if (idx >= 0 && idx + 7 <= up.length()) {
                try {
                    int v = Integer.parseInt(up.substring(idx + 5, idx + 7), 16);
                    if (v >= 0 && v <= 100) {
                        return v;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }

        /** 标准混动 015B：100×A/256。 */
        private Integer querySocPid() {
            String resp = send("015B");
            logProbe("SOC 015B → " + respText(resp));
            int[] d = payload(resp, 0x5B, 1);
            return d == null ? null : Math.round((d[0] & 0xFF) * 100f / 256f);
        }

        /** 记忆的 SOC 来源失效：连续 5 轮后回到自动探测（不锁死）。 */
        private Integer onSocSourceFailed() {
            if (++socFailCount < 5) {
                return null;
            }
            socFailCount = 0;
            latchSocSource(SOC_AUTO);
            return null;
        }

        private void latchSocSource(int source) {
            if (socSource != source) {
                socSource = source;
                Prefs.setSocSource(context, source);
            }
            socFailCount = 0;
        }

        /**
         * 电池功率（kW，放电为正、充电为负）。按探测结果锁定取值方式：
         *
         * 1. 比亚迪 BMS 私有 DID 220008（电池总电压，2 字节小端，原值即 V）+
         *    220009（电池电流，2 字节小端，(raw−5000)/10 A，正值为放电），
         *    功率 = U × I / 1000（与 OVMS / Battery-Emulator 等开源实现一致）。
         *    先走默认功能寻址（与 221FFC 相同方式，不动寻址头、零风险），
         *    无应答才临时切物理寻址 7E7 再试，结束后必须用带参 ATSH7DF 恢复——
         *    部分读头固件（如 Vgate iCar Pro 2S）不支持无参 ATSH，恢复失败会让寻址头
         *    滞留在 7E7，后续所有标准 PID 全部无应答（表现为设备反复掉线）。
         * 2. 标准 J1979 混动/EV PID 019A（Hybrid/EV Vehicle System Data）：
         *    电压 = 第 1 对字节 (A×256+B)/10 V（大端）；电流 = 第 3 对字节
         *    有符号 /10 A（实测第五代 DM-i：第 2 对为状态值恒 0，电流在第 3 对；
         *    EHS 同时应答升压母线电压 + 无意义电流位，按电压 100–800V、
         *    电流 |I|≤500A、第 2 对 |I|≤500A 筛除，取最后通过者 = BMS）。
         *    物理寻址 7E7 优先（仅 BMS 应答，无歧义），功能寻址兜底。
         *
         * 所有方式均无应答时不锁死状态：跳过数轮后自动重探（车辆上电状态会变化，
         * 如解锁后仅低压上电、踩刹车才上高压；诊断弹窗可查看原始应答）。
         * 取值方式成功后持久化记忆，下次启动直接按记忆读取；锁定方式连续 5 轮
         * 失效（上电状态变化或 OTA 后失效）自动回到探测重新适配。
         */
        private Float readPowerRaw() {
            if (powerMethod == PWR_TRY) {
                if (powerProbeSkip > 0) {
                    powerProbeSkip--;
                    return null;
                }
                Float kw = probePower();
                powerProbeFailed = kw == null;
                if (kw == null) {
                    powerProbeSkip = 5; // 失败后跳 5 轮再重探，上高压后 ~10s 内自动恢复
                }
                return kw;
            }
            // 已锁定取值方式：直接按记忆方式读取
            Float kw = queryByMethod(powerMethod);
            if (kw != null) {
                powerFailCount = 0;
                return kw;
            }
            if (++powerFailCount >= 5) {
                powerFailCount = 0;
                powerMethod = PWR_TRY;
                powerProbeSkip = 0; // 下轮立即重探
            }
            return null;
        }

        /** 按锁定的取值方式读取一次。 */
        private Float queryByMethod(int method) {
            switch (method) {
                case PWR_FUNC:
                    return queryPowerDid(false);
                case PWR_PHYS:
                    return queryPowerDid(true);
                case PWR_9A_PHYS:
                    return queryPower9A(true);
                default:
                    return queryPower9A(false);
            }
        }

        /** 探测：按优先级尝试各取值方式并锁定有效的一种；全部失败则等待下轮重探。 */
        private Float probePower() {
            Float kw = queryPowerDid(false);
            if (kw != null) {
                latchPowerMethod(PWR_FUNC);
                return kw;
            }
            kw = queryPowerDid(true);
            if (kw != null) {
                latchPowerMethod(PWR_PHYS);
                return kw;
            }
            kw = queryPower9A(true);
            if (kw != null) {
                latchPowerMethod(PWR_9A_PHYS);
                return kw;
            }
            kw = queryPower9A(false);
            if (kw != null) {
                latchPowerMethod(PWR_9A_FUNC);
                return kw;
            }
            return null;
        }

        /** 锁定取值方式并持久化记忆（下次启动直接按此方式读取）。 */
        private void latchPowerMethod(int method) {
            powerMethod = method;
            powerFailCount = 0;
            Prefs.setPowerMethod(context, method);
        }

        /** 比亚迪私有 DID 方式：查电压 220008 + 电流 220009 并计算 U×I。 */
        private Float queryPowerDid(boolean physical) {
            if (physical) {
                sendIgnore("ATSH7E7");
            }
            String vResp;
            String iResp;
            try {
                vResp = send("220008");
                iResp = send("220009");
            } finally {
                if (physical) {
                    sendIgnore("ATSH7DF"); // 恢复 11 位 CAN 默认功能寻址，防止头部滞留
                }
            }
            logProbe((physical ? "物理" : "功能") + " 220008 → " + respText(vResp));
            logProbe((physical ? "物理" : "功能") + " 220009 → " + respText(iResp));
            Long volts = parseDidValue(vResp, "0008", 2);
            Long currentRaw = parseDidValue(iResp, "0009", 2);
            if (volts == null || currentRaw == null) {
                return null;
            }
            if (volts < 0 || volts > 1000) {
                return null; // 电压超出动力电池合理范围视为无效
            }
            float amps = (currentRaw - 5000f) / 10f;
            if (Math.abs(amps) > 1000f) {
                return null;
            }
            float kw = volts * amps / 1000f;
            if (Math.abs(kw) < 0.05f) {
                kw = 0f; // 避免极小电流时闪烁 "-0.0"
            } else if (Math.abs(kw) > 500f) {
                return null;
            }
            return kw;
        }

        /** 标准 019A 方式：physical=true 时物理寻址 7E7（仅 BMS 应答，无多 ECU 歧义）。 */
        private Float queryPower9A(boolean physical) {
            if (physical) {
                sendIgnore("ATSH7E7");
            }
            String resp;
            try {
                resp = send("019A");
            } finally {
                if (physical) {
                    sendIgnore("ATSH7DF");
                }
            }
            logProbe((physical ? "物理" : "功能") + " 019A → " + respText(resp));
            return decode9A(resp);
        }

        /**
         * 解析 019A 应答中的全部候选（可能多 ECU 应答），取最后一个通过筛选者
         * （功能寻址下 BMS 应答通常最后到达）。实测第五代 DM-i（P 档上电 + 空调，
         * 仪表 ≈1kW）标定：电流不在标准第 2 对字节（BMS 该位恒 0，EHS 该位为
         * 2259.2A 的无意义值），而在第 3 对字节（有符号 /10 A，放电为正）——
         * 第 2 对解码为 |I|>500A 的候选直接剔除。多帧显示夹杂的长度前缀与
         * 行索引会被容忍跳过。
         */
        private Float decode9A(String resp) {
            if (resp == null) {
                return null;
            }
            String up = resp.toUpperCase();
            float result = Float.NaN;
            int searchFrom = 0;
            int hit;
            while ((hit = up.indexOf("419A", searchFrom)) >= 0) {
                searchFrom = hit + 4;
                int[] d = collect9AData(up, hit + 4);
                if (d == null) {
                    continue;
                }
                float volts = ((d[0] << 8) | d[1]) / 10f;
                float iStd = (short) ((d[2] << 8) | d[3]) / 10f;
                float amps = (short) ((d[4] << 8) | d[5]) / 10f;
                boolean ok = volts >= 100f && volts <= 800f
                        && Math.abs(iStd) <= 500f && Math.abs(amps) <= 500f;
                logProbe("019A 候选 U=" + volts + "V I2=" + iStd + "A I3=" + amps + "A"
                        + (ok ? " ✓" : "（弃）"));
                if (!ok) {
                    continue;
                }
                float kw = volts * amps / 1000f;
                if (Math.abs(kw) < 0.05f) {
                    kw = 0f;
                }
                if (Math.abs(kw) <= 300f) {
                    result = kw;
                }
            }
            return Float.isNaN(result) ? null : result;
        }

        /**
         * 从 from 起收集 12 个十六进制字符（6 字节）。容忍多帧显示夹杂的长度前缀
         * 与 "N:" 行索引：冒号前若剩孤位（奇数个）则该位是行索引，丢弃。
         */
        private static int[] collect9AData(String up, int from) {
            StringBuilder nib = new StringBuilder();
            for (int i = from; i < up.length() && nib.length() < 12; i++) {
                char c = up.charAt(i);
                if (c == ':') {
                    if (nib.length() % 2 != 0) {
                        nib.setLength(nib.length() - 1);
                    }
                } else if (isHexDigit(c)) {
                    nib.append(c);
                } else {
                    break; // 其他字符视为应答结束
                }
            }
            if (nib.length() < 12) {
                return null;
            }
            int[] d = new int[6];
            for (int i = 0; i < 6; i++) {
                d[i] = Integer.parseInt(nib.substring(i * 2, i * 2 + 2), 16);
            }
            return d;
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
        }

        /**
         * 在 mode 22 应答中解析指定 DID 的数据：兼容 UDS 前缀 "62<DID>" 与
         * J2190 前缀 "61<DID>"，数据按小端解析（BYD BMS 惯例）。无效返回 null。
         */
        private static Long parseDidValue(String response, String did, int byteCount) {
            if (response == null) {
                return null;
            }
            String up = response.toUpperCase();
            if (up.contains("NODATA") || up.contains("SEARCHING") || up.contains("STOPPED")
                    || up.contains("ERROR") || up.contains("UNABLE") || up.contains("?")) {
                return null;
            }
            int idx = up.indexOf("62" + did);
            if (idx < 0) {
                idx = up.indexOf("61" + did);
            }
            if (idx < 0 || idx + 2 + did.length() + byteCount * 2 > up.length()) {
                return null;
            }
            try {
                long value = 0;
                for (int i = 0; i < byteCount; i++) {
                    // 小端：首字节为低位
                    int b = Integer.parseInt(up.substring(idx + 2 + did.length() + i * 2,
                            idx + 4 + did.length() + i * 2), 16);
                    value |= (long) b << (8 * i);
                }
                return value;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        void close() {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
            in = null;
            out = null;
            socket = null;
        }

        private String send(String command) {
            try {
                out.write((command + "\r").getBytes("ASCII"));
                out.flush();
                return readUntilPrompt(1500);
            } catch (IOException e) {
                return null;
            }
        }

        private void sendIgnore(String command) {
            try {
                out.write((command + "\r").getBytes("ASCII"));
                out.flush();
                readUntilPrompt(800);
            } catch (IOException ignored) {
            }
        }

        /** 读取到 '>' 提示符，去除回显/空白。 */
        private String readUntilPrompt(int timeoutMs) throws IOException {
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int c = in.read();
                    if (c == -1 || c == '>') {
                        break;
                    }
                    if (c != '\r' && c != '\n' && c != ' ') {
                        sb.append((char) c);
                    }
                } else {
                    sleep(15);
                }
            }
            return sb.toString();
        }

        /** 提取 "41<PID><data...>" 的数据字节。 */
        private static int[] payload(String response, int pid, int byteCount) {
            if (response == null) {
                return null;
            }
            String up = response.toUpperCase();
            if (up.contains("NODATA") || up.contains("SEARCHING")
                    || up.contains("STOPPED") || up.contains("ERROR") || up.contains("UNABLE")) {
                return null;
            }
            int idx = up.indexOf(String.format("41%02X", pid));
            if (idx < 0 || idx + 4 + byteCount * 2 > up.length()) {
                return null;
            }
            String hex = up.substring(idx + 4, idx + 4 + byteCount * 2);
            int[] out = new int[byteCount];
            for (int i = 0; i < byteCount; i++) {
                try {
                    out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return out;
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
