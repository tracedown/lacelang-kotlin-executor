package dev.lacelang.executor.ext

/**
 * Parser for the .laceext rule body DSL (lace-extensions.md §5.1).
 *
 * Produces a tree of typed Map nodes. Precedence (lowest to highest):
 *   ternary (? :) < or < and < eq/neq < lt/lte/gt/gte < add/sub < mul/div
 *   < unary (not, -) < access (. ?. [] [?]) < primary
 */

class DslParseError(msg: String, val line: Int) : RuntimeException("line $line: $msg")

fun parseRuleBody(src: String): List<Map<String, Any?>> {
    val expanded = expandInlineWhens(src)
    val tokens = tokenize(expanded)
    return DslParser(tokens, inFunction = false).parseBody()
}

fun parseFunctionBody(src: String): List<Map<String, Any?>> {
    val expanded = expandInlineWhens(src)
    val tokens = tokenize(expanded)
    return DslParser(tokens, inFunction = true).parseBody()
}

private class DslParser(private val toks: List<Token>, private val inFunction: Boolean) {
    private var pos = 0

    private val tok: Token get() = toks[pos]
    private fun peek(off: Int = 0): Token = toks[pos + off]
    private fun advance(): Token { val t = toks[pos]; if (pos < toks.lastIndex) pos++; return t }
    private fun check(vararg types: TokenType): Boolean = tok.type in types
    private fun match(vararg types: TokenType): Token? = if (tok.type in types) advance() else null
    private fun expect(ttype: TokenType): Token {
        if (tok.type != ttype) throw DslParseError("expected $ttype, got ${tok.type} ('${tok.value}')", tok.line)
        return advance()
    }

    fun parseBody(): List<Map<String, Any?>> {
        val stmts = mutableListOf<Map<String, Any?>>()
        while (!check(TokenType.EOF, TokenType.DEDENT)) {
            if (check(TokenType.NEWLINE)) { advance(); continue }
            stmts.add(parseStatement())
        }
        return stmts
    }

    private fun parseStatement(): Map<String, Any?> {
        val t = tok
        return when (t.type) {
            TokenType.KW_FOR -> parseFor()
            TokenType.KW_WHEN -> parseWhen()
            TokenType.KW_LET -> parseLet()
            TokenType.KW_SET -> parseSet()
            TokenType.KW_EMIT -> parseEmit()
            TokenType.KW_EXIT -> { advance(); expectStmtEnd(); mapOf("kind" to "exit", "line" to t.line) }
            TokenType.KW_RETURN -> {
                if (!inFunction) throw DslParseError("return is only valid in function bodies", t.line)
                advance(); val e = parseExpr(); expectStmtEnd()
                mapOf("kind" to "return", "expr" to e, "line" to t.line)
            }
            TokenType.IDENT -> {
                val call = parseFuncCallExpr(); expectStmtEnd()
                mapOf("kind" to "call_stmt", "call" to call, "line" to t.line)
            }
            else -> throw DslParseError("unexpected token at statement start: ${t.type} '${t.value}'", t.line)
        }
    }

    private fun expectStmtEnd() {
        if (check(TokenType.NEWLINE)) { advance(); return }
        if (check(TokenType.EOF, TokenType.DEDENT)) return
        throw DslParseError("expected end of statement, got ${tok.type}", tok.line)
    }

    // ── for / when / let / set / emit ─────────────────────────────

    private fun parseFor(): Map<String, Any?> {
        val start = advance() // for
        val binding = expect(TokenType.BINDING).value
        expect(TokenType.KW_IN)
        val iterExpr = parseExpr()
        expect(TokenType.COLON)
        expect(TokenType.NEWLINE)
        expect(TokenType.INDENT)
        val body = parseBody()
        expect(TokenType.DEDENT)
        return mapOf("kind" to "for", "binding" to binding, "iter" to iterExpr, "body" to body, "line" to start.line)
    }

