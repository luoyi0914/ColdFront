package io.github.wxmyyds.coldfront.fan

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** 温度 → 占空比曲线上的一个断点。 */
data class CurvePoint(val tempC: Int, val pwmPercent: Int)

/**
 * 曲线不良构的原因。[index] 指向违规断点，供编辑器高亮到具体节点。
 *
 * 这些属于**结构性约束**（曲线算不出来），与「安全建议」（曲线能算但有风险，只提示）
 * 是两回事 —— 后者见 [FanCurve.isDangerous]。
 */
data class CurveViolation(val kind: Kind, val index: Int) {
    enum class Kind {
        TOO_FEW_POINTS,
        TOO_MANY_POINTS,
        TEMP_OUT_OF_RANGE,
        TEMP_NOT_ASCENDING,
        DUTY_OUT_OF_RANGE,
        DUTY_DECREASING,
    }

    override fun toString(): String = "$kind@$index"
}

/** 预设曲线。文案不在这里 —— 项目用 100% Kotlin 字符串表，由 UI 层映射。 */
enum class CurvePreset { QUIET, BALANCED, PERFORMANCE }

/**
 * 自定义温控曲线：一组**严格递增**温度上的**单调不减**占空比。
 *
 * ## 为什么这些约束是强制的
 * [pwmFor] 依赖断点有序才能定位区间。上游参考实现允许节点被拖成乱序，其区间查找
 * 落空后返回 0 —— 于是曲线一旦乱序，风扇**静默停转**而界面仍显示"智能温控运行中"。
 * 这里把约束做进类型：[FanCurve] 无法被构造成不良构状态，[pwmFor] 也就不存在
 * "找不到区间"这条静默降级分支。
 *
 * ## 本类不碰 Android
 * 纯逻辑 + [org.json]，因此可以在纯 JVM 单测里覆盖（项目没有 Robolectric）。
 * 曲线原来的插值实现是 `FanManager` 的私有方法，需要 `Context`，一行测试都写不了。
 */
data class FanCurve(val points: List<CurvePoint>) {

    init {
        val violation = validate(points)
        require(violation == null) { "不良构曲线：$violation" }
    }

    /**
     * 分段线性插值，与移植前 `FanManager.curvePwmForTemp` 逐字一致
     * （截断取整，非四舍五入），因此默认曲线的行为与旧版完全相同。
     */
    fun pwmFor(tempC: Int): Int {
        val first = points.first()
        val last = points.last()
        if (tempC <= first.tempC) return first.pwmPercent
        if (tempC >= last.tempC) return last.pwmPercent
        // 校验保证严格递增，因此这里必然落在某个区间内，indexOfLast 不会返回 -1，
        // 也不会取到最后一个点（tempC < last.tempC）。
        val lowerIndex = points.indexOfLast { it.tempC <= tempC }
        val lower = points[lowerIndex]
        val upper = points[lowerIndex + 1]
        val ratio = (tempC - lower.tempC).toFloat() / (upper.tempC - lower.tempC)
        return (lower.pwmPercent + ratio * (upper.pwmPercent - lower.pwmPercent)).toInt()
    }

    /** 曲线当前等于哪个预设；自定义曲线返回 null。 */
    fun matchingPreset(): CurvePreset? =
        CurvePreset.entries.firstOrNull { presetCurve(it) == this }

    /**
     * 曲线上首个命中危险判定的温度；没有则 null。
     *
     * 供编辑器着色与保存确认使用。注意判定**与基线无关**：静谧预设刻意比默认线安静，
     * 若拿"低于基线"当危险判定，选中自带预设就会满屏警告。
     */
    fun firstDangerousTemp(): Int? =
        (MIN_TEMP_C..MAX_TEMP_C).firstOrNull { isDangerous(it, pwmFor(it)) }

