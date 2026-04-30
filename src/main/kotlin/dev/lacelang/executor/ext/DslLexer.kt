package dev.lacelang.executor.ext

/**
 * Lexer for the .laceext rule body DSL (lace-extensions.md §5.1).
 *
 * Python-style indentation-sensitive. Emits INDENT / DEDENT tokens at
 * block boundaries. Comments start with `#` and run to end-of-line.
 */

enum class TokenType {
    IDENT, STRING, INT, FLOAT, BINDING,
    NEWLINE, INDENT, DEDENT,
    LPAREN, RPAREN, LBRACK, RBRACK, LBRACE, RBRACE,
    COMMA, COLON, DOT, QDOT, QBRACK, ARROW,
    EQ,
    PLUS, MINUS, STAR, SLASH,
    QUESTION,
    // keywords
    KW_FOR, KW_IN, KW_WHEN, KW_LET, KW_SET, KW_EMIT,
    KW_EXIT, KW_RETURN,
    KW_AND, KW_OR, KW_NOT,
    KW_TRUE, KW_FALSE, KW_NULL,
    KW_RESULT, KW_PREV, KW_THIS, KW_CONFIG, KW_REQUIRE,
    // comparison operator keywords
    KW_EQ, KW_NEQ, KW_LT, KW_LTE, KW_GT, KW_GTE,
    EOF
}

private val KEYWORDS: Map<String, TokenType> = mapOf(
    "for" to TokenType.KW_FOR,
    "in" to TokenType.KW_IN,
    "when" to TokenType.KW_WHEN,
    "let" to TokenType.KW_LET,
    "set" to TokenType.KW_SET,
    "emit" to TokenType.KW_EMIT,
    "exit" to TokenType.KW_EXIT,
    "return" to TokenType.KW_RETURN,
    "and" to TokenType.KW_AND,
    "or" to TokenType.KW_OR,
    "not" to TokenType.KW_NOT,
    "true" to TokenType.KW_TRUE,
    "false" to TokenType.KW_FALSE,
    "null" to TokenType.KW_NULL,
    "result" to TokenType.KW_RESULT,
    "prev" to TokenType.KW_PREV,
    "this" to TokenType.KW_THIS,
    "config" to TokenType.KW_CONFIG,
    "require" to TokenType.KW_REQUIRE,
    "eq" to TokenType.KW_EQ,
    "neq" to TokenType.KW_NEQ,
    "lt" to TokenType.KW_LT,
    "lte" to TokenType.KW_LTE,
    "gt" to TokenType.KW_GT,
    "gte" to TokenType.KW_GTE,
)

data class Token(val type: TokenType, val value: String, val line: Int, val col: Int)

class DslLexError(msg: String, val line: Int, val col: Int) : RuntimeException("$msg (line $line, col $col)")

fun tokenize(source: String): List<Token> = DslLexer(source).tokenize()

