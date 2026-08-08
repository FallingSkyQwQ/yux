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
        val resolved = SemaType.resolveVar(type)
        val base = when (resolved) {
            is SemaType.UnitT -> IrType.Void
            is SemaType.NothingT -> IrType.Nothing
            is SemaType.ErrorT -> IrType.Error
            is SemaType.Basic -> IrType.Basic(resolved.name)
            is SemaType.Declared -> IrType.Declared(resolved.symbol, resolved.args.map(::toIr))
            is SemaType.TypeParam -> IrType.TypeParam(resolved.name, resolved.bound?.let(::toIr))
            is SemaType.Function -> IrType.Function(resolved.params.map(::toIr), toIr(resolved.ret))
            is SemaType.InferenceVar -> IrType.Error // 未求解推断变量（不应抵达 IR）
        }
        // 用「已求解值」的可空标记判定是否包装：已求解的可空推断变量（如 Int?）须产出可空 IR
        return if (resolved.nullable && !base.nullable) IrType.Nullable(base) else base
    }
}
