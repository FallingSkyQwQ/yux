package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * T-M13 运算符重载端到端：`operator fun plus/minus/times/compareTo/equals/get/set` 等
 * 编译运行并精确比对 stdout。
 *
 * 覆盖：成员二元运算符（plus/times）、compareTo 派生四种比较、equals 派生 `!=`、
 * 一元 unaryMinus、索引 get/set、跨文件扩展运算符、JVM List `+`（concat/append）。
 */
class OperatorOverloadE2eTest {

    @Test
    fun `member binary operators and compareTo`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                Vec {
                    x:Int
                    y:Int
                    operator fun plus(other:Vec):Vec {
                        return Vec(x + other.x, y + other.y)
                    }
                    operator fun times(k:Int):Vec {
                        return Vec(x * k, y * k)
                    }
                    operator fun compareTo(other:Vec):Int {
                        if (x != other.x) return x - other.x
                        return y - other.y
                    }
                    operator fun equals(other:Vec):Boolean {
                        return x == other.x && y == other.y
                    }
                    operator fun hashCode():Int {
                        return x * 31 + y
                    }
                }
                fun main() {
                    a = Vec(1, 2)
                    b = Vec(3, 4)
                    c = a + b
                    print c.x
                    print c.y
                    print ((a * 5).x)
                    print ((a * 5).y)
                    print (a < b)
                    print (a <= b)
                    print (b > a)
                    print (b >= a)
                    print (a == a)
                    print (a != b)
                    print (a == b)
                }
                """.trimIndent(),
            )
        assertEquals("46510truetruetruetruetruetruefalse", out)
    }

    @Test
    fun `unary minus operator`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                Money {
                    amount:Int
                    operator fun unaryMinus():Money {
                        return Money(-amount)
                    }
                }
                fun main() {
                    m = Money(7)
                    print ((-m).amount)
                    print m.amount
                }
                """.trimIndent(),
            )
        assertEquals("-77", out)
    }

    @Test
    fun `index get and set operators`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                Pair {
                    first:Int
                    second:Int
                    operator fun get(i:Int):Int {
                        return if (i == 0) then first else second
                    }
                    operator fun set(i:Int, v:Int):Unit {
                        if (i == 0) {
                            this.first = v
                        } else {
                            this.second = v
                        }
                    }
                }
                fun main() {
                    p = Pair(0, 0)
                    p[0] = 10
                    p[1] = 20
                    print p[0]
                    print p[1]
                    print (p[1] + p[0])
                }
                """.trimIndent(),
            )
        assertEquals("102030", out)
    }

    @Test
    fun `extension operator across files`() {
        val out =
            BackendTestSupport.run(
                BackendTestSupport.compileMany(
                    mapOf(
                        "vec.yux" to
                            """
                            Vec {
                                x:Int
                                y:Int
                            }
                            """.trimIndent(),
                        "ops.yux" to
                            """
                            operator fun Vec.plus(other:Vec):Vec {
                                return Vec(this.x + other.x, this.y + other.y)
                            }
                            """.trimIndent(),
                        "main.yux" to
                            """
                            fun main() {
                                a = Vec(1, 2)
                                b = Vec(3, 4)
                                c = a + b
                                print c.x
                                print c.y
                            }
                            """.trimIndent(),
                    ),
                ),
                "Main",
            )
        assertEquals("46", out)
    }

    @Test
    fun `jvm list plus concat and append`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun main() {
                    a = List Int()
                    a.add(1); a.add(2)
                    b = List Int()
                    b.add(3)
                    c = (a + b) as List Any
                    print c.size
                    d = (a + 9) as List Any
                    print d.size
                    print (d.get(2) as Int)
                }
                """.trimIndent(),
            )
        assertEquals("339", out)
    }
}
