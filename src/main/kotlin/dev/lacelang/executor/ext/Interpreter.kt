package dev.lacelang.executor.ext

/**
 * Tree-walking interpreter for the .laceext rule body DSL.
 *
 * Implements: null-propagating field/index/filter access, for/when/let/
 * set/emit/exit/return, ternary, boolean, arithmetic, comparison,
 * function dispatch (primitives, extension-defined, tag constructors,
 * qualified cross-extension calls), emit-target validation.
 */

private class ExitRule : Exception()
private class ReturnValue(val value: Any?) : Exception()

class Scope(private val parent: Scope? = null) {
    private val vars = mutableMapOf<String, Any?>()

    fun get(name: String): Any? {
        if (name in vars) return vars[name]
        return parent?.get(name)
    }

    fun has(name: String): Boolean {
        if (name in vars) return true
        return parent?.has(name) ?: false
    }

    fun put(name: String, value: Any?) { vars[name] = value }

    fun set(name: String, value: Any?): Boolean {
        if (name in vars) { vars[name] = value; return true }
        return parent?.set(name, value) ?: false
    }

    fun child(): Scope = Scope(this)
}

class Interpreter(
    private val extName: String,
    private val functions: Map<String, Map<String, Any?>>,
    private val tagConstructors: Map<String, (List<Any?>) -> Any?>,
    private val emitCallback: (List<String>, Map<String, Any?>) -> Unit,
    private val config: Map<String, Any?> = emptyMap(),
    private val requireView: Map<String, Map<String, Any?>> = emptyMap(),
    private val qualifiedCall: ((String, String, List<Any?>) -> Any?)? = null,
    private val requires: Set<String> = emptySet(),
) {
    fun runRule(body: List<Map<String, Any?>>, context: Map<String, Any?>) {
        val scope = Scope()
        for ((k, v) in context) scope.put(k, v)
        try { runStmts(body, scope) } catch (_: ExitRule) {}
    }

    @Suppress("UNCHECKED_CAST")
    internal fun callFunction(name: String, args: List<Any?>): Any? {
        val spec = functions[name] ?: throw RuntimeException("unknown function: '$name'")
        val body = spec["body"] as? List<Map<String, Any?>> ?: return null
        val params = spec["params"] as? List<String> ?: emptyList()
        if (args.size != params.size) throw RuntimeException("function '$name' expected ${params.size} args, got ${args.size}")
        val scope = Scope()
        for (i in params.indices) scope.put(params[i], args[i])
        return try { runStmts(body, scope); null } catch (r: ReturnValue) { r.value }
    }

    // ── statements ────────────────────────────────────────────────

    private fun runStmts(stmts: List<Map<String, Any?>>, scope: Scope) {
        for (st in stmts) runStmt(st, scope)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runStmt(st: Map<String, Any?>, scope: Scope) {
        when (st["kind"]) {
            "when_inline" -> { if (!truthy(eval(st["cond"] as Map<String, Any?>, scope))) throw ExitRule() }
            "when_block" -> { if (truthy(eval(st["cond"] as Map<String, Any?>, scope))) runStmts(st["body"] as List<Map<String, Any?>>, scope.child()) }
            "for" -> {
                val iter = eval(st["iter"] as Map<String, Any?>, scope)
                if (iter is List<*>) for (v in iter) {
                    val inner = scope.child(); inner.put(st["binding"] as String, v)
                    runStmts(st["body"] as List<Map<String, Any?>>, inner)
                }
            }
            "let" -> {
                val name = st["name"] as String
                if (scope.has(name)) throw RuntimeException("let: name \$$name already bound in this scope")
                scope.put(name, eval(st["expr"] as Map<String, Any?>, scope))
            }
            "set" -> {
                val name = st["name"] as String
                if (!scope.set(name, eval(st["expr"] as Map<String, Any?>, scope)))
                    throw RuntimeException("set: name \$$name is not bound in any enclosing scope")
            }
            "emit" -> runEmit(st, scope)
            "exit" -> throw ExitRule()
            "return" -> throw ReturnValue(eval(st["expr"] as Map<String, Any?>, scope))
            "call_stmt" -> eval(st["call"] as Map<String, Any?>, scope)
            else -> throw RuntimeException("unknown statement kind: ${st["kind"]}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runEmit(st: Map<String, Any?>, scope: Scope) {
        val target = st["target"] as List<String>
        if (target.size < 2 || target[0] != "result") throw RuntimeException("invalid emit target: ${target.joinToString(".")}")
        val payload = mutableMapOf<String, Any?>()
        for (f in st["fields"] as List<Map<String, Any?>>) {
            payload[f["key"] as String] = eval(f["value"] as Map<String, Any?>, scope)
        }
        if (target == listOf("result", "runVars")) {
            val prefixed = mutableMapOf<String, Any?>()
            for ((k, v) in payload) {
                if (!k.startsWith("$extName.")) throw RuntimeException("extension '$extName' emitted run_vars key '$k' without required prefix")
                prefixed[k] = v
            }
            emitCallback(target, prefixed)
        } else {
            emitCallback(target, payload)
        }
    }

    // ── expressions ───────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    fun eval(node: Map<String, Any?>, scope: Scope): Any? {
        return when (node["kind"]) {
            "literal" -> node["value"]
            "base" -> when (node["name"]) {
                "this" -> scope.get("this")
                "prev" -> scope.get("prev")
                "result" -> scope.get("result")
                "config" -> config
                "require" -> requireView
                else -> null
            }
            "binding" -> scope.get(node["name"] as String)
            "ident" -> scope.get(node["name"] as String)
            "access_field" -> {
                val base = eval(node["base"] as Map<String, Any?>, scope)
                if (base is Map<*, *>) base[node["name"]] else null
            }
            "access_index" -> {
                val base = eval(node["base"] as Map<String, Any?>, scope)
                val idx = eval(node["index"] as Map<String, Any?>, scope)
                when {
                    base is List<*> && idx is Number -> base.getOrNull(idx.toInt())
                    base is Map<*, *> && idx is String -> base[idx]
                    else -> null
                }
            }
            "access_filter" -> {
                val base = eval(node["base"] as Map<String, Any?>, scope)
                if (base !is List<*>) return null
                for (item in base) {
                    val inner = scope.child(); inner.put("$", item)
                    if (truthy(eval(node["cond"] as Map<String, Any?>, inner))) return item
                }
                null
            }
            "ternary" -> {
                if (truthy(eval(node["cond"] as Map<String, Any?>, scope)))
                    eval(node["then"] as Map<String, Any?>, scope)
                else eval(node["else"] as Map<String, Any?>, scope)
            }
            "binop" -> evalBinop(node, scope)
            "unop" -> evalUnop(node, scope)
            "call", "qualified_call" -> evalCall(node, scope)
            "object_lit" -> {
                val fields = node["fields"] as? List<Map<String, Any?>> ?: emptyList()
                val result = linkedMapOf<String, Any?>()
                for (f in fields) result[f["key"] as String] = eval(f["value"] as Map<String, Any?>, scope)
                result
            }
            else -> throw RuntimeException("unknown expression kind: ${node["kind"]}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalBinop(node: Map<String, Any?>, scope: Scope): Any? {
        val op = node["op"] as String
        if (op == "and") {
            val left = eval(node["left"] as Map<String, Any?>, scope)
            if (!truthy(left)) return if (left == null) null else false
            return eval(node["right"] as Map<String, Any?>, scope)
        }
        if (op == "or") {
            val left = eval(node["left"] as Map<String, Any?>, scope)
            if (truthy(left)) return left
            return eval(node["right"] as Map<String, Any?>, scope)
        }
        val a = eval(node["left"] as Map<String, Any?>, scope)
        val b = eval(node["right"] as Map<String, Any?>, scope)
        if (op == "eq") return a == b
        if (op == "neq") return a != b
        // arithmetic + ordered compare: null propagates
        if (a == null || b == null) return null
        return try {
            when (op) {
                "lt" -> numCmp(a, b)?.let { it < 0 }
                "lte" -> numCmp(a, b)?.let { it <= 0 }
                "gt" -> numCmp(a, b)?.let { it > 0 }
                "gte" -> numCmp(a, b)?.let { it >= 0 }
                "+" -> {
                    if (a is String && b is String) a + b
                    else if (a is Number && b is Number && a !is Boolean && b !is Boolean) numAdd(a, b)
                    else null
                }
                "-" -> if (a is Number && b is Number) numSub(a, b) else null
                "*" -> if (a is Number && b is Number) numMul(a, b) else null
                "/" -> if (a is Number && b is Number) {
                    if (toDouble(b) == 0.0) null
                    else if (a is Double || b is Double) toDouble(a) / toDouble(b)
                    else Math.floorDiv(toInt(a), toInt(b))
                } else null
                else -> null
            }
        } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalUnop(node: Map<String, Any?>, scope: Scope): Any? {
        val op = node["op"] as String
        val v = eval(node["operand"] as Map<String, Any?>, scope)
        return when (op) {
            "not" -> !truthy(v)
            "-" -> if (v is Number && v !is Boolean) numNeg(v) else null
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalCall(node: Map<String, Any?>, scope: Scope): Any? {
        if (node["kind"] == "qualified_call") {
            val ext = node["ext"] as String
            val name = node["name"] as String
            val args = (node["args"] as? List<Map<String, Any?>> ?: emptyList()).map { eval(it, scope) }
            if (ext !in requires) throw RuntimeException("extension '$extName' called $ext.$name(...) but does not require '$ext'")
            val qc = qualifiedCall ?: throw RuntimeException("qualified function call unavailable in this context")
            return qc(ext, name, args)
        }
        val name = node["name"] as String
        val args = (node["args"] as? List<Map<String, Any?>> ?: emptyList()).map { eval(it, scope) }
        val prim = Primitives.FUNCTIONS[name]
        if (prim != null) return prim(args)
        val tagCtor = tagConstructors[name]
        if (tagCtor != null) return tagCtor(args)
        if (name in functions) return callFunction(name, args)
        throw RuntimeException("unknown function in .laceext rule: '$name'")
    }

    companion object {
        fun truthy(v: Any?): Boolean = when {
            v == null -> false
            v is Boolean -> v
            else -> true
        }

        // Numeric helpers preserving int vs double
        private fun toDouble(v: Any): Double = (v as Number).toDouble()
        private fun toInt(v: Any): Int = (v as Number).toInt()

        private fun numCmp(a: Any, b: Any): Int? {
            if (a !is Number || b !is Number) return null
            if (a is Boolean || b is Boolean) return null
            return toDouble(a).compareTo(toDouble(b))
        }

        private fun numAdd(a: Number, b: Number): Number =
            if (a is Double || b is Double) toDouble(a) + toDouble(b)
            else toInt(a) + toInt(b)

        private fun numSub(a: Number, b: Number): Number =
            if (a is Double || b is Double) toDouble(a) - toDouble(b)
            else toInt(a) - toInt(b)

        private fun numMul(a: Number, b: Number): Number =
            if (a is Double || b is Double) toDouble(a) * toDouble(b)
            else toInt(a) * toInt(b)

        private fun numNeg(v: Number): Number =
            if (v is Double) -v else -(v.toInt())
    }
}
