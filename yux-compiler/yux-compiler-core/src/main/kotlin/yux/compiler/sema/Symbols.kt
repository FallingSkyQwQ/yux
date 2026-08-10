package yux.compiler.sema

import yux.compiler.ast.SourceSpan

/** 符号类别（02-§6.1）。 */
enum class SymbolKind {
    TYPE,
    CLASS,
    SERVICE,
    FUNCTION,
    VARIABLE,
    PARAMETER,
    PROPERTY,
}

/** 符号表条目（02-§6.1）。 */
interface Symbol {
    val name: String
    val span: SourceSpan?
    val kind: SymbolKind
}

/** 局部变量 / 顶层变量。 */
class VariableSymbol(
    override val name: String,
    var type: SemaType?,
    /** 是否只读（参数、`val` 语义属性、常量）。 */
    val isVal: Boolean,
    override val span: SourceSpan?,
) : Symbol {
    override val kind: SymbolKind get() = SymbolKind.VARIABLE
    /** 是否已被确定性赋值（S-6.1.2）。 */
    var definitelyAssigned: Boolean = false
    /** 是否在函数体内被作为赋值目标（影响智能转型，02-§7.3）。 */
    var reassigned: Boolean = false
}

/** 函数参数。 */
class ParameterSymbol(
    override val name: String,
    val type: SemaType,
    val hasDefault: Boolean,
    override val span: SourceSpan?,
) : Symbol {
    override val kind: SymbolKind get() = SymbolKind.PARAMETER
}

/** 函数（顶级或类成员）。 */
class FunctionSymbol(
    override val name: String,
    val params: List<ParameterSymbol>,
    /** 返回类型；null 时在 TypeChecker 中推断（S-5.5.2）。 */
    var returnType: SemaType?,
    val isAsync: Boolean,
    val isOverride: Boolean,
    /** null = 顶级函数（文件静态方法，S-5.5.3）。 */
    val owner: YxClassSymbol?,
    /** 扩展函数接收者类型（M9），null = 普通函数。 */
    val receiverType: SemaType? = null,
    /** 访问控制（S-5.1.4）：默认 public。 */
    val visibility: yux.compiler.ast.YxVisibility = yux.compiler.ast.YxVisibility.PUBLIC,
    override val span: SourceSpan?,
    /** 对应 AST 声明（函数体检查用）。 */
    val decl: yux.compiler.ast.YxFunction? = null,
) : Symbol {
    override val kind: SymbolKind get() = SymbolKind.FUNCTION
}

/** 类属性（S-5.2）。顶层属性 [owner] 为 null。 */
class PropertySymbol(
    override val name: String,
    var type: SemaType?,
    /** 是否只读（无 setter，S-5.2.2）。 */
    val isVal: Boolean,
    val owner: YxClassSymbol?,
    /** 访问控制（S-5.1.4）：默认 public。 */
    val visibility: yux.compiler.ast.YxVisibility = yux.compiler.ast.YxVisibility.PUBLIC,
    override val span: SourceSpan?,
    /** 对应 AST 声明（初始值/访问器检查用）。 */
    val decl: yux.compiler.ast.YxProperty? = null,
) : Symbol {
    override val kind: SymbolKind get() = SymbolKind.PROPERTY
}