    private fun parseWhen(): Map<String, Any?> {
        val start = advance() // when
        val cond = parseExpr()
        if (match(TokenType.COLON) != null) {
            expect(TokenType.NEWLINE)
            expect(TokenType.INDENT)
            val body = parseBody()
            expect(TokenType.DEDENT)
            return mapOf("kind" to "when_block", "cond" to cond, "body" to body, "line" to start.line)
        }
        expectStmtEnd()
        return mapOf("kind" to "when_inline", "cond" to cond, "line" to start.line)
    }

    private fun parseLet(): Map<String, Any?> {
        val start = advance() // let
        val name = expect(TokenType.BINDING).value
        expect(TokenType.EQ)
        val expr = parseExpr()
        expectStmtEnd()
        return mapOf("kind" to "let", "name" to name, "expr" to expr, "line" to start.line)
    }

    private fun parseSet(): Map<String, Any?> {
        val start = advance() // set
        if (!inFunction) throw DslParseError("set is only valid in function bodies; rule bindings are immutable", start.line)
        val name = expect(TokenType.BINDING).value
        expect(TokenType.EQ)
        val expr = parseExpr()
        expectStmtEnd()
        return mapOf("kind" to "set", "name" to name, "expr" to expr, "line" to start.line)
    }

    private fun parseEmit(): Map<String, Any?> {
        val start = advance() // emit
        expect(TokenType.KW_RESULT)
        val path = mutableListOf("result")
        while (match(TokenType.DOT) != null) path.add(expect(TokenType.IDENT).value)
        expect(TokenType.ARROW)
        expect(TokenType.LBRACE)
        val fields = mutableListOf<Map<String, Any?>>()
        if (!check(TokenType.RBRACE)) {
            while (true) {
                val keyTok = tok
                val key = when (keyTok.type) {
                    TokenType.STRING -> { advance(); keyTok.value }
                    TokenType.IDENT -> { advance(); keyTok.value }
                    else -> throw DslParseError("expected field key, got ${keyTok.type}", keyTok.line)
                }
                expect(TokenType.COLON)
                val value = parseExpr()
                fields.add(mapOf("key" to key, "value" to value))
                if (match(TokenType.COMMA) == null) break
                if (check(TokenType.RBRACE)) break
            }
        }
        expect(TokenType.RBRACE)
        expectStmtEnd()
        return mapOf("kind" to "emit", "target" to path, "fields" to fields, "line" to start.line)
    }

    // ── expressions (precedence climb) ────────────────────────────

    private fun parseExpr(): Map<String, Any?> {
        val cond = parseOr()
        if (match(TokenType.QUESTION) != null) {
            val then = parseExpr()
            expect(TokenType.COLON)
            val else_ = parseExpr()
            return mapOf("kind" to "ternary", "cond" to cond, "then" to then, "else" to else_)
        }
        return cond
    }

    private fun parseOr(): Map<String, Any?> {
        var left = parseAnd()
        while (check(TokenType.KW_OR)) { advance(); val right = parseAnd(); left = mapOf("kind" to "binop", "op" to "or", "left" to left, "right" to right) }
        return left
    }

    private fun parseAnd(): Map<String, Any?> {
        var left = parseEq()
        while (check(TokenType.KW_AND)) { advance(); val right = parseEq(); left = mapOf("kind" to "binop", "op" to "and", "left" to left, "right" to right) }
        return left
    }

    private fun parseEq(): Map<String, Any?> {
        var left = parseOrd()
        if (check(TokenType.KW_EQ, TokenType.KW_NEQ)) {
            val opTok = advance()
            val right = parseOrd()
            left = mapOf("kind" to "binop", "op" to opTok.value, "left" to left, "right" to right)
            if (check(TokenType.KW_EQ, TokenType.KW_NEQ))
                throw DslParseError("chained comparison: comparisons do not associate; use 'and'/'or' with parentheses to combine", tok.line)
        }
        return left
    }

