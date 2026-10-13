package com.nizam.app.feature.atlaschat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub

object AtlasChatSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("atlaschat", "Atlas Messenger", "Private end-to-end encrypted chats with other Atlas users, no phone number needed",
            Icons.Outlined.Forum, Hub.Social, "chat messenger message telegram whatsapp signal private encrypted friends dm") { _, _ -> AtlasMessengerScreen() },
    )
}
