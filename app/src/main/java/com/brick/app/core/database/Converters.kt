package com.brick.app.core.database

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Room TypeConverters for complex types:
 * - Set<String> <-> JSON string (e.g. package names in ModeEntity)
 * - Enums (TokenType, ModeType, UnbrickTrigger) <-> String
 */
class Converters {

    @TypeConverter
    fun fromStringSet(set: Set<String>?): String {
        if (set.isNullOrEmpty()) return "[]"
        val jsonArray = JSONArray()
        for (item in set) {
            jsonArray.put(item)
        }
        return jsonArray.toString()
    }

    @TypeConverter
    fun toStringSet(value: String?): Set<String> {
        if (value.isNullOrBlank() || value == "[]") return emptySet()
        return try {
            val jsonArray = JSONArray(value)
            val result = LinkedHashSet<String>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                result.add(jsonArray.getString(i))
            }
            result
        } catch (e: Throwable) {
            // Pure-Kotlin fallback for unmocked JVM unit tests or malformed JSON
            parseSimpleJsonArray(value)
        }
    }

    @TypeConverter
    fun fromTokenType(type: TokenType?): String? = type?.name

    @TypeConverter
    fun toTokenType(value: String?): TokenType? =
        value?.let { runCatching { TokenType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromModeType(type: ModeType?): String? = type?.name

    @TypeConverter
    fun toModeType(value: String?): ModeType? =
        value?.let { runCatching { ModeType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromUnbrickTrigger(trigger: UnbrickTrigger?): String? = trigger?.name

    @TypeConverter
    fun toUnbrickTrigger(value: String?): UnbrickTrigger? =
        value?.let { runCatching { UnbrickTrigger.valueOf(it) }.getOrNull() }

    private fun parseSimpleJsonArray(raw: String): Set<String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptySet()
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptySet()

        val result = mutableSetOf<String>()
        val regex = Regex("\"((?:\\\\\"|[^\"])*)\"")
        for (match in regex.findAll(inner)) {
            val unescaped = match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            result.add(unescaped)
        }
        return result
    }
}
