package yux.core

/**
 * 内置函数运行时（02-§9.1：builtin `print`/`println` 映射到本类的静态方法）。
 *
 * IRGen 将未解析的内置函数调用解析为 `yux.core.CoreLib.<name>` 静态调用（StmtGen.builtinCall），
 * 实参类型 ANY → JVM Object；本类方法签名必须与之一致。
 */
object CoreLib {

    @JvmStatic
    fun print(x: Any?) {
        System.out.print(x)
    }

    @JvmStatic
    fun println(x: Any?) {
        System.out.println(x)
    }
}
