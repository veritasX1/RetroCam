package com.retrocam.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromGrainStrength(v: GrainStrength): String = v.name

    @TypeConverter
    fun toGrainStrength(v: String): GrainStrength = GrainStrength.valueOf(v)

    @TypeConverter
    fun fromGrainSize(v: GrainSize): String = v.name

    @TypeConverter
    fun toGrainSize(v: String): GrainSize = GrainSize.valueOf(v)

    @TypeConverter
    fun fromEffectStrength(v: EffectStrength): String = v.name

    @TypeConverter
    fun toEffectStrength(v: String): EffectStrength = EffectStrength.valueOf(v)
}
