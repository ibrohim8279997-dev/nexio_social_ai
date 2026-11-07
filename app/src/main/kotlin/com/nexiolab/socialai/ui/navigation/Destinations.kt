package com.nexiolab.socialai.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.model.AgentRole

/** Bottom-bar destinations, in the order the product spec lists them. */
enum class Tab(val route: String, @StringRes val label: Int, @StringRes val title: Int, val icon: ImageVector) {
    Dashboard("dashboard", R.string.shell_tab_dashboard_label, R.string.shell_tab_dashboard_title, Icons.Outlined.Home),
    Agents("agents", R.string.shell_tab_agents_label, R.string.shell_tab_agents_title, Icons.Outlined.AutoAwesome),
    Calendar("calendar", R.string.shell_tab_calendar_label, R.string.shell_tab_calendar_title, Icons.Outlined.CalendarMonth),
    Video("video", R.string.shell_tab_video_label, R.string.shell_tab_video_title, Icons.Outlined.VideoLibrary),
    Analytics("analytics", R.string.shell_tab_analytics_label, R.string.shell_tab_analytics_title, Icons.AutoMirrored.Outlined.ShowChart),
    Settings("settings", R.string.shell_tab_settings_label, R.string.shell_tab_settings_title, Icons.Outlined.Settings),
}

/** Sections reached from the drawer and from Settings. */
enum class Section(val route: String, @StringRes val title: Int, val icon: ImageVector) {
    Brand("brand", R.string.shell_section_brand, Icons.Outlined.Badge),
    Media("media", R.string.shell_section_media, Icons.Outlined.PermMedia),
    Instagram("instagram", R.string.shell_section_instagram, Icons.Outlined.PhotoCamera),
    Expenses("expenses", R.string.shell_section_expenses, Icons.Outlined.AccountBalanceWallet),
    Api("api", R.string.shell_section_api, Icons.Outlined.Key),
    Migration("migration", R.string.shell_section_migration, Icons.Outlined.SwapHoriz),
    Security("security", R.string.shell_section_security, Icons.Outlined.Security),
}

/** Screens below the tabs and sections. */
object Routes {
    const val INTERVIEW = "interview"
    const val MONTHLY = "monthly"
    fun agent(role: AgentRole) = "agent/${role.name}"
    fun task(id: Long) = "task/$id"
    fun content(id: Long) = "content/$id"
    fun provider(id: Long) = "provider/$id"
    fun video(id: Long) = "video/$id"

    @StringRes
    fun title(route: String?): Int? = when (route?.substringBefore('/')) {
        INTERVIEW -> R.string.shell_route_interview
        MONTHLY -> R.string.shell_route_monthly
        "agent" -> R.string.shell_route_agent
        "task" -> R.string.shell_route_task
        "content" -> R.string.shell_route_content
        "provider" -> R.string.shell_route_provider
        "video" -> R.string.shell_route_video
        else -> null
    }
}