/** 用户声明的类 / data / service 符号（实现 [TypeSymbol]）。 */
class YxClassSymbol(
    override val name: String,
    override val typeParams: List<String>,
    override val isData: Boolean,
    override val isService: Boolean,
    /** 单继承父类型（S-8.7.1）。 */
    var superType: SemaType?,
    var interfaces: List<SemaType>,
    val members: MutableList<Symbol>,
    val isAbstract: Boolean,
    val annotations: List<yux.compiler.ast.YxAnnotation>,
    /** 访问控制（S-5.1.4）：默认 public。 */
    val visibility: yux.compiler.ast.YxVisibility = yux.compiler.ast.YxVisibility.PUBLIC,
    /** 所属文件（用于定位注入图）。 */
    val fileScope: FileScope?,
    override val span: SourceSpan?,
) : Symbol, TypeSymbol {
    override val kind: SymbolKind get() = if (isService) SymbolKind.SERVICE else SymbolKind.CLASS
    override val isInterface: Boolean = false
    override val isYuxDeclared: Boolean = true

    fun properties(): List<PropertySymbol> = members.filterIsInstance<PropertySymbol>()
    fun functions(): List<FunctionSymbol> = members.filterIsInstance<FunctionSymbol>()
    fun property(name: String): PropertySymbol? = members.filterIsInstance<PropertySymbol>().firstOrNull { it.name == name }
    fun functionsNamed(name: String): List<FunctionSymbol> = members.filterIsInstance<FunctionSymbol>().filter { it.name == name }

    /**
     * 沿父类链 + 接口（传递闭包）查找函数（含自身，S-8.7.1 成员继承 / S-8.7 接口实现）。
     * 顺序：自身 → 父类链 → 接口；同名同元数按 sameSignature（元数）去重，自身/父类优先，
     * 菱形接口继承不产生重复候选。
     */
    fun functionsIncludingSuper(name: String): List<FunctionSymbol> {
        val result = mutableListOf<FunctionSymbol>()
        val seenArity = mutableSetOf<Int>()
        fun add(fns: List<FunctionSymbol>) {
            for (fn in fns) {
                if (seenArity.add(fn.params.size)) result += fn
            }
        }
        var cls: YxClassSymbol? = this
        while (cls != null) {
            add(cls.functionsNamed(name))
            cls = cls.superClassSymbol()
        }
        for (iface in interfaceSymbols()) {
            add(iface.functionsNamed(name))
        }
        return result
    }

    /** 沿父类链 + 接口（传递闭包）查找属性（含自身）；自身/父类优先。 */
    fun propertyIncludingSuper(name: String): PropertySymbol? {
        var cls: YxClassSymbol? = this
        while (cls != null) {
            cls.property(name)?.let { return it }
            cls = cls.superClassSymbol()
        }
        for (iface in interfaceSymbols()) {
            iface.property(name)?.let { return it }
        }
        return null
    }

    private fun superClassSymbol(): YxClassSymbol? =
        (superType as? SemaType.Declared)?.symbol as? YxClassSymbol

    /**
     * 实现的接口符号（传递闭包，按符号去重）。
     * 仅展开 Yux 声明类（YxClassSymbol）——JVM 接口成员不经反射参与 Yux 成员查找，
     * 如 `implements org.bukkit.event.Listener` 的处理器类不受影响。
     */
    fun interfaceSymbols(): List<YxClassSymbol> {
        val result = mutableListOf<YxClassSymbol>()
        val seen = mutableSetOf<YxClassSymbol>()
        fun visit(cls: YxClassSymbol) {
            for (t in cls.interfaces) {
                val iface = (t as? SemaType.Declared)?.symbol as? YxClassSymbol ?: continue
                if (seen.add(iface)) {
                    result += iface
                    visit(iface)
                }
            }
        }
        visit(this)
        return result
    }
}

/** 文件级作用域：包名 + 导入 + 本文件顶层声明。 */
class FileScope(
    val path: String,
    val packageName: String,
) {
    val types = mutableMapOf<String, TypeSymbol>()
    val topLevelFunctions = mutableMapOf<String, MutableList<FunctionSymbol>>()
    val topLevelProperties = mutableMapOf<String, PropertySymbol>()
    val imports = mutableListOf<yux.compiler.ast.YxImport>()
    /** 本文件原始顶层声明（供 Declarations 两遍式处理）。 */
    val decls = mutableListOf<yux.compiler.ast.YxDecl>()
    /** 扩展函数表（M9）：接收者类型名 → 扩展函数，供成员调用查找。 */
    val extensionFunctions = mutableMapOf<String, MutableList<FunctionSymbol>>()
}
