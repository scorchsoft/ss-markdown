package com.scorchsoft.ssmarkdown.theme.json

/**
 * A parsed JSON value that remembers the line it started on, so theme
 * validation can say "line 12, “bg” isn't a colour" (mockup MoreStates).
 * General-purpose JSON libraries throw that position away, which is why
 * `core:theme` carries this small reader instead of a dependency.
 */
internal sealed interface JsonValue {
    /** 1-based line of the value's first character. */
    val line: Int

    data class Obj(
        override val line: Int,
        val members: List<Member>,
    ) : JsonValue {
        fun member(name: String): Member? = members.firstOrNull { it.name == name }
    }

    data class Arr(
        override val line: Int,
        val items: List<JsonValue>,
    ) : JsonValue

    data class Str(
        override val line: Int,
        val value: String,
    ) : JsonValue

    data class Num(
        override val line: Int,
        val value: Double,
    ) : JsonValue

    data class Bool(
        override val line: Int,
        val value: Boolean,
    ) : JsonValue

    data class Null(
        override val line: Int,
    ) : JsonValue
}

/**
 * One `"name": value` pair.
 *
 * @property line where the name was written; errors about a key point here.
 */
internal data class Member(
    val name: String,
    val line: Int,
    val value: JsonValue,
)

/** What [JsonReader.parse] produces: a value, or where and why the text is not JSON. */
internal sealed interface JsonParse {
    data class Ok(
        val value: JsonValue,
    ) : JsonParse

    data class Failed(
        val line: Int,
        val message: String,
    ) : JsonParse
}

/** A short human name for a value's type, for "must be a number, not text" messages. */
internal fun JsonValue.typeName(): String =
    when (this) {
        is JsonValue.Obj -> "an object"
        is JsonValue.Arr -> "a list"
        is JsonValue.Str -> "text"
        is JsonValue.Num -> "a number"
        is JsonValue.Bool -> "true/false"
        is JsonValue.Null -> "null"
    }
