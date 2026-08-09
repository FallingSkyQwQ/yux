package yux.minecraft.nbt

/**
 * NBT 标签基类（T-M8-9 / 03-§6.2）：全部具体标签类型的密封基类。
 *
 * 与 Minecraft 原生 NBT 的对应关系见各子类；Boolean 以 NbtByte(1/0) 形式存储。
 */
sealed class NbtTag

/** 单字节有符号整数（TAG_Byte）；也承载 Boolean（1/0）。 */
class NbtByte(val value: Byte) : NbtTag()

/** 双字节有符号整数（TAG_Short）。 */
class NbtShort(val value: Short) : NbtTag()

/** 四字节有符号整数（TAG_Int）。 */
class NbtInt(val value: Int) : NbtTag()

/** 八字节有符号整数（TAG_Long）。 */
class NbtLong(val value: Long) : NbtTag()

/** 单精度浮点数（TAG_Float）。 */
class NbtFloat(val value: Float) : NbtTag()

/** 双精度浮点数（TAG_Double）。 */
class NbtDouble(val value: Double) : NbtTag()

/** 字符串（TAG_String）。 */
class NbtString(val value: String) : NbtTag()

/** 同构标签列表（TAG_List），元素为任意 [NbtTag] 子类。 */
class NbtList(val values: MutableList<NbtTag> = mutableListOf()) : NbtTag()

/** 键值对复合标签（TAG_Compound），键为字符串。 */
class NbtCompound(val entries: MutableMap<String, NbtTag> = linkedMapOf()) : NbtTag()