private class DslLexer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val indentStack = mutableListOf(0)
    private var parenDepth = 0
    private val tokens = mutableListOf<Token>()
    private var atLineStart = true

    private fun peek(off: Int = 0): Char {
        val p = pos + off
        return if (p < src.length) src[p] else '\u0000'
    }

    private fun advance(n: Int = 1): String {
        val chunk = src.substring(pos, minOf(pos + n, src.length))
        for (ch in chunk) {
            if (ch == '\n') { line++; col = 1 } else col++
        }
        pos += n
        return chunk
    }

    private fun handleLineStart() {
        while (true) {
            var indent = 0
            var scan = pos
            while (scan < src.length && (src[scan] == ' ' || src[scan] == '\t')) {
                indent++; scan++
            }
            if (scan >= src.length) return
            val ch = src[scan]
            if (ch == '\n') { advance(scan - pos + 1); continue }
            if (ch == '#') {
                while (scan < src.length && src[scan] != '\n') scan++
                advance(scan - pos)
                if (pos < src.length && peek() == '\n') advance()
                continue
            }
            break
        }
        val indent = run {
            var n = 0; var s = pos
            while (s < src.length && (src[s] == ' ' || src[s] == '\t')) { n++; s++ }
            n
        }
        advance(indent)
        val curIndent = indentStack.last()
        if (indent > curIndent) {
            indentStack.add(indent)
            tokens.add(Token(TokenType.INDENT, "", line, 1))
        } else if (indent < curIndent) {
            while (indentStack.isNotEmpty() && indentStack.last() > indent) {
                indentStack.removeAt(indentStack.lastIndex)
                tokens.add(Token(TokenType.DEDENT, "", line, 1))
            }
            if (indentStack.last() != indent) {
                throw DslLexError("inconsistent indentation", line, 1)
            }
        }
        atLineStart = false
    }

    private fun lexIdent(): Token {
        val startLine = line; val startCol = col
        val start = pos
        while (pos < src.length && (peek().isLetterOrDigit() || peek() == '_')) advance()
        val text = src.substring(start, pos)
        val kw = KEYWORDS[text]
        return if (kw != null) Token(kw, text, startLine, startCol) else Token(TokenType.IDENT, text, startLine, startCol)
    }

    private fun lexBinding(): Token {
        val startLine = line; val startCol = col
        advance() // $
        if (!(peek().isLetter() || peek() == '_')) {
            return Token(TokenType.BINDING, "$", startLine, startCol)
        }
        val start = pos
        while (pos < src.length && (peek().isLetterOrDigit() || peek() == '_')) advance()
        return Token(TokenType.BINDING, src.substring(start, pos), startLine, startCol)
    }

    private fun lexNumber(): Token {
        val startLine = line; val startCol = col
        val start = pos
        while (pos < src.length && peek().isDigit()) advance()
        if (peek() == '.' && (pos + 1 < src.length && src[pos + 1].isDigit())) {
            advance() // .
            while (pos < src.length && peek().isDigit()) advance()
            return Token(TokenType.FLOAT, src.substring(start, pos), startLine, startCol)
        }
        return Token(TokenType.INT, src.substring(start, pos), startLine, startCol)
    }

    private fun lexString(): Token {
        val startLine = line; val startCol = col
        val quote = peek()
        advance()
        val chars = StringBuilder()
        while (pos < src.length) {
            val ch = peek()
            if (ch == quote) { advance(); return Token(TokenType.STRING, chars.toString(), startLine, startCol) }
            if (ch == '\\') {
                val nxt = peek(1)
                val mapping = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', '\\' to '\\', '"' to '"', '\'' to '\'')
                if (nxt in mapping) { chars.append(mapping[nxt]); advance(2); continue }
                throw DslLexError("invalid escape \\$nxt", line, col)
            }
            if (ch == '\n') throw DslLexError("unterminated string literal", startLine, startCol)
            chars.append(ch)
            advance()
        }
        throw DslLexError("unterminated string literal", startLine, startCol)
    }

    fun tokenize(): List<Token> {
        while (pos < src.length) {
            if (atLineStart && parenDepth == 0) {
                handleLineStart()
                if (pos >= src.length) break
            }
            val ch = peek()
            if (ch == '\r') { advance(); continue } // skip CR, \n handles newline
            if (ch == '\n') {
                advance()
                if (parenDepth == 0) {
                    if (tokens.isEmpty() || tokens.last().type != TokenType.NEWLINE) {
                        tokens.add(Token(TokenType.NEWLINE, "", line, col))
                    }
                    atLineStart = true
                }
                continue
            }
            if (ch == ' ' || ch == '\t') { advance(); continue }
            if (ch == '#') { while (pos < src.length && peek() != '\n') advance(); continue }
            if (ch == '$') { tokens.add(lexBinding()); continue }
            if (ch.isLetter() || ch == '_') { tokens.add(lexIdent()); continue }
            if (ch.isDigit()) { tokens.add(lexNumber()); continue }
            if (ch == '"' || ch == '\'') { tokens.add(lexString()); continue }

            // 2-char punct
            val startLine = line; val startCol = col
            if (pos + 1 < src.length) {
                val two = src.substring(pos, pos + 2)
                when (two) {
                    "<-" -> { advance(2); tokens.add(Token(TokenType.ARROW, "<-", startLine, startCol)); continue }
                    "?." -> { advance(2); tokens.add(Token(TokenType.QDOT, "?.", startLine, startCol)); continue }
                    "[?" -> { advance(2); parenDepth++; tokens.add(Token(TokenType.QBRACK, "[?", startLine, startCol)); continue }
                }
            }

            // single-char punct
            val single = mapOf(
                '(' to TokenType.LPAREN, ')' to TokenType.RPAREN,
                '[' to TokenType.LBRACK, ']' to TokenType.RBRACK,
                '{' to TokenType.LBRACE, '}' to TokenType.RBRACE,
                ',' to TokenType.COMMA, ':' to TokenType.COLON, '.' to TokenType.DOT,
                '+' to TokenType.PLUS, '-' to TokenType.MINUS,
                '*' to TokenType.STAR, '/' to TokenType.SLASH,
                '?' to TokenType.QUESTION,
                '=' to TokenType.EQ,
            )
            val tt = single[ch]
            if (tt != null) {
                if (ch in "([{") parenDepth++
                else if (ch in ")]}") parenDepth--
                advance()
                tokens.add(Token(tt, ch.toString(), startLine, startCol))
                continue
            }
            throw DslLexError("unexpected character '$ch'", line, col)
        }
        // Final newline + dedents
        if (tokens.isNotEmpty() && tokens.last().type != TokenType.NEWLINE) {
            tokens.add(Token(TokenType.NEWLINE, "", line, col))
        }
        while (indentStack.size > 1) {
            indentStack.removeAt(indentStack.lastIndex)
            tokens.add(Token(TokenType.DEDENT, "", line, col))
        }
        tokens.add(Token(TokenType.EOF, "", line, col))
        return tokens
    }
}
