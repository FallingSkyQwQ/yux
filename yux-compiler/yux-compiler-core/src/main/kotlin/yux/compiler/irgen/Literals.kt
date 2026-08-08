package yux.compiler.irgen

/**
 * 字面量解码（01-§2.3/§2.5；词法器已验证合法转义）。
 *
 * ExprGen 常量生成与 IRGen 注解实参求值共用（T-M5-6）。
 */
internal object Literals {

    fun decodeInt(text: String): Any {
        val clean = text.replace("_", "")
        val long = clean.endsWith("L") || clean.endsWith("l")
        val digits = if (long) clean.dropLast(1) else clean
        val value = when {
            digits.startsWith("0x") || digits.startsWith("0X") -> digits.substring(2).toLong(16)
            digits.startsWith("0b") || digits.startsWith("0B") -> digits.substring(2).toLong(2)
            else -> digits.toLong()
        }
        return if (long) value else value.toInt()
    }

    fun decodeFloat(text: String): Any {
        val clean = text.replace("_", "")
        return when (clean.lastOrNull()) {
            'f', 'F' -> clean.dropLast(1).toFloat()
            'd', 'D' -> clean.dropLast(1).toDouble()
            else -> clean.toDouble()
        }
    }

    fun decodeChar(text: String): Char {
        val inner = text.substring(1, text.length - 1)
        return decodeEscapes(inner).single()
    }

    fun decodeString(text: String): String =
        decodeEscapes(text.substring(1, text.length - 1))

    fun decodeEscapes(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                append(c)
                i++
                continue
            }
            val next = s[i + 1]
            when (next) {
                'n' -> { append('\n'); i += 2 }
                't' -> { append('\t'); i += 2 }
                'r' -> { append('\r'); i += 2 }
                'b' -> { append('\b'); i += 2 }
                'f' -> { append('\u000C'); i += 2 }
                '\\' -> { append('\\'); i += 2 }
                '\'' -> { append('\''); i += 2 }
                '"' -> { append('"'); i += 2 }
                '$' -> { append('$'); i += 2 }
                '0' -> { append('\u0000'); i += 2 }
                'u' -> {
                    if (i + 5 < s.length) {
                        append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                    } else {
                        // 截断的 \uXXXX：退化为字面 `u`，前进越过 `\u` 防重复输出
                        append('u')
                        i += 2
                    }
                }
                else -> { append('\\'); i++ }
            }
        }
    }
}
