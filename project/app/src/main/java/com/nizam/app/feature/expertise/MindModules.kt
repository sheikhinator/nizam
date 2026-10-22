package com.nizam.app.feature.expertise

import com.nizam.app.feature.nx.Spec

/** Every engine Atlas absorbed from LYCHO, registered in one place. */
object MindModules {
    val all: List<Spec> by lazy { IntelligenceSpecs + WorkforceSpecs + GrowthSpecs + WatchSpecs }

    /** Background work, called from Atlas's tick: Orion at night, Scout in the morning, Sense once a day. */
    suspend fun tick(c: android.content.Context) {
        runCatching { Orion.nightly(c) }
        runCatching { Scout.daily(c) }
        runCatching { Sense.daily(c) }
        runCatching { com.nizam.app.feature.safe.AtlasRefresh.nightly(c) }
    }
}
