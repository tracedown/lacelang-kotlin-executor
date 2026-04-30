package dev.lacelang.executor.ext

/**
 * Extension registry — central coordinator for loaded `.laceext` files.
 *
 * Responsibilities: hold loaded Extensions, validate require lists, aggregate
 * emit results (actions + runVars), fire hooks in topo-sorted order, and expose
 * cross-extension qualified_call dispatch.
 */

data class RuleTriple(val extName: String, val ext: Extension, val rule: RuleDef, val reg: HookRegistration)

class ExtensionRegistry(private val extensionConfig: Map<String, Any?> = emptyMap()) {
    val extensions = mutableListOf<Extension>()
    val actions = mutableMapOf<String, MutableList<Any?>>()
    val extRunVars = mutableMapOf<String, Any?>()
    val perExtRunVars = mutableMapOf<String, MutableMap<String, Any?>>()
    val warnings = mutableListOf<String>()

    fun load(path: String): Extension {
        val ext = loadExtension(path)
        extensions.add(ext)
        perExtRunVars.getOrPut(ext.name) { mutableMapOf() }
        return ext
    }

    fun finalize() {
        val loaded = extensions.map { it.name }.toSet()
        // 1. require presence
        for (ext in extensions) {
            for (dep in ext.requires) {
                if (dep !in loaded) throw RuntimeException("extension '${ext.name}' requires '$dep', but '$dep' is not loaded")
            }
        }
        // 2. after/before name resolution
        for (ext in extensions) {
            for (rule in ext.rules) {
                for (reg in rule.hooks) {
                    for (target in reg.after + reg.before) {
                        if (target !in loaded)
                            throw RuntimeException("extension '${ext.name}' rule '${rule.name}' on hook '${reg.hook}': unknown extension '$target' in 'after'/'before' qualifier")
                    }
                }
            }
        }
        // 3. cross-extension function call graph cycle check
        checkCrossExtRecursion(extensions)
    }

    fun isActive(name: String): Boolean = extensions.any { it.name == name }

    fun tagConstructors(): Map<String, (List<Any?>) -> Any?> {
        val out = mutableMapOf<String, (List<Any?>) -> Any?>()
        for (e in extensions) out.putAll(e.tagConstructors())
        return out
    }

    // ── hook dispatch (topo-sorted) ───────────────────────────────

    fun fireHook(hook: String, context: Map<String, Any?>) {
        var triples = gatherRulesForHook(hook)
        if (triples.isEmpty()) return

        // Step 3: silent drop
        while (true) {
            val extHasRulesHere = triples.map { it.extName }.toSet()
            var dropped = false
            val survivors = mutableListOf<RuleTriple>()
            for (rt in triples) {
                val ok = rt.reg.after.all { it in extHasRulesHere } && rt.reg.before.all { it in extHasRulesHere }
                if (ok) survivors.add(rt) else dropped = true
            }
            if (!dropped) break
            triples = survivors
            if (triples.isEmpty()) return
        }

        // Step 2: add implicit after-edges from require
        val extHasRulesHere = triples.map { it.extName }.toSet()
        val edges = mutableListOf<Pair<Int, Int>>()
        val indexByExt = mutableMapOf<String, MutableList<Int>>()
        for ((idx, rt) in triples.withIndex()) indexByExt.getOrPut(rt.extName) { mutableListOf() }.add(idx)

        for ((idx, rt) in triples.withIndex()) {
            for (target in rt.reg.after) for (src in indexByExt[target] ?: emptyList()) edges.add(src to idx)
            for (target in rt.reg.before) for (dst in indexByExt[target] ?: emptyList()) edges.add(idx to dst)
            for (dep in rt.ext.requires) {
                if (dep !in extHasRulesHere) continue
                if (dep in rt.reg.before) continue
                for (src in indexByExt[dep] ?: emptyList()) {
                    val edge = src to idx
                    if (edge !in edges) edges.add(edge)
                }
            }
        }

        val order = topoSort(triples.size, edges, triples)

        // Step 5: execute
        for (i in order) {
            val rt = triples[i]
            val interp = buildInterpreter(rt.ext)
            try {
                interp.runRule(rt.rule.body, context.toMutableMap())
            } catch (e: Exception) {
                warnings.add("extension '${rt.extName}' rule '${rt.rule.name}' on '$hook': ${e.message}")
            }
        }
    }

    private fun gatherRulesForHook(hook: String): List<RuleTriple> {
        val out = mutableListOf<RuleTriple>()
        for (ext in extensions) for (rule in ext.rules) for (reg in rule.hooks) {
            if (reg.hook == hook) out.add(RuleTriple(ext.name, ext, rule, reg))
        }
        return out
    }

