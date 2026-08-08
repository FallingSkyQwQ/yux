package yux.compiler.irgen

import yux.compiler.ir.IrType
import yux.compiler.sema.SemaType

/**
 * M3 类型 → IR 类型桥接（T-M4-3：02-§8.1 的 IrType 与 M3 类型桥接）。
 *
 * 可空性以 [IrType.Nullable] 包装表达（SemaType 的各变体自带 nullable 标记）；
 * [SemaType.InferenceVar] 先解引用（S-4.5 的 [SemaType.resolveVar]），未求解视为错误。
 */
object TypeBridge {

    fun toIr(type: SemaType): IrType {
        val base = when (val t = SemaType.resolveVar(type)) {
            is SemaType.UnitT -> IrType.Void
            is SemaType.NothingT -> IrType.Nothing
            is SemaType.ErrorT -> IrType.Error
            is SemaType.Basic -> IrType.Basic(t.name)
            is SemaType.Declared -> IrType.Declared(t.symbol, t.args.map(::toIr))
            is SemaType.TypeParam -> IrType.TypeParam(t.name, t.bound?.let(::toIr))
            is SemaType.Function -> IrType.Function(t.params.map(::toIr), toIr(t.ret))
            is SemaType.InferenceVar -> IrType.Error // 未求解推断变量（不应抵达 IR）
        }
        return if (base is IrType.Nullable || base is IrType.Void || base is IrType.Nothing || base is IrType.Error) {
            base
        } else {
            // 仅包装「可空标记缺失」的情形（不可空原样返回）
            if (type.nullable && !base.nullable) IrType.Nullable(base) else base
        }
    }
}
