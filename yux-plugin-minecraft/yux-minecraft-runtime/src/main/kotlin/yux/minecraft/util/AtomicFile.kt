package yux.minecraft.util

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 原子写文本文件：同目录 `<name>.tmp` 写入后 ATOMIC_MOVE 覆盖目标（崩溃/并发不留半写文件）。 */
internal fun atomicWriteText(target: File, text: String) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}
