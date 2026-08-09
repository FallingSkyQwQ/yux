// T-M10-1：Kotlin 侧（05-§5.2 Yux → Kotlin：Currency.format 被 Yux service 调用）
package com.example

object Currency {
    @JvmStatic
    fun format(amount: Double): String =
        "$" + String.format("%,.2f", amount)
}