    companion object {
        /** 温度轴范围。下限取 20 以容纳电池温度这类低量程源。 */
        const val MIN_TEMP_C = 20
        const val MAX_TEMP_C = 100

        const val MIN_POINTS = 2
        const val MAX_POINTS = 12

        /** 相邻断点的最小温差：不允许重合，否则区间宽度为 0。 */
        const val MIN_TEMP_GAP_C = 1

        const val MIN_DUTY = 0
        const val MAX_DUTY = 99

        /** 编辑器默认维持的断点数（与旧硬编码曲线一致）。 */
        const val EDITOR_POINT_COUNT = 8

        // 危险判定阈值（建议值，待实机标定）。见设计文档 §5。
        const val DANGER_TEMP_HARD = 60
        const val DANGER_DUTY_HARD = 50
        const val DANGER_TEMP_SOFT = 55
        const val DANGER_DUTY_SOFT = 20

        /**
         * 危险判定：只拦「明显会出事」的配置，不拦「比默认安静」。
         *
         * 这样选静谧预设不会触发警告，而画一条高温区近乎停转的曲线会。
         */
        fun isDangerous(tempC: Int, pwmPercent: Int): Boolean =
            (tempC >= DANGER_TEMP_HARD && pwmPercent < DANGER_DUTY_HARD) ||
                (tempC >= DANGER_TEMP_SOFT && pwmPercent < DANGER_DUTY_SOFT)

        /** 返回首个违规项；曲线良构时返回 null。 */
        fun validate(points: List<CurvePoint>): CurveViolation? {
            if (points.size < MIN_POINTS) return CurveViolation(CurveViolation.Kind.TOO_FEW_POINTS, 0)
            if (points.size > MAX_POINTS) return CurveViolation(CurveViolation.Kind.TOO_MANY_POINTS, MAX_POINTS)
            points.forEachIndexed { index, point ->
                if (point.tempC !in MIN_TEMP_C..MAX_TEMP_C) {
                    return CurveViolation(CurveViolation.Kind.TEMP_OUT_OF_RANGE, index)
                }
                if (point.pwmPercent !in MIN_DUTY..MAX_DUTY) {
                    return CurveViolation(CurveViolation.Kind.DUTY_OUT_OF_RANGE, index)
                }
                if (index > 0) {
                    val previous = points[index - 1]
                    if (point.tempC - previous.tempC < MIN_TEMP_GAP_C) {
                        return CurveViolation(CurveViolation.Kind.TEMP_NOT_ASCENDING, index)
                    }
                    if (point.pwmPercent < previous.pwmPercent) {
                        return CurveViolation(CurveViolation.Kind.DUTY_DECREASING, index)
                    }
                }
            }
            return null
        }

        /** 良构则返回曲线，否则 null。用于需要「不抛异常地试探」的场合。 */
        fun of(points: List<CurvePoint>): FanCurve? =
            if (validate(points) == null) FanCurve(points.toList()) else null

        private fun curveOf(vararg points: CurvePoint) = FanCurve(points.toList())

        /** 均衡 = 默认 = 移植前的硬编码曲线原样，老用户升级后行为零变化。 */
        private val BALANCED_CURVE = curveOf(
            CurvePoint(30, 0),
            CurvePoint(35, 30),
            CurvePoint(40, 70),
            CurvePoint(45, 80),
            CurvePoint(50, 90),
            CurvePoint(55, 99),
            CurvePoint(60, 99),
            CurvePoint(90, 99),
        )

        private val QUIET_CURVE = curveOf(
            CurvePoint(40, 0),
            CurvePoint(45, 20),
            CurvePoint(50, 40),
            CurvePoint(55, 60),
            CurvePoint(60, 75),
            CurvePoint(65, 90),
            CurvePoint(70, 99),
            CurvePoint(90, 99),
        )

        private val PERFORMANCE_CURVE = curveOf(
            CurvePoint(30, 40),
            CurvePoint(35, 60),
            CurvePoint(40, 80),
            CurvePoint(45, 90),
            CurvePoint(50, 95),
            CurvePoint(55, 99),
            CurvePoint(60, 99),
            CurvePoint(90, 99),
        )

        fun presetCurve(preset: CurvePreset): FanCurve = when (preset) {
            CurvePreset.QUIET -> QUIET_CURVE
            CurvePreset.BALANCED -> BALANCED_CURVE
            CurvePreset.PERFORMANCE -> PERFORMANCE_CURVE
        }

        /** 出厂默认曲线。 */
        val DEFAULT: FanCurve get() = BALANCED_CURVE

        /**
         * 曲线页画成虚线的参考基线。
         *
         * 直接复用默认曲线而不是凭空规定安全值：它来自参考实现的厂商默认值，有据可依，
         * 用户一看就懂「虚线是默认，我把它压低了」。
         */
        val BASELINE: FanCurve get() = BALANCED_CURVE
    }
}

