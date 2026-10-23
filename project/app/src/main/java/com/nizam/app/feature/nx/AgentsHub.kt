package com.nizam.app.feature.nx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Extension
import com.nizam.app.ui.design.Hub

/** Agent teams, the connector catalogue and the approval inbox, reachable from search, hubs and the agent. */
object AgentsHub {
    val all: List<Spec> = Ascended.all + listOf(
        ScreenSpec("hq", "Atlas HQ", "A 3D office where you watch your agents work, and walk around it", Icons.Outlined.Apartment, Hub.Work,
            "hq office 3d agents desk who needs me meeting table explore building tower") { c, o -> com.nizam.app.feature.hq.HqScreen(c, o) },
        ScreenSpec("crew", "Agent teams", "A lead that delegates to specialists who work in parallel", Icons.Outlined.Groups, Hub.Work,
            "agents team crew multi agent delegate researcher planner writer operator") { c, _ -> com.nizam.app.feature.agent.CrewScreen(c) },
        ScreenSpec("contextreceipt", "Run context receipt", "Review what the agent included in recent requests and control which data is shared", Icons.Outlined.Extension, Hub.Tools,
            "agent context receipt prompt privacy data snapshot") { _, _ -> com.nizam.app.feature.agent.RunContextReceiptScreen() },
        ScreenSpec("assumptioncheckpoint", "Assumption checkpoint", "Pause before consequential actions when important details are inferred", Icons.Outlined.Extension, Hub.Tools,
            "agent assumptions checkpoint confirm before action approval") { _, _ -> com.nizam.app.feature.agent.AssumptionCheckpointScreen() },
        ScreenSpec("connectors", "Connectors", "250+ apps and services Atlas can use", Icons.Outlined.Extension, Hub.Tools,
            "connect integrations apps api notion github slack gmail zapier home assistant mcp") { c, _ -> com.nizam.app.feature.connect.ConnectorsScreen(c) },
    )
}
