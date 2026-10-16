package com.vw1980.bcm

import android.content.Context
import android.os.Build
import java.io.File

object AppDiagnostics {
    private var installed = false
    fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText("SYNTX · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" + error.stackTraceToString().take(10000))
            }
            previous?.uncaughtException(thread, error)
        }
    }
    fun lastCrash(context: Context): String? = runCatching { File(context.filesDir, "last_crash.txt").readText() }.getOrNull()
}
