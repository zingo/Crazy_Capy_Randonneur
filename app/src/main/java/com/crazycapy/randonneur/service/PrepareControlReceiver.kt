/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.crazycapy.randonneur.cache.RoutePrepare

class PrepareControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PrepareActions.PAUSE -> RoutePrepare.pause()
            PrepareActions.RESUME -> RoutePrepare.resume(context)
            PrepareActions.CANCEL -> RoutePrepare.cancel(context)
        }
    }
}
