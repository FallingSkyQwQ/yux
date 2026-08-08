@file:Suppress("unused")

package yux.plugin.api

import yux.compiler.ast.YxExtensionDecl

/**
 * 编译器插件 SPI 再导出（02-§10 / T-M6）。
 *
 * SPI 接口定义于 `yux-compiler-core`（`yux.compiler.plugin` 包）：Parser 等核心
 * 组件须引用这些类型，而 core 不能依赖 plugin-api（依赖方向相反），故本模块以
 * typealias 再导出，保持文档约定的 `yux.plugin.api` 命名空间供插件作者使用。
 */

/** 编译器插件（02-§10.1）。 */
typealias YuxCompilerPlugin = yux.compiler.plugin.YuxCompilerPlugin

/** 插件上下文：扩展注册通道（02-§10.2）。 */
typealias PluginContext = yux.compiler.plugin.PluginContext

/** 扩展关键字解析函数（02-§10.2.1）。 */
typealias ExtensionParser = yux.compiler.plugin.ExtensionParser

/** 语法下沉（02-§10.2.2）。 */
typealias SyntaxTransform = yux.compiler.plugin.SyntaxTransform

/** 语义规则（02-§10.2.3）。 */
typealias SemanticRule = yux.compiler.plugin.SemanticRule

/** 代码生成钩子（02-§10.2.4）。 */
typealias CodegenHook = yux.compiler.plugin.CodegenHook

/** 代码生成钩子产物。 */
typealias PluginArtifact = yux.compiler.plugin.PluginArtifact

/** 扩展声明节点（插件解析器产出，下沉前驻留 AST）。 */
typealias ExtensionNode = YxExtensionDecl

/** 插件注册表（T-M6-2）。 */
typealias PluginManager = yux.compiler.plugin.PluginManager

/** 插件加载（T-M6-3）。 */
typealias PluginLoader = yux.compiler.plugin.PluginLoader
