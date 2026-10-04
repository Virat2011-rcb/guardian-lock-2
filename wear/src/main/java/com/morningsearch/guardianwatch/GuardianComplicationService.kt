package com.morningsearch.guardianwatch

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class GuardianComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val status = WatchStateStore(this).lastStatus()
        val text = "Phone ${if (status.batteryPercent >= 0) "${status.batteryPercent}%" else "--"}"
        val title = if (status.phoneOnline) "Online" else "Offline"
        listener.onComplicationData(
            ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text).build(),
                contentDescription = PlainComplicationText.Builder("$text $title").build()
            ).setTitle(PlainComplicationText.Builder(title).build()).build()
        )
    }

    override fun getPreviewData(type: androidx.wear.watchface.complications.data.ComplicationType): ComplicationData? {
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("Phone 72%").build(),
            PlainComplicationText.Builder("Phone 72 percent online").build()
        ).setTitle(PlainComplicationText.Builder("Online").build()).build()
    }
}
