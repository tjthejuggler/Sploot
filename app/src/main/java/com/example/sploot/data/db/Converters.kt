package com.example.sploot.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room type converters backed by kotlinx-serialization (no Gson).
 *
 * Nullable lists serialize to `null`-storing TEXT columns; an empty list is
 * stored as `"[]"` so the two states stay distinguishable.
 */
class Converters {

    private val json = Json { encodeDefaults = true }
    private val floatList = ListSerializer(Float.serializer())
    private val doubleList = ListSerializer(Double.serializer())

    // ---- List<Float>? : feature vectors, stage probabilities ----

    @TypeConverter
    fun floatListToJson(value: List<Float>?): String? =
        value?.let { json.encodeToString(floatList, it) }

    @TypeConverter
    fun jsonToFloatList(value: String?): List<Float>? =
        value?.let { json.decodeFromString(floatList, it) }

    // ---- List<Double>? ----

    @TypeConverter
    fun doubleListToJson(value: List<Double>?): String? =
        value?.let { json.encodeToString(doubleList, it) }

    @TypeConverter
    fun jsonToDoubleList(value: String?): List<Double>? =
        value?.let { json.decodeFromString(doubleList, it) }
}
