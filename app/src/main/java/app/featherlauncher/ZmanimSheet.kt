package app.featherlauncher

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Today's zmanim in a sheet: past ones faded, the next one picked out. */
object ZmanimSheet {
    fun show(ctx: Context, p: Palette, overlays: Overlays, place: ClockInfo.Place) {
        val info = ClockInfo(ctx)
        val now = System.currentTimeMillis()
        val list = info.zmanim(now, place)
        val next = list.firstOrNull { it.time > now }
        val format = android.text.format.DateFormat.getTimeFormat(ctx).apply { timeZone = place.zone }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Ui.dp(ctx, Ui.SPACE_M))
            addView(TextView(ctx).apply {
                text = "${info.dateLine(now, place)} · ${place.name}"
                style(TextStyle.Secondary, p.textSecondary)
                setPadding(Ui.dp(ctx, Ui.SPACE_XL), 0, Ui.dp(ctx, Ui.SPACE_XL), Ui.dp(ctx, Ui.SPACE_S))
            })
        }
        list.forEach { z ->
            val past = z.time <= now
            val isNext = z == next
            val color = when {
                isNext -> p.accent
                past -> p.textTertiary
                else -> p.text
            }
            column.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(ctx, 44)
                setPadding(Ui.dp(ctx, Ui.SPACE_XL), 0, Ui.dp(ctx, Ui.SPACE_XL), 0)
                if (isNext) {
                    background = Ui.rounded(ctx, p.accentSurface, Ui.RADIUS_M)
                    // The highlight is inset from the sheet's edges; the text stays in line with the other rows.
                    setPadding(Ui.dp(ctx, Ui.SPACE_XL - Ui.SPACE_S), 0, Ui.dp(ctx, Ui.SPACE_XL - Ui.SPACE_S), 0)
                }
                addView(TextView(ctx).apply {
                    text = info.zmanName(z)
                    style(if (isNext) TextStyle.Label else TextStyle.Body, color)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(ctx).apply {
                    text = format.format(java.util.Date(z.time))
                    style(if (isNext) TextStyle.Label else TextStyle.Body, color)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (isNext) setMargins(Ui.dp(ctx, Ui.SPACE_S), 0, Ui.dp(ctx, Ui.SPACE_S), 0)
            })
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        overlays.showSheet(if (Prefs(ctx).dailyZmanim) "Zmanim" else "Today's times", scroll, scrollable = scroll, fill = true)
    }
}
