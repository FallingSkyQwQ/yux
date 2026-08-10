package yux.compiler.ir

/**
 * IR 模块层次（T-M4-1 / 02-§8.3）：`IrModule` → `IrClass` → `IrMethod`/`IrField`/`IrProperty`。
 *
 * 属性在 IR 中体现为 [IrProperty]，包含 backing 字段 [backingField] 与
 * getter/setter [IrMethod]（01-§5.2）；顶层函数/属性归入文件类（[IrClass.isFileClass]）。
 */
class IrModule(
    val classes: MutableList<IrClass> = mutableListOf(),
) {
    /** 全部类（含文件类）。 */
    val allClasses: List<IrClass> get() = classes.toList()

    fun classNamed(name: String): IrClass? = classes.firstOrNull { it.name == name }}

/** 类：普通类 / data / service / 文件类（顶层函数与属性的容器，05-§5.3 文件类语义）。 */
class IrClass(
    val name: String,
    val isFileClass: Boolean,
    val isData: Boolean,
    val isService: Boolean,
    /** 类型参数名列表（01-§4.3.3；泛型类如 `data Result T`）。 */
    val typeParams: List<String> = emptyList(),
    /** 单继承父类型（S-8.7.1）。 */
    val superType: IrType?,
    val interfaces: List<IrType>,
    /** JVM 注解（T-M5-6）：IRGen 从 AST 注解 + service 自动 `@YuxService` 填充。 */
    val annotations: List<IrAnnotation> = emptyList(),
    /** 访问控制（S-5.1.4）：默认 public。 */
    val visibility: yux.compiler.ast.YxVisibility = yux.compiler.ast.YxVisibility.PUBLIC,
    val fields: MutableList<IrField> = mutableListOf(),
    val properties: MutableList<IrProperty> = mutableListOf(),
    val methods: MutableList<IrMethod> = mutableListOf(),
) {
    /** 主构造器（`<init>`，Yux 构造实参 = 属性顺序，02-§9.1）。 */
    val constructor: IrMethod? get() = methods.firstOrNull { it.isConstructor }

    fun fieldNamed(name: String): IrField? = fields.firstOrNull { it.name == name }
    fun methodNamed(name: String): IrMethod? = methods.firstOrNull { it.name == name }
}

/** 字段（JVM 私有字段的 IR 表示；含静态字段）。 */
data class IrField(
    val name: String,
    val type: IrType,
    val isStatic: Boolean,
    val isFinal: Boolean,
    /** 归属类 JVM 名（后端 GET/PUTSTATIC 需要；实例字段为 null 时用当前类）。 */
    val owner: String? = null,
    /** 访问控制（S-5.1.4）：默认 public。 */
    val visibility: yux.compiler.ast.YxVisibility = yux.compiler.ast.YxVisibility.PUBLIC,
)

/** 属性（01-§5.2）：backing 字段 + getter/setter 方法（可自定义访问器，亦可只读）。 */
class IrProperty(
    val name: String,
    val type: IrType,
    /** 只读（无 setter，S-5.2.2）。 */
    val isVal: Boolean,
    val isStatic: Boolean,
    val backingField: IrField?,
    val getter: IrMethod?,
    val setter: IrMethod?,
)

/** 方法参数。 */
data class IrParam(
    val name: String,
    val type: IrType,
)

/** JVM 注解（T-M5-6 / 01-§8.2）：[name] 为 JVM 限定名（`yux.core.YuxService`）。 */
data class IrAnnotation(
    val name: String,
    /** 注解实参（常量字面量值；非字面量实参由 IRGen 跳过）。 */
    val args: List<IrAnnotationArg> = emptyList(),
)

/** 注解实参（01-§8.2）：[name] 参数名，[value] 常量值（String/Int/Long/Float/Double/Boolean/Char）。 */
data class IrAnnotationArg(
    val name: String,
    val value: Any?,
)

/**
 * 局部变量（方法作用域）。[index] 为**编译期序号**（0 起：参数优先），
 * 非 JVM 槽位（Long/Double 双槽与实例接收者槽由后端 M5 另行分配）。
 */
class IrLocal(
    val name: String,
    val type: IrType,
    val index: Int,
)

/** 跳转标签（02-§8.2 的 Branch/Goto 目标，配合 [yux.compiler.ir.IrStmt.Label] 定位）。 */
data class IrLabel(
    val name: String,
)

/** 方法（02-§8.3）：含构造器、属性访问器、Lambda 体（[isSynthetic]）。 */
class IrMethod(
    val name: String,
    val params: List<IrParam>,
    /** 返回类型；IRGen 生成后固化（推断函数返回 S-5.5.2）。 */
    val returnType: IrType,
    val isStatic: Boolean,
    val isConstructor: Boolean,
    val isAsync: Boolean,
    val isOverride: Boolean,
    /** 合成方法（Lambda 体、属性访问器），非用户声明。 */
    val isSynthetic: Boolean,
    /** JVM 注解（T-M5-6）。 */
    val annotations: List<IrAnnotation> = emptyList(),
    /** 访问控制（S-5.1.4）：默认 public。 */
    val visibility: yux.compiler.ast.YxVisibility = yux.compiler.ast.YxVisibility.PUBLIC,
    val body: MutableList<IrStmt> = mutableListOf(),
    /** 所属类；Lambda 方法在生成后由 IRGen 回填。 */
    val owner: IrClass? = null,
) {
    /** 参数局部变量（index 0..n-1，与 [body] 中其它局部槽位连续）。 */
    val paramLocals: List<IrLocal> =
        params.mapIndexed { i, p -> IrLocal(p.name, p.type, i) }
}
