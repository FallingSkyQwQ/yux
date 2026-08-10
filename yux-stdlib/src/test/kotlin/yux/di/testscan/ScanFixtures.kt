package yux.di.testscan

import yux.di.YuxService

/**
 * scanAndRegister 测试夹具包（扫描目录型 classpath；NotAnnotated 不应被登记）。
 */
@YuxService
class ScanDb

@YuxService
class ScanUser(val db: ScanDb)

class NotAnnotated
