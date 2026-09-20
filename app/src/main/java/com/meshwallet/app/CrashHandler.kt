package com.meshwallet.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CrashDisplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.setPadding(40, 100, 40, 40)
        tv.text = intent.getStringExtra("crash_info") ?: "Unknown crash"
        tv.setTextIsSelectable(true)
        setContentView(tv)
    }
}

class MeshWalletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val info = "CRASH:\n${throwable.javaClass.name}: ${throwable.message}\n\n${throwable.stackTraceToString()}"
                val intent = Intent(this, CrashDisplayActivity::class.java)
                intent.putExtra("crash_info", info)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e: Exception) {
                // if even this fails, fall through to default handler
            }
            Thread.sleep(500) // give the new activity a moment to launch before the process dies
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
