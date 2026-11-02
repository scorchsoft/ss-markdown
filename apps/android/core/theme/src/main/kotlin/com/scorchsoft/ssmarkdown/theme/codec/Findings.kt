package com.scorchsoft.ssmarkdown.theme.codec

import com.scorchsoft.ssmarkdown.theme.json.JsonValue
import com.scorchsoft.ssmarkdown.theme.json.Member
import com.scorchsoft.ssmarkdown.theme.json.typeName

/**
 * What [ThemeReader] has found so far, plus the typed accessors that
 * record a problem whenever a value has the wrong JSON type. Reading
 * carries on after a problem so one import reports everything at once.
 */
internal class Findings {
    val problems = mutableListOf<ThemeProblem>()
    val warnings = mutableListOf<ThemeProblem>()

    fun problem(
        member: Member,
        message: String,
    ) {
        problems += ThemeProblem(member.line, member.name, message)
    }

    fun warn(
        member: Member,
        message: String,
    ) {
        warnings += ThemeProblem(member.line, member.name, message)
    }

    /** Warns about every member of [obj] whose name is not in [known]. */
    fun warnUnknown(
        obj: JsonValue.Obj,
        known: Set<String>,
    ) {
        obj.members.filter { it.name !in known }.forEach { warn(it, "isn't used and was ignored") }
    }

    fun string(member: Member): String? =
        (member.value as? JsonValue.Str)?.value ?: null.also { wrongType(member, "text") }

    fun number(member: Member): Double? =
        (member.value as? JsonValue.Num)?.value ?: null.also { wrongType(member, "a number") }

    fun objectOf(member: Member): JsonValue.Obj? =
        member.value as? JsonValue.Obj ?: null.also { wrongType(member, "an object") }

    private fun wrongType(
        member: Member,
        expected: String,
    ) = problem(member, "must be $expected, not ${member.value.typeName()}")
}

/** `16` rather than `16.0`, the way people write sizes. */
internal fun plainNumber(value: Double): String =
    if (value ==
        Math.rint(value)
    ) {
        value.toLong().toString()
    } else {
        value.toString()
    }
