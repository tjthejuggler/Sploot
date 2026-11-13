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

    // allowSpecialFloatingPointValues: feature vectors legitimately contain NaN
    // (e.g. actigraphy features on a PPI-only night with no accelerometer).
    // kotlinx-serialization rejects NaN by default, which crashed persisting a
    // fully-decoded sensor recording (on-device 2026-11-02). ONNX runtime
    // treats NaN features as missing, so round-tripping them is safe.
    private val json = Json {
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }
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
