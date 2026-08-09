// T-M10-1：Java 侧（05-§5.1 Yux → Java：Logger 单例被 Yux service 调用）
package com.example;

public final class Logger {
    private static final Logger INSTANCE = new Logger();
    private Logger() {}
    public static Logger getInstance() { return INSTANCE; }
    public void log(String msg) { System.out.println("[MyServer] " + msg); }
}
