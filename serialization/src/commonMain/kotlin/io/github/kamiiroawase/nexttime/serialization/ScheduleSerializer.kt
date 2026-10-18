package io.github.kamiiroawase.nexttime.serialization

import io.github.kamiiroawase.nexttime.Schedule
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** [ScheduleSerializer.descriptor] 的字段下标：与 [Schedule] 构造器参数序一致 */
private const val FIELD_LUNAR = 0
private const val FIELD_LEAP_COUNT = 1
private const val FIELD_TARGET_DAY = 2
private const val FIELD_TARGET_HOUR = 3
private const val FIELD_TARGET_MINUTE = 4
private const val FIELD_TARGET_SECOND = 5
private const val FIELD_REPEAT_INTERVAL = 6
private const val FIELD_REPEAT_UNIT = 7
private const val FIELD_WEEKDAYS = 8
private const val FIELD_WEEKDAY_ORDINALS = 9
private const val FIELD_MONTH_DAYS = 10
private const val FIELD_CLAMP_MONTH_DAYS = 11
private const val FIELD_REPEAT_COUNT = 12

private val intSetSerializer = SetSerializer(Int.serializer())

/**
 * [Schedule] 的 kotlinx-serialization 序列化器：字段名与 [Schedule] 属性名一一对应
 * （**线格式字段名即契约**，跨版本稳定）；`Set<Int>` 字段序列化为数组，编码前排序
 * （跨平台确定性线格式），解码接受任意顺序。
 *
 * 始终写全字段（与 Json 的 encodeDefaults 配置无关），解码端缺字段抛
 * [SerializationException]；未知字段的容忍性由消费方 Json 配置决定
 * （`Json { ignoreUnknownKeys = true }` 跳过）。解码走 [Schedule] 构造器，全部
 * 校验复跑——构造期的 [IllegalArgumentException]（如时分秒部分选择）转译为
 * [SerializationException] 并保留 cause，符合 kotlinx 的解码异常惯例。
 *
 * 用法：
 * ```kotlin
 * val json = Json.encodeToString(ScheduleSerializer, schedule)
 * val schedule = Json.decodeFromString(ScheduleSerializer, json)
 *
 * // 嵌入自己的可序列化模型：
 * @Serializable
 * data class CountdownItem(
 *     val title: String,
 *     @Serializable(with = ScheduleSerializer::class) val schedule: Schedule,
 * )
 * ```
 */
