package dev.lacelang.executor.ext

/**
 * Built-in primitive functions available inside .laceext rule bodies
 * and functions. Implements lace-extensions.md §7.
 */
object Primitives {

    val FUNCTIONS: Map<String, (List<Any?>) -> Any?> = mapOf(
        "compare" to { args -> compare(args.getOrNull(0), args.getOrNull(1)) },
        "map_get" to { args -> mapGet(args.getOrNull(0), args.getOrNull(1)) },
        "map_match" to { args -> mapMatch(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2), args.getOrNull(3)) },
        "is_null" to { args -> args.getOrNull(0) == null },
        "type_of" to { args -> typeOf(args.getOrNull(0)) },
        "to_string" to { args -> toString(args.getOrNull(0)) },
        "replace" to { args -> replace(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2)) },
    )

    fun compare(a: Any?, b: Any?): String? {
        if (a == null || b == null) return null
        // Bool: only eq/neq meaningful.
        if (a is Boolean || b is Boolean) {
            if (a !is Boolean || b !is Boolean) return null
            return if (a == b) "eq" else "neq"
        }
        // Numeric: int and float are comparable.
        if (a is Number && b is Number) {
            val da = a.toDouble()
            val db = b.toDouble()
            return when {
                da < db -> "lt"
                da > db -> "gt"
                da == db -> "eq"
                else -> "neq"
            }
        }
        // String comparison.
        if (a is String && b is String) {
            return when {
                a < b -> "lt"
                a > b -> "gt"
                a == b -> "eq"
                else -> "neq"
            }
        }
        // Incomparable types.
        return null
    }

    fun mapGet(m: Any?, key: Any?): Any? {
        if (m !is Map<*, *>) return null
        if (key != null && m.containsKey(key)) return m[key]
        if (m.containsKey("default")) return m["default"]
        return null
    }

    fun mapMatch(m: Any?, actual: Any?, expected: Any?, op: Any?): Any? {
        if (m !is Map<*, *>) return null
        val actualKey = scalarToKey(actual)
        if (actualKey != null && m.containsKey(actualKey)) return m[actualKey]
        val rel = compare(actual, expected)
        if (rel != null && m.containsKey(rel)) return m[rel]
        if (m.containsKey("default")) return m["default"]
        return null
    }

    private fun scalarToKey(v: Any?): String? = when {
        v is Boolean -> if (v) "true" else "false"
        v is Number -> v.toString()
        v is String -> v
        else -> null
    }

    fun typeOf(v: Any?): String = when {
        v == null -> "null"
        v is Boolean -> "bool"
        v is Int -> "int"
        v is Long -> "int"
        v is Float -> "float"
        v is Double -> "float"
        v is String -> "string"
        v is List<*> -> "array"
        v is Map<*, *> -> "object"
        else -> "any"
    }

    fun toString(v: Any?): String = when {
        v == null -> "null"
        v is Boolean -> if (v) "true" else "false"
        v is String -> v
        else -> v.toString()
    }

    fun replace(s: Any?, pattern: Any?, replacement: Any?): Any? {
        if (s == null || pattern == null) return s
        return s.toString().replace(pattern.toString(), toString(replacement))
    }
}
