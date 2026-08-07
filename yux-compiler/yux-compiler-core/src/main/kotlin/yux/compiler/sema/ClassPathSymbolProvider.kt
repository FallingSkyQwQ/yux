package yux.compiler.sema

import yux.compiler.ast.SourceSpan

/**
 * JVM 类符号（02-§6.2 的 `ClassPathSymbolProvider` 产物）。
 * 由反射加载，仅提取语义检查所需的元信息：构造器 / 方法 / 静态方法 / 字段 / JavaBean 访问器。
 */
class JvmClassSymbol(
    val qualifiedName: String,
    val simpleName: String,
    override val isInterface: Boolean,
    override val typeParams: List<String>,
    override val isData: Boolean = false,
    override val isService: Boolean = false,
    var methods: List<JvmMethodSymbol>,
    var staticMethods: List<JvmMethodSymbol>,
    var fields: List<JvmFieldSymbol>,
    var constructors: List<JvmConstructorSymbol>,
) : Symbol, TypeSymbol {    override val name: String get() = simpleName
    override val isYuxDeclared: Boolean = false
    override val span: SourceSpan? = null
    override val kind: SymbolKind get() = SymbolKind.TYPE

    fun allMethods(): List<JvmMethodSymbol> = methods + staticMethods

    fun methodNamed(name: String): JvmMethodSymbol? = allMethods().firstOrNull { it.name == name }
    fun staticMethodNamed(name: String): JvmMethodSymbol? = staticMethods.firstOrNull { it.name == name }

    /** JavaBean 属性类型（getX/isX → x，S-8.5.2）。 */
    fun propertyType(property: String): SemaType? {
        val cap = property.replaceFirstChar { it.uppercaseChar() }
        methods.firstOrNull { it.name == "get$cap" && it.params.isEmpty() }?.let { return it.returnType }
        methods.firstOrNull { it.name == "is$cap" && it.params.isEmpty() && it.returnType == SemaType.BOOLEAN }
            ?.let { return it.returnType }
        fields.firstOrNull { it.name == property }?.let { return it.type }
        return null
    }

    fun propertySettable(property: String): Boolean {
        val cap = property.replaceFirstChar { it.uppercaseChar() }
        return methods.any { it.name == "set$cap" && it.params.size == 1 } ||
            fields.any { it.name == property && !it.isFinal }
    }

    /** 可迭代类型判定（S-6.4.1：实现 Iterable 或为数组）。 */
    fun isIterable(): Boolean = qualifiedName == "java.lang.Iterable" || isAssignableFrom("java.lang.Iterable")

    private fun isAssignableFrom(superType: String): Boolean = try {
        Class.forName(qualifiedName, false, JvmClassSymbol::class.java.classLoader)
            .let { superClass -> Class.forName(superType, false, JvmClassSymbol::class.java.classLoader).isAssignableFrom(superClass) }
    } catch (_: ClassNotFoundException) {
        false
    }

    override fun toString(): String = "JvmClassSymbol($qualifiedName)"
}

/** JVM 方法（含静态标志与参数/返回类型）。 */
class JvmMethodSymbol(
    val name: String,
    val params: List<SemaType>,
    val returnType: SemaType,
    val isStatic: Boolean,
)

/** JVM 字段。 */
class JvmFieldSymbol(
    val name: String,
    val type: SemaType,
    val isStatic: Boolean,
    val isFinal: Boolean,
)

/** JVM 构造器。 */
class JvmConstructorSymbol(
    val params: List<SemaType>,
)

/**
 * 类路径符号提供器（02-§6.2）：对未在 Yux 源码声明的类型名，懒加载 JVM 类元信息，
 * 结果缓存于 [cache]。JVM 引用类型一律视为可空（S-8.1 与 Java 互操作规则）；
 * JVM 基本类型（`int/long/...`）为非空。
 *
 * 实现要点：先构造**空成员符号**并写入 [cache]，再填充成员——自引用方法/返回类型
 * 递归解析时命中同一符号实例（`===` 相等，类型一致性），无需事后修复。
 */
class ClassPathSymbolProvider {
    private val cache = mutableMapOf<String, JvmClassSymbol?>()

