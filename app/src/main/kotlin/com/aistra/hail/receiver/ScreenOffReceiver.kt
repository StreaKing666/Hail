package com.aistra.hail.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistra.hail.work.HWork

class ScreenOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF) {
            if (com.aistra.hail.app.HailData.autoFreezeAfterLock) HWork.setAutoFreeze(true)
            com.aistra.hail.work.RuleScheduler.enqueue(context, com.aistra.hail.enhanced.domain.RuleTrigger.SCREEN_OFF)
        }
    }
}