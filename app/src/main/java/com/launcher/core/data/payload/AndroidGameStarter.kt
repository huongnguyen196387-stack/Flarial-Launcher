package com.launcher.core.data.payload

import android.content.Context
import android.content.Intent
import com.launcher.core.domain.GamePaths
import com.launcher.core.domain.GameStarter

class AndroidGameStarter(private val context: Context) : GameStarter {
    override fun start(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(GamePaths.PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