    private fun parseOrd(): Map<String, Any?> {
        var left = parseAddSub()
        if (check(TokenType.KW_LT, TokenType.KW_LTE, TokenType.KW_GT, TokenType.KW_GTE)) {
            val opTok = advance()
            val right = parseAddSub()
            left = mapOf("kind" to "binop", "op" to opTok.value, "left" to left, "right" to right)
            if (check(TokenType.KW_LT, TokenType.KW_LTE, TokenType.KW_GT, TokenType.KW_GTE))
                throw DslParseError("chained comparison: comparisons do not associate; use 'and'/'or' with parentheses to combine", tok.line)
        }
        return left
    }

    private fun parseAddSub(): Map<String, Any?> {
        var left = parseMulDiv()
        while (check(TokenType.PLUS, TokenType.MINUS)) {
            val op = advance().value; val right = parseMulDiv()
            left = mapOf("kind" to "binop", "op" to op, "left" to left, "right" to right)
        }
        return left
    }

    private fun parseMulDiv(): Map<String, Any?> {
        var left = parseUnary()
        while (check(TokenType.STAR, TokenType.SLASH)) {
            val op = advance().value; val right = parseUnary()
            left = mapOf("kind" to "binop", "op" to op, "left" to left, "right" to right)
        }
        return left
    }

    private fun parseUnary(): Map<String, Any?> {
        if (check(TokenType.KW_NOT)) { advance(); return mapOf("kind" to "unop", "op" to "not", "operand" to parseUnary()) }
        if (check(TokenType.MINUS)) { advance(); return mapOf("kind" to "unop", "op" to "-", "operand" to parseUnary()) }
        return parseAccess()
    }

    private fun parseAccess(): Map<String, Any?> {
        var base = parsePrimary()
        while (true) {
            if (match(TokenType.DOT) != null || match(TokenType.QDOT) != null) {
                val t = tok
                if (t.type == TokenType.IDENT || t.type.name.startsWith("KW_")) {
                    advance()
                    base = mapOf("kind" to "access_field", "base" to base, "name" to t.value)
                } else throw DslParseError("expected field name, got ${t.type}", t.line)
            } else if (match(TokenType.LBRACK) != null) {
                val idx = parseExpr(); expect(TokenType.RBRACK)
                base = mapOf("kind" to "access_index", "base" to base, "index" to idx)
            } else if (match(TokenType.QBRACK) != null) {
                val cond = parseExpr(); expect(TokenType.RBRACK)
                base = mapOf("kind" to "access_filter", "base" to base, "cond" to cond)
            } else break
        }
        return base
    }

    private fun parsePrimary(): Map<String, Any?> {
        val t = tok
        return when (t.type) {
            TokenType.LPAREN -> { advance(); val e = parseExpr(); expect(TokenType.RPAREN); e }
            TokenType.LBRACE -> parseObjectLit()
            TokenType.STRING -> { advance(); mapOf("kind" to "literal", "valueType" to "string", "value" to t.value) }
            TokenType.INT -> { advance(); mapOf("kind" to "literal", "valueType" to "int", "value" to t.value.toInt()) }
            TokenType.FLOAT -> { advance(); mapOf("kind" to "literal", "valueType" to "float", "value" to t.value.toDouble()) }
            TokenType.KW_TRUE -> { advance(); mapOf("kind" to "literal", "valueType" to "bool", "value" to true) }
            TokenType.KW_FALSE -> { advance(); mapOf("kind" to "literal", "valueType" to "bool", "value" to false) }
            TokenType.KW_NULL -> { advance(); mapOf("kind" to "literal", "valueType" to "null", "value" to null) }
            TokenType.KW_RESULT -> { advance(); mapOf("kind" to "base", "name" to "result") }
            TokenType.KW_PREV -> { advance(); mapOf("kind" to "base", "name" to "prev") }
            TokenType.KW_THIS -> { advance(); mapOf("kind" to "base", "name" to "this") }
            TokenType.KW_CONFIG -> { advance(); mapOf("kind" to "base", "name" to "config") }
            TokenType.KW_REQUIRE -> { advance(); mapOf("kind" to "base", "name" to "require") }
            TokenType.BINDING -> { advance(); mapOf("kind" to "binding", "name" to t.value) }
            TokenType.IDENT -> {
                if (peek(1).type == TokenType.LPAREN) return parseFuncCallExpr()
                if (looksLikeQualifiedCall()) return parseFuncCallExpr()
                advance(); mapOf("kind" to "ident", "name" to t.value)
            }
            else -> throw DslParseError("unexpected token in expression: ${t.type} '${t.value}'", t.line)
        }
    }

