package yux.buildtool

import yux.compiler.ast.YxAccessor
import yux.compiler.ast.YxClass
import yux.compiler.ast.YxClassMember
import yux.compiler.ast.YxDataClass
import yux.compiler.ast.YxDecl
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxFunction
import yux.compiler.ast.YxFunctionBody
import yux.compiler.ast.YxFunctionType
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxImport
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNamedType
import yux.compiler.ast.YxNode
import yux.compiler.ast.YxProperty
import yux.compiler.ast.YxService
import yux.compiler.ast.YxType
import yux.compiler.ast.YxTypeReference
import yux.compiler.sema.AstScan
import yux.compiler.sema.SymbolTable

/**
 * 文件依赖图（类级增量，M13）：计算每个源文件**引用的其它文件**，供反向依赖重编。
 *
 * 保守策略（正确性优先）：对每个文件收集声明级类型（父类/接口/参数/返回/属性/扩展接收者）、
 * 函数体与初始值内的全部标识符与类型引用、以及 import 目标；再与全局「简单名 → 归属文件」
 * 索引比对（类型 + 顶层函数 + 顶层属性 + 扩展函数名），命中非本文件即记为依赖。
 *
 * 允许过近似（把同名局部引用也算依赖 → 多重编一些文件），但**不允许漏依赖**
 * （漏了会产生陈旧产物）。依赖随每次分析重新计算并写回缓存，供下一次构建判定。
 */
object FileDependencies {

    /** 计算 declsByFile 中每个文件的依赖清单（清单键 = 源路径字符串）。 */
    fun compute(declsByFile: Map<String, List<YxDecl>>, symbolTable: SymbolTable): Map<String, List<String>> {
        val ownerBySimpleName = mutableMapOf<String, String>()
        for (file in symbolTable.files) {
            val path = file.path
            for (name in file.types.keys) ownerBySimpleName.putIfAbsent(name, path)
            for (name in file.topLevelFunctions.keys) ownerBySimpleName.putIfAbsent(name, path)
            for (name in file.topLevelProperties.keys) ownerBySimpleName.putIfAbsent(name, path)
            // 扩展函数按接收者类型名登记（成员名可跨接收者复用，接收者归属文件即依赖）
            for (receiver in file.extensionFunctions.keys) ownerBySimpleName.putIfAbsent(receiver, path)
        }

        val result = mutableMapOf<String, MutableSet<String>>()
        for ((path, decls) in declsByFile) {
            val names = mutableSetOf<String>()
            val deps = mutableSetOf<String>()
            for (decl in decls) {
                collectNames(decl, names)
            }
            for (name in names) {
                val owner = ownerBySimpleName[name]
                if (owner != null && owner != path) deps += owner
            }
            result[path] = deps
        }
        return result.mapValues { it.value.toList() }
    }

    /** 收集声明中引用的全部候选名（标识符 + 类型首/末段 + 成员名 + import 目标）。 */
    private fun collectNames(decl: YxDecl, names: MutableSet<String>) {
        when (decl) {
            is YxImport -> decl.qualifiedName.forEach { names += it }
            is YxFunction -> {
                names += decl.name
                decl.receiver?.let { collectType(it, names) }
                decl.params.forEach { collectType(it.type, names) }
                decl.returnType?.let { collectType(it, names) }
                decl.body.let { body ->
                    when (body) {
                        is YxFunctionBody.YxExpressionBody -> collectExpr(body.expr, names)
                        is YxFunctionBody.YxBlockBody -> collectBody(body.block, names)
                    }
                }
            }
            is YxProperty -> {
                names += decl.name
                decl.type?.let { collectType(it, names) }
                decl.initializer?.let { collectExpr(it, names) }
                decl.accessors.forEach { collectAccessor(it, names) }
            }
            is YxClass -> collectClass(decl.name, decl.superType, decl.interfaces, decl.members, names)
            is YxDataClass -> collectClass(decl.name, decl.superType, decl.interfaces, decl.members, names)
            is YxService -> collectClass(decl.name, null, emptyList(), decl.members, names)
            else -> Unit
        }
    }

    private fun collectAccessor(accessor: YxAccessor, names: MutableSet<String>) {
        collectBody(accessor.body, names)
    }

    private fun collectClass(
        name: String,
        superType: YxType?,
        interfaces: List<YxType>,
        members: List<YxClassMember>,
        names: MutableSet<String>,
    ) {
        names += name
        superType?.let { collectType(it, names) }
        interfaces.forEach { collectType(it, names) }
        members.forEach { member -> if (member is YxDecl) collectNames(member, names) }
    }

    private fun collectType(type: YxType, names: MutableSet<String>) {
        when (type) {
            is YxNamedType -> {
                names += type.segments.firstOrNull().orEmpty()
                names += type.segments.lastOrNull().orEmpty()
                type.typeArgs.forEach { collectType(it, names) }
            }
            is YxFunctionType -> {
                type.params.forEach { collectType(it, names) }
                collectType(type.ret, names)
            }
        }
    }

    /** 块语句（复用 AstScan 遍历全部语句/表达式）。 */
    private fun collectBody(block: YxNode, names: MutableSet<String>) {
        AstScan.visit(block) { node ->
            when (node) {
                is YxIdentifier -> names += node.name
                is YxMemberAccess -> names += node.name
                is YxTypeReference -> {
                    when (val t = node.type) {
                        is YxNamedType -> {
                            t.segments.firstOrNull()?.let { names += it }
                            t.segments.lastOrNull()?.let { names += it }
                        }
                        is YxFunctionType -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    private fun collectExpr(expr: YxExpr, names: MutableSet<String>) {
        AstScan.visit(expr) { node ->
            when (node) {
                is YxIdentifier -> names += node.name
                is YxMemberAccess -> names += node.name
                is YxTypeReference -> {
                    when (val t = node.type) {
                        is YxNamedType -> {
                            t.segments.firstOrNull()?.let { names += it }
                            t.segments.lastOrNull()?.let { names += it }
                        }
                        is YxFunctionType -> Unit
                    }
                }
                else -> Unit
            }
        }
    }
}
