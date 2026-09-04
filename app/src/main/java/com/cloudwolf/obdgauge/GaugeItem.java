package com.cloudwolf.obdgauge;

/**
 * 可在悬浮窗展示的 OBD 数据项定义。
 *
 * 均为 SAE J1979 标准公开 PID；其中电池电量、电池功率项在支持比亚迪私有协议的车型上
 * 优先走厂商自定义 PID（mode 22 DID），失败时回退标准 015B（仅电量项）。
 */
public enum GaugeItem {

    /** 发动机转速 010C：((A×256)+B)/4，单位 rpm。 */
    RPM("rpm", R.string.item_name_rpm, R.string.item_label_rpm, "010C", 2),

    /** 油箱液位 012F：100×A/255，单位 %。 */
    FUEL("fuel", R.string.item_name_fuel, R.string.item_label_fuel, "012F", 1),

    /** 车速 010D：A，单位 km/h。 */
    SPEED("speed", R.string.item_name_speed, R.string.item_label_speed, "010D", 1),

    /** 冷却液温度 0105：A−40，单位 °C。 */
    COOLANT("coolant", R.string.item_name_coolant, R.string.item_label_coolant, "0105", 1),

    /** 电池电量：比亚迪私有 221FFC 优先，回退标准 015B（100×A/256），单位 %。 */
    BATTERY("battery", R.string.item_name_battery, R.string.item_label_battery, "015B", 1),

    /** 电池功率：比亚迪私有 220008/220009（U×I），放电为正、充电为负，单位 kW。 */
    POWER("power", R.string.item_name_power, R.string.item_label_power, "220009", 2),

    /** 控制模块电压 0142：((A×256)+B)/1000，单位 V。 */
    VOLTAGE("voltage", R.string.item_name_voltage, R.string.item_label_voltage, "0142", 2),

    /** 进气温度 010F：A−40，单位 °C。 */
    INTAKE("intake", R.string.item_name_intake, R.string.item_label_intake, "010F", 1),

    /** 发动机负载 0104：100×A/255，单位 %。 */
    LOAD("load", R.string.item_name_load, R.string.item_label_load, "0104", 1),

    /** 节气门开度 0111：100×A/255，单位 %。 */
    THROTTLE("throttle", R.string.item_name_throttle, R.string.item_label_throttle, "0111", 1),

    /** 机油温度 015C：A−40，单位 °C。 */
    OILTEMP("oiltemp", R.string.item_name_oiltemp, R.string.item_label_oiltemp, "015C", 1);

    /** 稳定标识，用于持久化用户勾选。 */
    public final String id;
    /** 设置界面展示名。 */
    public final int nameRes;
    /** 悬浮窗内的小标签。 */
    public final int labelRes;
    /** 标准 OBD 命令（电池除外，特殊处理）。 */
    public final String command;
    /** 应答数据字节数。 */
    public final int byteCount;

    GaugeItem(String id, int nameRes, int labelRes, String command, int byteCount) {
        this.id = id;
        this.nameRes = nameRes;
        this.labelRes = labelRes;
        this.command = command;
        this.byteCount = byteCount;
    }

    public static GaugeItem byId(String id) {
        if (id != null) {
            for (GaugeItem item : values()) {
                if (item.id.equals(id)) {
                    return item;
                }
            }
        }
        return null;
    }
}