public object ScheduleSerializer : KSerializer<Schedule> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.github.kamiiroawase.nexttime.Schedule") {
            element("lunar", Boolean.serializer().descriptor)
            element("leapCount", Boolean.serializer().descriptor)
            element("targetDay", Long.serializer().descriptor)
            element("targetHour", Int.serializer().descriptor)
            element("targetMinute", Int.serializer().descriptor)
            element("targetSecond", Int.serializer().descriptor)
            element("repeatInterval", Int.serializer().descriptor)
            element("repeatUnit", Int.serializer().descriptor)
            element("weekdays", intSetSerializer.descriptor)
            element("weekdayOrdinals", intSetSerializer.descriptor)
            element("monthDays", intSetSerializer.descriptor)
            element("clampMonthDays", Boolean.serializer().descriptor)
            element("repeatCount", Int.serializer().descriptor)
        }

    override fun serialize(
        encoder: Encoder,
        value: Schedule,
    ) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeBooleanElement(descriptor, FIELD_LUNAR, value.lunar)
        composite.encodeBooleanElement(descriptor, FIELD_LEAP_COUNT, value.leapCount)
        composite.encodeLongElement(descriptor, FIELD_TARGET_DAY, value.targetDay)
        composite.encodeIntElement(descriptor, FIELD_TARGET_HOUR, value.targetHour)
        composite.encodeIntElement(descriptor, FIELD_TARGET_MINUTE, value.targetMinute)
        composite.encodeIntElement(descriptor, FIELD_TARGET_SECOND, value.targetSecond)
        composite.encodeIntElement(descriptor, FIELD_REPEAT_INTERVAL, value.repeatInterval)
        composite.encodeIntElement(descriptor, FIELD_REPEAT_UNIT, value.repeatUnit)
        composite.encodeSerializableElement(descriptor, FIELD_WEEKDAYS, intSetSerializer, value.weekdays.sorted().toSet())
        composite.encodeSerializableElement(descriptor, FIELD_WEEKDAY_ORDINALS, intSetSerializer, value.weekdayOrdinals.sorted().toSet())
        composite.encodeSerializableElement(descriptor, FIELD_MONTH_DAYS, intSetSerializer, value.monthDays.sorted().toSet())
        composite.encodeBooleanElement(descriptor, FIELD_CLAMP_MONTH_DAYS, value.clampMonthDays)
        composite.encodeIntElement(descriptor, FIELD_REPEAT_COUNT, value.repeatCount)
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): Schedule {
        val composite = decoder.beginStructure(descriptor)
        var lunar: Boolean? = null
        var leapCount: Boolean? = null
        var targetDay: Long? = null
        var targetHour: Int? = null
        var targetMinute: Int? = null
        var targetSecond: Int? = null
        var repeatInterval: Int? = null
        var repeatUnit: Int? = null
        var weekdays: Set<Int>? = null
        var weekdayOrdinals: Set<Int>? = null
        var monthDays: Set<Int>? = null
        var clampMonthDays: Boolean? = null
        var repeatCount: Int? = null

        loop@ while (true) {
            when (val index = composite.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                FIELD_LUNAR -> lunar = composite.decodeBooleanElement(descriptor, index)
                FIELD_LEAP_COUNT -> leapCount = composite.decodeBooleanElement(descriptor, index)
                FIELD_TARGET_DAY -> targetDay = composite.decodeLongElement(descriptor, index)
                FIELD_TARGET_HOUR -> targetHour = composite.decodeIntElement(descriptor, index)
                FIELD_TARGET_MINUTE -> targetMinute = composite.decodeIntElement(descriptor, index)
                FIELD_TARGET_SECOND -> targetSecond = composite.decodeIntElement(descriptor, index)
                FIELD_REPEAT_INTERVAL -> repeatInterval = composite.decodeIntElement(descriptor, index)
                FIELD_REPEAT_UNIT -> repeatUnit = composite.decodeIntElement(descriptor, index)
                FIELD_WEEKDAYS -> weekdays = composite.decodeSerializableElement(descriptor, index, intSetSerializer)
                FIELD_WEEKDAY_ORDINALS -> weekdayOrdinals = composite.decodeSerializableElement(descriptor, index, intSetSerializer)
                FIELD_MONTH_DAYS -> monthDays = composite.decodeSerializableElement(descriptor, index, intSetSerializer)
                FIELD_CLAMP_MONTH_DAYS -> clampMonthDays = composite.decodeBooleanElement(descriptor, index)
                FIELD_REPEAT_COUNT -> repeatCount = composite.decodeIntElement(descriptor, index)
                else -> throw SerializationException("Unexpected index $index for ${descriptor.serialName}")
            }
        }
        composite.endStructure(descriptor)

        fun required(name: String): Nothing = throw SerializationException("Missing required field $name in ${descriptor.serialName}")

        return try {
            Schedule(
                lunar = lunar ?: required("lunar"),
                leapCount = leapCount ?: required("leapCount"),
                targetDay = targetDay ?: required("targetDay"),
                targetHour = targetHour ?: required("targetHour"),
                targetMinute = targetMinute ?: required("targetMinute"),
                targetSecond = targetSecond ?: required("targetSecond"),
                repeatInterval = repeatInterval ?: required("repeatInterval"),
                repeatUnit = repeatUnit ?: required("repeatUnit"),
                weekdays = weekdays ?: required("weekdays"),
                weekdayOrdinals = weekdayOrdinals ?: required("weekdayOrdinals"),
                monthDays = monthDays ?: required("monthDays"),
                clampMonthDays = clampMonthDays ?: required("clampMonthDays"),
                repeatCount = repeatCount ?: required("repeatCount"),
            )
        } catch (e: IllegalArgumentException) {
            // 构造期校验（字段取值/组合非法）转译为解码异常惯例类型
            throw SerializationException("Decoded fields do not form a valid Schedule: ${e.message}", e)
        }
    }
}
