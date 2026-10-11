package com.nizam.app.feature.jarvis

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.NizamApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Work that must outlive the screen that started it — an order firing from a notification, the
 * co-pilot acting after its sheet closes, the wake word handling a command.
 */
object Background {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun container(c: Context): AppContainer = (c.applicationContext as NizamApplication).container
}
