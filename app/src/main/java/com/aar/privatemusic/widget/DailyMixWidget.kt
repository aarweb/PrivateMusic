package com.aar.privatemusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aar.privatemusic.MainActivity
import com.aar.privatemusic.R

const val ACTION_PLAY_DAILY_MIX = "com.aar.privatemusic.PLAY_DAILY_MIX"

/** Homescreen widget: one tap plays today's mix, another opens the app. */
class DailyMixWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_daily)

            val playIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_PLAY_DAILY_MIX
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            views.setOnClickPendingIntent(
                R.id.widget_play_mix,
                PendingIntent.getActivity(
                    context, 1, playIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            views.setOnClickPendingIntent(
                R.id.widget_open,
                PendingIntent.getActivity(
                    context, 2, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            manager.updateAppWidget(id, views)
        }
    }
}
