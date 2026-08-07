package yux.compiler.lexer

/** Lexer 与 InterpolationScanner 共享的扫描状态。 */
internal class ScannerState(val text: String) {
    var offset: Int = 0
    var line: Int = 1
    var column: Int = 1
}