    private fun parseFuncCallExpr(): Map<String, Any?> {
        val headTok = expect(TokenType.IDENT)
        val head = headTok.value
        var qualified: String? = null
        if (check(TokenType.DOT) && peek(1).type == TokenType.IDENT && peek(2).type == TokenType.LPAREN) {
            advance() // DOT
            qualified = advance().value // IDENT
        }
        expect(TokenType.LPAREN)
        val args = mutableListOf<Map<String, Any?>>()
        if (!check(TokenType.RPAREN)) {
            while (true) {
                args.add(parseExpr())
                if (match(TokenType.COMMA) == null) break
                if (check(TokenType.RPAREN)) break
            }
        }
        expect(TokenType.RPAREN)
        return if (qualified != null) {
            mapOf("kind" to "qualified_call", "ext" to head, "name" to qualified, "args" to args, "line" to headTok.line)
        } else {
            mapOf("kind" to "call", "name" to head, "args" to args, "line" to headTok.line)
        }
    }

    private fun parseObjectLit(): Map<String, Any?> {
        val start = expect(TokenType.LBRACE)
        val fields = mutableListOf<Map<String, Any?>>()
        if (!check(TokenType.RBRACE)) {
            while (true) {
                val keyTok = tok
                val key = when (keyTok.type) {
                    TokenType.STRING -> { advance(); keyTok.value }
                    TokenType.IDENT -> { advance(); keyTok.value }
                    else -> throw DslParseError("expected object-literal key, got ${keyTok.type}", keyTok.line)
                }
                expect(TokenType.COLON)
                val value = parseExpr()
                fields.add(mapOf("key" to key, "value" to value))
                if (match(TokenType.COMMA) == null) break
                if (check(TokenType.RBRACE)) break
            }
        }
        expect(TokenType.RBRACE)
        return mapOf("kind" to "object_lit", "fields" to fields, "line" to start.line)
    }

    private fun looksLikeQualifiedCall(): Boolean {
        return pos + 3 < toks.size
            && toks[pos].type == TokenType.IDENT
            && toks[pos + 1].type == TokenType.DOT
            && toks[pos + 2].type == TokenType.IDENT
            && toks[pos + 3].type == TokenType.LPAREN
    }
}

// ── inline when expansion ────────────────────────────────────────

internal fun expandInlineWhens(src: String): String {
    val lines = src.split("\n")
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val stripped = line.trimStart()
        if (stripped.startsWith("when ") && !stripped.trimEnd().endsWith(":") && !stripped.startsWith("when:")) {
            val indent = line.substring(0, line.length - stripped.length)
            val bodyLines = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size) {
                val nxt = lines[j]
                if (nxt.trim().isEmpty()) break
                val nxtIndent = nxt.length - nxt.trimStart().length
                if (nxtIndent < indent.length) break
                bodyLines.add(nxt)
                j++
            }
            if (bodyLines.isEmpty()) {
                out.add(line.trimEnd() + ":")
                out.add(indent + "    exit")
                i++
                continue
            }
            out.add(line.trimEnd() + ":")
            val extra = "    "
            val bodySrc = bodyLines.joinToString("\n")
            val expanded = expandInlineWhens(bodySrc)
            for (bl in expanded.split("\n")) {
                out.add(if (bl.trim().isNotEmpty()) indent + extra + bl else bl)
            }
            i = j
            continue
        }
        out.add(line)
        i++
    }
    return out.joinToString("\n")
}
