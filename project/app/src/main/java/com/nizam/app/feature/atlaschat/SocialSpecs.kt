package com.nizam.app.feature.atlaschat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub

object SocialSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("atlassocial", "Atlas Social", "Photos, 24-hour stories, a public feed, likes, comments and follows",
            Icons.Outlined.Public, Hub.Social, "social instagram snapchat facebook feed stories photos follow post share friends") { _, _ -> AtlasSocialScreen() },
    )
}