/**
 * 曲线读取结果。
 *
 * 「缺失」与「损坏」必须分开：缺失是首次运行的正常状态，静默取默认即可；
 * 损坏是数据出了问题，**必须让用户知道**，不能当成"没有曲线"混过去。
 */
sealed interface CurveLoad {
    val curve: FanCurve

    /** 正常读取，或存储中尚无曲线（首次运行）。 */
    data class Loaded(override val curve: FanCurve) : CurveLoad

    /** 存储中的内容无法解析，已回退默认曲线，调用方负责告知用户。 */
    data class FellBack(override val curve: FanCurve, val reason: String) : CurveLoad
}

/**
 * 曲线的 JSON 编解码。
 *
 * 风格对齐 `data/ProfileJson.kt`：带版本字段；不认识的/损坏的内容抛错而不是静默丢弃。
 */
object FanCurveJson {

    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_POINTS = "points"
    private const val KEY_TEMP = "tempC"
    private const val KEY_DUTY = "pwmPercent"

    fun encode(curve: FanCurve): String = JSONObject()
        .put(KEY_VERSION, VERSION)
        .put(
            KEY_POINTS,
            JSONArray().also { array ->
                curve.points.forEach { point ->
                    array.put(
                        JSONObject()
                            .put(KEY_TEMP, point.tempC)
                            .put(KEY_DUTY, point.pwmPercent),
                    )
                }
            },
        )
        .toString()

    /**
     * @param stored null / 空白表示存储里还没有曲线（首次运行），返回默认曲线且**不算失败**。
     *   其余任何解析或校验失败都返回 [CurveLoad.FellBack]。
     */
    fun decode(stored: String?): CurveLoad {
        if (stored.isNullOrBlank()) return CurveLoad.Loaded(FanCurve.DEFAULT)
        return try {
            CurveLoad.Loaded(parse(stored))
        } catch (error: IOException) {
            CurveLoad.FellBack(FanCurve.DEFAULT, error.message ?: "invalid curve")
        }
    }

    /** 严格解析：任何问题都抛 [IOException]，绝不返回默认值。 */
    @Throws(IOException::class)
    fun parse(stored: String): FanCurve {
        val root = try {
            val reader = JSONTokener(stored)
            val value = reader.nextValue()
            if (value !is JSONObject || reader.nextClean() != '\u0000') {
                throw IOException("Invalid curve json: expected one JSON object")
            }
            value
        } catch (error: JSONException) {
            throw IOException("Invalid curve json", error)
        }

        val version = root.opt(KEY_VERSION) as? Number
            ?: throw IOException("Invalid curve json: missing version")
        if (version.toInt() > VERSION) {
            // 未来版本写下的曲线，本版本不理解其语义 —— 宁可回退也不猜。
            throw IOException("Unsupported curve version: ${version.toInt()}")
        }

        val array = root.opt(KEY_POINTS) as? JSONArray
            ?: throw IOException("Invalid curve json: missing points")

        val points = ArrayList<CurvePoint>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.opt(index) as? JSONObject
                ?: throw IOException("Invalid curve json: point $index is not an object")
            points += CurvePoint(
                tempC = readStrictInt(entry.opt(KEY_TEMP), "tempC", index),
                pwmPercent = readStrictInt(entry.opt(KEY_DUTY), "pwmPercent", index),
            )
        }

        val violation = FanCurve.validate(points)
        if (violation != null) throw IOException("Invalid curve: $violation")
        return FanCurve(points)
    }

    /**
     * JSON 数字读成 Int 时必须确认它**本来就是整数**：`30.5` 静默截断成 30 会让
     * 曲线在两个版本间静默变形。
     */
    @Throws(IOException::class)
    private fun readStrictInt(value: Any?, field: String, index: Int): Int {
        val number = value as? Number
            ?: throw IOException("Invalid curve json: point $index missing $field")
        val double = number.toDouble()
        val int = double.toInt()
        if (double != int.toDouble()) {
            throw IOException("Invalid curve json: point $index has non-integer $field")
        }
        return int
    }
}