    private fun buildInterpreter(ext: Extension): Interpreter {
        val depView = mutableMapOf<String, Map<String, Any?>>()
        for (dep in ext.requires) depView[dep] = perExtRunVars[dep]?.toMap() ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val userCfg = ((extensionConfig[ext.name] as? Map<String, Any?>) ?: emptyMap())
            .filterKeys { it != "laceext" }
        val extCfg = ext.configDefaults.toMutableMap().apply { putAll(userCfg) }
        return Interpreter(
            extName = ext.name,
            functions = ext.functionSpecs(),
            tagConstructors = tagConstructors(),
            emitCallback = ::emit,
            config = extCfg,
            requireView = depView,
            qualifiedCall = ::invokeExposed,
            requires = ext.requires.toSet(),
        )
    }

    private fun invokeExposed(extName: String, fnName: String, args: List<Any?>): Any? {
        val owner = extensions.firstOrNull { it.name == extName }
            ?: throw RuntimeException("qualified call to unknown extension '$extName'")
        val fn = owner.functions[fnName]
        if (fn == null || !fn.exposed)
            throw RuntimeException("$extName.$fnName is not an exposed function (declare [functions.$fnName].exposed = true)")
        val ownerInterp = buildInterpreter(owner)
        return ownerInterp.callFunction(fnName, args)
    }

    private fun emit(target: List<String>, payload: Map<String, Any?>) {
        if (target.size == 3 && target[0] == "result" && target[1] == "actions") {
            actions.getOrPut(target[2]) { mutableListOf() }.add(payload)
            return
        }
        if (target == listOf("result", "runVars")) {
            extRunVars.putAll(payload)
            for ((key, value) in payload) {
                val owner = key.split(".", limit = 2)[0]
                perExtRunVars.getOrPut(owner) { mutableMapOf() }[key] = value
            }
            return
        }
        warnings.add("emit to disallowed target: ${target.joinToString(".")}")
    }
}

// ── topo sort (Kahn's algorithm) ──────────────────────────────────

private fun topoSort(n: Int, edges: List<Pair<Int, Int>>, nodes: List<RuleTriple>): List<Int> {
    val indeg = IntArray(n)
    val adj = Array(n) { mutableListOf<Int>() }
    for ((a, b) in edges) { adj[a].add(b); indeg[b]++ }

    val cmp = compareBy<Int>({ nodes[it].rule.declarationIndex }, { nodes[it].extName })

    val ready = (0 until n).filter { indeg[it] == 0 }.sortedWith(cmp).toMutableList()
    val out = mutableListOf<Int>()
    while (ready.isNotEmpty()) {
        val i = ready.removeAt(0)
        out.add(i)
        for (j in adj[i]) {
            indeg[j]--
            if (indeg[j] == 0) {
                ready.add(j)
                ready.sortWith(cmp)
            }
        }
    }
    if (out.size != n) {
        val stuck = (0 until n).filter { indeg[it] > 0 }.map { nodes[it] }
        val desc = stuck.joinToString(", ") { "${it.extName}:${it.rule.name}" }
        throw RuntimeException("extension hook cycle among rules: $desc")
    }
    return out
}

// ── cross-extension function recursion check ──────────────────────

private fun checkCrossExtRecursion(extensions: List<Extension>) {
    val edges = mutableSetOf<Pair<Pair<String, String>, Pair<String, String>>>()
    val nodes = mutableSetOf<Pair<String, String>>()
    for (ext in extensions) {
        for ((fname, fdef) in ext.functions) {
            val caller = ext.name to fname
            nodes.add(caller)
            walkCallTargets(fdef.body, ext.name, edges, caller)
        }
    }
    val adj = nodes.associateWith { mutableListOf<Pair<String, String>>() }.toMutableMap()
    for ((src, dst) in edges) {
        adj.getOrPut(src) { mutableListOf() }.add(dst)
        adj.getOrPut(dst) { mutableListOf() }
    }
    val WHITE = 0; val GREY = 1; val BLACK = 2
    val color = adj.keys.associateWith { WHITE }.toMutableMap()

    fun visit(n: Pair<String, String>, stack: MutableList<Pair<String, String>>) {
        color[n] = GREY; stack.add(n)
        for (m in adj[n] ?: emptyList()) {
            if (color[m] == GREY) {
                val cycle = stack.subList(stack.indexOf(m), stack.size) + listOf(m)
                val path = cycle.joinToString(" → ") { "${it.first}.${it.second}" }
                throw RuntimeException("function call cycle: $path")
            }
            if (color[m] == WHITE) visit(m, stack)
        }
        stack.removeAt(stack.lastIndex); color[n] = BLACK
    }
    for (n in adj.keys.toList()) if (color[n] == WHITE) visit(n, mutableListOf())
}

@Suppress("UNCHECKED_CAST")
private fun walkCallTargets(
    node: Any?,
    owner: String,
    edges: MutableSet<Pair<Pair<String, String>, Pair<String, String>>>,
    caller: Pair<String, String>,
) {
    when (node) {
        is Map<*, *> -> {
            when (node["kind"]) {
                "call" -> edges.add(caller to (owner to (node["name"] as String)))
                "qualified_call" -> edges.add(caller to ((node["ext"] as String) to (node["name"] as String)))
            }
            for (v in node.values) walkCallTargets(v, owner, edges, caller)
        }
        is List<*> -> for (it in node) walkCallTargets(it, owner, edges, caller)
    }
}
