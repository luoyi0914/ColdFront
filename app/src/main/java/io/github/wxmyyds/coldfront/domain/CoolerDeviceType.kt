package io.github.wxmyyds.coldfront.domain

/**
 * 红魔/努比亚散热器支持的设备类型。
 *
 * 识别方式(逆向自官方 App,见 docs/protocol-8pro.md):
 * - 3 代起:广播厂商数据 MSD(company id 0x08CA)前两字节 [mainType, subType];
 * - 兜底:蓝牙广播名匹配 [matchesBleName]。
 *
 * @param mainType MSD byte0(全系 0x05)
 * @param subType MSD byte1(型号编号)
 * @param rawMin 风扇 raw 下限(8 Pro 为 40)
 * @param rawMax 风扇 raw 上限(8 Pro 为 80,旧型号 200)
 */
enum class CoolerDeviceType(
    val deviceName: String,
    val mainType: Int,
    val subType: Int,
    val generation: Int,
    val description: String,
    val rawMin: Int = 40,
    val rawMax: Int = 200,
    val supportsRgb: Boolean = true,
    val supportsAutoMode: Boolean = true,
) {
    JACKET_1(
        deviceName = "双核散热背夹",
        mainType = 0x05, subType = 0x01,
        generation = 1,
        description = "红魔散热器第一代(旧协议)",
        supportsRgb = false,
        supportsAutoMode = false,
    ),

    JACKET_2(
        deviceName = "涡轮散热背夹",
        mainType = 0x05, subType = 0x02,
        generation = 2,
        description = "红魔散热器第二代(旧协议)",
        supportsRgb = false,
        supportsAutoMode = false,
    ),

    JACKET_3(
        deviceName = "红魔散热器",
        mainType = 0x05, subType = 0x03,
        generation = 3,
        description = "第三代(官方名称就叫「红魔散热器」)",
    ),

    JACKET_4(
        deviceName = "Heat Sink 4 Pro",
        mainType = 0x05, subType = 0x04,
        generation = 4,
        description = "第四代专业版",
    ),

    JACKET_5(
        deviceName = "Heat Sink 5 Pro",
        mainType = 0x05, subType = 0x05,
        generation = 5,
        description = "第五代,RGB 控制",
    ),

    JACKET_5_LITE(
        deviceName = "Heat Sink 5 Lite",
        mainType = 0x05, subType = 0x05,
        generation = 5,
        description = "第五代紧凑版(与 5 Pro 同识别码)",
    ),

    JACKET_6(
        deviceName = "Heat Sink 6",
        mainType = 0x05, subType = 0x06,
        generation = 6,
        description = "第六代(与 6 Pro 同识别码,官方双管理器)",
    ),

    JACKET_6_PRO(
        deviceName = "Heat Sink 6 Pro",
        mainType = 0x05, subType = 0x06,
        generation = 6,
        description = "第六代专业版",
    ),

    /**
     * 红魔磁吸散热器 8 Pro(第 8 代 COLDLNG,36W,11 叶 6200RPM,16 颗可寻址 RGB,NTC 温控)。
     * MSD(0x08CA) = [0x05, 0x08];风扇 raw 40–80 共 8 档;自动模式写 0x01/0x00。
     */
    JACKET_8_PRO(
        deviceName = "Cryo Cooler 8 Pro",
        mainType = 0x05, subType = 0x08,
        generation = 8,
        description = "第八代 COLDLNG 架构,36W 峰值",
        rawMin = 40,
        rawMax = 80,
    ),

    /**
     * 手机内置散热风扇（REDMI K90 Ultra / K90 Max）。
     *
     * 不是 BLE 设备：走 root 读写 `soc/odm:xiaomi_fan` 节点（见 fan/FanNodes.kt），
     * 因此没有 MSD 识别码，也没有 RGB。
     *
     * `generation` **不能是 8** —— HomeScreen 的 `LevelSection` 按 `generation == 8`
     * 决定用 8 Pro 的 1–8 档位滑条还是通用百分比滑条；内置风扇用
     * `pwm_duty` 0–99 无极调速，所以走百分比滑条。
     */
    BUILT_IN_FAN(
        deviceName = "内置散热风扇",
        mainType = 0x00, subType = 0x00,
        generation = 9,
        description = "REDMI K90 Ultra/Max 内置风扇(root sysfs 控制)",
        rawMin = 0,
        rawMax = 99,
        supportsRgb = false,
        supportsAutoMode = true,
    );

    companion object {
        /** 按 MSD [mainType, subType] 精确匹配 */
        fun fromMsdType(mainType: Int, subType: Int): CoolerDeviceType? =
            entries.firstOrNull { it.mainType == mainType && it.subType == subType }

        /**
         * 名称兜底识别:优先明确代数，最后匹配通用 Magcooler（第三代）。
         * MSD 不可用时(旧系统/广播被裁剪)的回退。
         */
        fun fromBleName(bleName: String?): CoolerDeviceType? {
            if (bleName.isNullOrBlank()) return null
            val isRedMagic = bleName.contains("Magcooler", true) ||
                bleName.contains("RM ", true) ||
                bleName.contains("RedMagic", true) ||
                bleName.contains("Red Magic", true) ||
                bleName.contains("Nubia", true) ||
                bleName.contains("Cryo", true) ||
                bleName.contains("Heat Sink", true)
            if (!isRedMagic) return null
            return entries.firstOrNull { it != JACKET_3 && it.matchesBleName(bleName) }
                ?: JACKET_3.takeIf { it.matchesBleName(bleName) }
        }
    }

    /** 该型号是否与给定 BLE 广播名一致(严格匹配,避免误判) */
    fun matchesBleName(bleName: String?): Boolean {
        if (bleName.isNullOrBlank()) return false
        return when (this) {
            JACKET_1 -> bleName.contains("Jacket", true) || bleName.contains("Dual", true) ||
                bleName.contains("双核", true)
            JACKET_2 -> bleName.contains("Turbo", true) || bleName.contains("涡轮", true)
            JACKET_3 -> (bleName.contains("Magcooler", true) && !bleName.contains("Pro", true)) ||
                bleName.contains("Magcooler 3", true)
            JACKET_4 -> bleName.contains("4", true) && !bleName.contains(Regex("[5678]"))
            JACKET_5 -> bleName.contains("5pro", true) ||
                (bleName.contains("5", true) && bleName.contains("Pro", true) && !bleName.contains("Lite", true))
            JACKET_5_LITE -> bleName.contains("5 Lite", true) || bleName.contains("5Lite", true)
            JACKET_6 -> bleName.contains("6", true) && !bleName.contains("Pro", true)
            JACKET_6_PRO -> bleName.contains("6", true) && bleName.contains("Pro", true)
            JACKET_8_PRO -> (bleName.contains("8", true) && bleName.contains("Pro", true)) ||
                bleName.contains("Cooler 8", true) || bleName.contains("8 Pro", true)
            // 内置风扇不走 BLE 广播，永远不会由名称识别出来
            BUILT_IN_FAN -> false
        }
    }

    val suggestedIcon: String
        get() = when (generation) {
            1, 2 -> "🌀"
            3, 4 -> "❄️"
            5, 6 -> "🔷"
            7, 8 -> "🧊"
            else -> "📦"
        }
}
