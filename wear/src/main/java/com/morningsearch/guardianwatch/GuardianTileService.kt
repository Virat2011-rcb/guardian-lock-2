package com.morningsearch.guardianwatch

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class GuardianTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val status = WatchStateStore(this).lastStatus()
        val text = "Phone ${if (status.batteryPercent >= 0) "${status.batteryPercent}%" else "--"} • ${if (status.phoneOnline) "Online" else "Offline"}"
        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setClickable(ModifiersBuilders.Clickable.Builder()
                    .setOnClick(ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build())
                        .build())
                    .build())
                .build())
            .addContent(LayoutElementBuilders.Text.Builder()
                .setText("Guardian\n$text")
                .build())
            .build()
        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder()
                .setLayout(LayoutElementBuilders.Layout.Builder()
                    .setRoot(layout)
                    .build())
                .build())
            .build()
        return Futures.immediateFuture(TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(timeline)
            .build())
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        Futures.immediateFuture(androidx.wear.protolayout.ResourceBuilders.Resources.Builder().setVersion("1").build())
}
