package yux.json

/** JSON 递归下降解析器（零依赖）：对象 / 数组 / 字符串 / 数字 / 布尔 / null。 */
internal class JsonParser(private val text: String) {
    private var pos = 0

    fun parse(): Any? {
        val value = parseValue()
        skipWs()
        if (pos != text.length) throw IllegalArgumentException("JSON 解析失败: 位置 $pos 存在多余内容")
        return value
    }

    private fun parseValue(): Any? = when (peek()) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> parseString()
        't' -> literal("true", true)
        'f' -> literal("false", false)
        'n' -> literal("null", null)
        '-', in '0'..'9' -> parseNumber()
        else -> throw IllegalArgumentException("JSON 解析失败: 位置 $pos 意外字符 '${peek()}'")
    }

    private fun parseObject(): LinkedHashMap<String, Any?> {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos++
            return map
        }
        while (true) {
            skipWs()
            if (peek() != '"') throw IllegalArgumentException("JSON 解析失败: 位置 $pos 期望对象键")
            val key = parseString()
            skipWs()
            expect(':')
            skipWs()
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }
                else -> throw IllegalArgumentException("JSON 解析失败: 位置 $pos 期望 ',' 或 '}'")
            }
        }
    }

    private fun parseArray(): ArrayList<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') {
            pos++
            return list
        }
        while (true) {
            skipWs()
            list.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> throw IllegalArgumentException("JSON 解析失败: 位置 $pos 期望 ',' 或 ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = text[pos]
            pos++
            when {
                c == '"' -> return out.toString()
                c == '\\' -> out.append(escape())
                c < ' ' -> throw IllegalArgumentException("JSON 解析失败: 字符串含未转义控制字符")
                else -> out.append(c)
            }
        }
    }

    private fun escape(): Char {
        val c = text[pos]
        pos++
        return when (c) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = text.substring(pos, pos + 4)
                pos += 4
                hex.toInt(16).toChar()
            }
            else -> throw IllegalArgumentException("JSON 解析失败: 非法转义 '\\$c'")
        }
    }

    private fun parseNumber(): Any {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' ||
                text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')
        ) {
            pos++
        }
        val raw = text.substring(start, pos)
        return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
            raw.toDouble()
        } else {
            raw.toLongOrNull() ?: raw.toDouble()
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        if (text.startsWith(word, pos)) {
            pos += word.length
            return value
        }
        throw IllegalArgumentException("JSON 解析失败: 位置 $pos 期望 '$word'")
    }

    private fun peek(): Char {
        skipWs()
        return if (pos < text.length) text[pos] else throw IllegalArgumentException("JSON 解析失败: 意外结尾")
    }

    private fun skipWs() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) {
            pos++
        }
    }

    private fun expect(c: Char) {
        if (pos >= text.length || text[pos] != c) {
            throw IllegalArgumentException("JSON 解析失败: 位置 $pos 期望 '$c'")
        }
        pos++
    }
}

