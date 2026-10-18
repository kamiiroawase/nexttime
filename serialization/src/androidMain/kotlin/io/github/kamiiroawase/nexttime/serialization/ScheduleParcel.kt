package io.github.kamiiroawase.nexttime.serialization

import android.os.Parcel
import android.os.Parcelable
import io.github.kamiiroawase.nexttime.Schedule

/**
 * [Schedule] 的 Android [Parcelable] 包装：进程重建、Fragment/Activity 传参场景用。
 * 手写 Parcel 读写而非 @Parcelize——parcelize 编译器插件与 AGP 的 KMP library
 * 插件组合存在兼容风险，13 个基本类型字段直写直读零依赖。
 *
 * 读回时走 [Schedule] 构造器复跑全部校验；Parcel 内容损坏（字段越界、组合非法）
 * 按 [IllegalArgumentException] 抛出，与 [android.os.Parcel] 读取损坏数据的行为
 * 一致——调用方（如 CREATOR 场景）自行决定兜底。
 *
 * Bundle 传参亦可直接传 [ScheduleSerializer] 编出的 JSON 字符串（putString），
 * 本类免去一次编解码；两种形态语义等价。
 */
public class ScheduleParcel : Parcelable {
    /** 包装的日程 */
    public val schedule: Schedule

    public constructor(schedule: Schedule) {
        this.schedule = schedule
    }

    private constructor(parcel: Parcel) {
        // 字段序与 [ScheduleSerializer] 一致（构造器参数序）；布尔以字节承载——
        // Parcel.readBoolean/writeBoolean 是 API 29+，minSdk 24 须用 readByte/writeByte
        schedule =
            try {
                Schedule(
                    lunar = parcel.readByte() != 0.toByte(),
                    leapCount = parcel.readByte() != 0.toByte(),
                    targetDay = parcel.readLong(),
                    targetHour = parcel.readInt(),
                    targetMinute = parcel.readInt(),
                    targetSecond = parcel.readInt(),
                    repeatInterval = parcel.readInt(),
                    repeatUnit = parcel.readInt(),
                    weekdays = parcel.createIntArray()?.toSet() ?: emptySet(),
                    weekdayOrdinals = parcel.createIntArray()?.toSet() ?: emptySet(),
                    monthDays = parcel.createIntArray()?.toSet() ?: emptySet(),
                    clampMonthDays = parcel.readByte() != 0.toByte(),
                    repeatCount = parcel.readInt(),
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Parcel does not contain a valid Schedule: ${e.message}", e)
            }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        parcel: Parcel,
        flags: Int,
    ) {
        parcel.writeByte(if (schedule.lunar) 1 else 0)
        parcel.writeByte(if (schedule.leapCount) 1 else 0)
        parcel.writeLong(schedule.targetDay)
        parcel.writeInt(schedule.targetHour)
        parcel.writeInt(schedule.targetMinute)
        parcel.writeInt(schedule.targetSecond)
        parcel.writeInt(schedule.repeatInterval)
        parcel.writeInt(schedule.repeatUnit)
        parcel.writeIntArray(schedule.weekdays.toIntArray())
        parcel.writeIntArray(schedule.weekdayOrdinals.toIntArray())
        parcel.writeIntArray(schedule.monthDays.toIntArray())
        parcel.writeByte(if (schedule.clampMonthDays) 1 else 0)
        parcel.writeInt(schedule.repeatCount)
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<ScheduleParcel> =
            object : Parcelable.Creator<ScheduleParcel> {
                override fun createFromParcel(parcel: Parcel): ScheduleParcel = ScheduleParcel(parcel)

                override fun newArray(size: Int): Array<ScheduleParcel?> = arrayOfNulls(size)
            }
    }
}
