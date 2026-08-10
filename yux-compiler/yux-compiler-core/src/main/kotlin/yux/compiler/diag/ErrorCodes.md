# Error Codes 诊断码手册（T-M3-10）

> 本文件是诊断码的唯一事实源（06-§7.1）。源码常量见 `ErrorCodes.kt`，
> 任何诊断码的新增/删除/重命名都必须同步本表与源码。

## 命名约定

- `E####` 编译错误：终止对应文件编译。
- `W####` 警告：编译继续。
- `R####` REMIND：空安全/风格提示（可 `-A` 关闭，02-§12.2）。

## E 系列：编译错误

| 码 | 常量名 | 规则依据 | 说明 |
|---|---|---|---|
| E0001 | `UNRESOLVED_REFERENCE` | 01-§7.7, 02-§6 | 名称无法解析（标识符/调用目标） |
| E0002 | `DUPLICATE_DECLARATION` | 02-§6.2 | 同一作用域重复声明 |
| E0003 | `CONDITION_NOT_BOOLEAN` | 01-S-6.2.1 | 条件必须是 `Boolean`（不自动转换数字） |
| E0004 | `TYPE_MISMATCH` | 01-§4,§7.5 | 类型不匹配（赋值/实参/返回） |
| E0005 | `TYPE_ARGUMENT_COUNT_MISMATCH` | 01-S-4.3.2 | 泛型实参数目与类型参数不一致 |
| E0006 | `ARGUMENT_COUNT_MISMATCH` | 01-S-5.5.5 | 实参数目与形参不一致（无默认值的形参缺失） |
| E0007 | `VARIABLE_NOT_INITIALIZED` | 01-S-6.1.2 | 变量使用前未确定性赋值 |
| E0008 | `PARAMETER_TYPE_MISSING` | 01-S-4.5.3 | 函数参数必须显式类型（ADR-08） |
| E0009 | `UNRESOLVED_TYPE` | 01-§4.1 | 类型无法解析 |
| E0010 | `ILLEGAL_ACCESSOR` | 01-S-5.2.2/5.2.4 | 访问器声明非法（重复/体缺失/只读 set 等） |
| E0011 | `ILLEGAL_DATA_MEMBER` | 01-S-5.3.1 | data 类成员非法（仅允许属性） |
| E0012 | `SERVICE_CYCLE` | 01-S-5.4.1 | service 依赖注入图存在循环 |
| E0013 | `INVALID_ANNOTATION_TARGET` | 01-S-8.2.1 | 注解目标非法 |
| E0014 | `INFERENCE_FAILURE` | 01-S-4.5.4 | 类型推断失败（不猜测类型） |
| E0015 | `RETURN_TYPE_MISMATCH` | 01-S-5.5.2/6.5.1 | 返回类型与声明/推断不一致 |
| E0016 | `UNRESOLVED_MEMBER` | 01-§7.7 | 成员（属性/方法）在接收者类型上不存在 |
| E0017 | `INVALID_OPERATOR_OPERAND` | 01-S-7.5.1~7.5.4 | 运算符操作数类型非法 |
| E0018 | `THIS_OUTSIDE_CLASS` | 01-§7 | `this` 在类外使用 |
| E0019 | `OVERRIDE_NO_SUPER` | 01-S-8.7.2 | `override` 无对应父类/接口成员 |
| E0020 | `VAL_ASSIGNMENT` | 01-S-6.1.1 | 只读属性/参数/常量赋值 |
| E0021 | `BREAK_OUTSIDE_LOOP` | 01-S-6.4.3 | `break`/`continue` 在循环外 |
| E0022 | `LOOP_ITERABLE_INVALID` | 01-S-6.4.1 | `for ... in` 的目标不可迭代 |
| E0023 | `NULLABLE_WRITE_NEEDS_CHECK` | 01-S-8.1 | 可空接收者写路径需显式判空（不自动守卫） |
| E0024 | `SUPER_OUTSIDE_CLASS` | 01-S-8.7 | `super` 在类外使用 |
| E0025 | `DUPLICATE_OVERRIDE` | 01-S-8.7.2 | 同成员重复 `override` |
| E0026 | `SERVICE_PROPERTY_NO_INIT` | 01-S-5.4.1 | service 属性（非注入类型）缺少初始值 |
| E0027 | `AMBIGUOUS_REFERENCE` | 02-§6 | 名称解析歧义（多个候选） |
| E0028 | `ILLEGAL_ASSIGN_TARGET` | 01-S-6.1.1 | 赋值目标不合法 |
| E0029 | `DUPLICATE_PLUGIN_ID` | 02-§10.4 | 插件 id 重复注册 |
| E0030 | `DUPLICATE_EXTENSION_KEYWORD` | 02-§10.4 | 扩展关键字冲突（已被其他插件注册） |
| E0031 | `RESERVED_EXTENSION_KEYWORD` | 02-§10.4 | 插件注册了内置关键字（隔离性） |
| E0032 | `EXTENSION_NOT_LOWERED` | 02-§10.4 | 扩展节点未下沉（无匹配 SyntaxTransform） |
| E0033 | `ILLEGAL_ACCESS` | 01-S-5.1.4 | 跨类/跨文件访问 private/protected 成员 |
| E0034 | `WHEN_NOT_EXHAUSTIVE` | T-M12 | when 表达式缺 else（非密封 subject）或密封 subject 未穷尽覆盖 |
| E0035 | `SEALED_INSTANTIATION` | T-M12 | 直接实例化密封类 |
| E0036 | `SEALED_SUBCLASS_DIFFERENT_FILE` | T-M12 | 密封类的直接子类声明在其它文件 |

## W 系列：警告

| 码 | 常量名 | 规则依据 | 说明 |
|---|---|---|---|
| W0001 | `TYPE_FALLBACK` | 01-§7.7.4 | 未知名称按命名约定（大写→类型）回退 |

## R 系列：REMIND

| 码 | 常量名 | 规则依据 | 说明 |
|---|---|---|---|
| R0001 | `NULL_GUARD_INSERTED` | 01-S-8.1, ADR-10 | 可空接收者读取已自动插入空守卫 |
| R0002 | `SMART_CAST_MUTABLE` | 02-§7.3 | 可变引用不执行智能转型（需显式转型） |