    /** 按完整限定名解析；失败返回 null。 */
    fun resolve(qualifiedName: String): JvmClassSymbol? {
        cache[qualifiedName]?.let { return it }
        val cls = forName(qualifiedName) ?: return null.also { cache[qualifiedName] = null }
        return resolveClass(cls)
    }

    /** 尝试在给定包名/简单名下解析。 */
    fun resolveIn(packageName: String, simpleName: String): JvmClassSymbol? =
        resolve("$packageName.$simpleName")

    /** 类 → 符号：缓存空符号后原地填充，保证自引用解析到同一实例。 */
    private fun resolveClass(cls: Class<*>): JvmClassSymbol {
        cache[cls.name]?.let { return it }
        val symbol = emptySymbol(cls)
        cache[cls.name] = symbol
        fill(symbol, cls)
        return symbol
    }

    private fun emptySymbol(cls: Class<*>): JvmClassSymbol = JvmClassSymbol(
        qualifiedName = cls.name,
        simpleName = cls.simpleName,
        isInterface = cls.isInterface,
        typeParams = cls.typeParameters.map { it.name },
        methods = emptyList(),
        staticMethods = emptyList(),
        fields = emptyList(),
        constructors = emptyList(),
    )

    private fun fill(symbol: JvmClassSymbol, cls: Class<*>) {
        val methods = mutableListOf<JvmMethodSymbol>()
        val staticMethods = mutableListOf<JvmMethodSymbol>()
        for (m in cls.methods) {
            if (m.isSynthetic) continue
            val params = m.parameterTypes.map { jvmType(it) }
            val ret = jvmType(m.returnType)
            val sym = JvmMethodSymbol(m.name, params, ret, java.lang.reflect.Modifier.isStatic(m.modifiers))
            if (sym.isStatic) staticMethods += sym else methods += sym
        }
        val fields = cls.fields.map { f ->
            JvmFieldSymbol(
                name = f.name,
                type = jvmType(f.type),
                isStatic = java.lang.reflect.Modifier.isStatic(f.modifiers),
                isFinal = java.lang.reflect.Modifier.isFinal(f.modifiers),
            )
        }
        val constructors = cls.constructors.map { JvmConstructorSymbol(it.parameterTypes.map { t -> jvmType(t) }) }
        symbol.methods = methods
        symbol.staticMethods = staticMethods
        symbol.fields = fields
        symbol.constructors = constructors
    }

    private fun forName(name: String): Class<*>? = try {
        Class.forName(name, false, ClassPathSymbolProvider::class.java.classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: NoClassDefFoundError) {
        null
    }

    /** JVM 类型 → [SemaType]。基本类型非空；引用类型可空（S-8.1）。 */
    private fun jvmType(cls: Class<*>): SemaType = when (cls.name) {
        "int" -> SemaType.INT
        "long" -> SemaType.LONG
        "float" -> SemaType.FLOAT
        "double" -> SemaType.DOUBLE
        "boolean" -> SemaType.BOOLEAN
        "char" -> SemaType.CHAR
        "byte" -> SemaType.BYTE
        "void" -> SemaType.UnitT
        "java.lang.String" -> SemaType.basic("String", nullable = true)
        "java.lang.Integer" -> SemaType.basic("Int", nullable = true)
        "java.lang.Long" -> SemaType.basic("Long", nullable = true)
        "java.lang.Float" -> SemaType.basic("Float", nullable = true)
        "java.lang.Double" -> SemaType.basic("Double", nullable = true)
        "java.lang.Boolean" -> SemaType.basic("Boolean", nullable = true)
        "java.lang.Character" -> SemaType.basic("Char", nullable = true)
        "java.lang.Byte" -> SemaType.basic("Byte", nullable = true)
        "java.lang.Object" -> SemaType.basic("Any", nullable = true)
        "java.lang.Throwable" -> SemaType.basic("Throwable", nullable = true)
        "java.lang.Exception" -> SemaType.basic("Exception", nullable = true)
        "java.lang.Iterable" -> SemaType.basic("Iterable", nullable = true)
        else -> {
            val symbol = resolveClass(cls)
            SemaType.Declared(symbol, emptyList(), nullable = true)
        }
    }
}
