package com.br.resonance

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UpdateDialogHelper: Displays a Cream Neobrutalist update dialog when a newer
 * stable release is detected on GitHub.
 */
object UpdateDialogHelper {

    fun showUpdateDialog(activity: Activity, release: ReleaseInfo, currentVersionName: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = FrameLayout(activity).apply {
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 20f),
                NeobrutalistTheme.dpToPx(activity, 20f),
                NeobrutalistTheme.dpToPx(activity, 20f),
                NeobrutalistTheme.dpToPx(activity, 20f)
            )
        }

        // Main Dialog Card
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = NeobrutalistTheme.card(
                activity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 18f,
                shadowOffsetDp = 6f,
                strokeWidthDp = 2.5f
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(card)

        // 1. Top Decorative Accent Strip (Warm Golden Yellow)
        val accentStrip = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NeobrutalistTheme.COLOR_ACCENT)
                cornerRadii = floatArrayOf(
                    NeobrutalistTheme.dpToPx(activity, 16f).toFloat(),
                    NeobrutalistTheme.dpToPx(activity, 16f).toFloat(),
                    NeobrutalistTheme.dpToPx(activity, 16f).toFloat(),
                    NeobrutalistTheme.dpToPx(activity, 16f).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(activity, 8f)
            )
        }
        card.addView(accentStrip)

        // Inner Padding Content Layout
        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 20f),
                NeobrutalistTheme.dpToPx(activity, 16f),
                NeobrutalistTheme.dpToPx(activity, 20f),
                NeobrutalistTheme.dpToPx(activity, 20f)
            )
        }
        card.addView(contentLayout)

        // 2. Header Row: Badge + Title
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 12f))
        }
        contentLayout.addView(headerRow)

        val iconBadge = TextView(activity).apply {
            text = "🚀"
            textSize = 22f
            gravity = Gravity.CENTER
            val size = NeobrutalistTheme.dpToPx(activity, 44f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = NeobrutalistTheme.dpToPx(activity, 12f)
            }
            background = NeobrutalistTheme.card(
                activity,
                fillColor = Color.parseColor("#F5F0E8"),
                cornerRadiusDp = 10f,
                shadowOffsetDp = 2f,
                strokeWidthDp = 1.5f
            )
        }
        headerRow.addView(iconBadge)

        val headerTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerTextCol)

        val titleView = TextView(activity).apply {
            text = "UPDATE AVAILABLE"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
        }
        headerTextCol.addView(titleView)

        val subtitleView = TextView(activity).apply {
            text = "A new stable release is ready on GitHub"
            textSize = 11f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
        }
        headerTextCol.addView(subtitleView)

        // 3. Version Comparison Badges
        val versionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 14f))
        }
        contentLayout.addView(versionRow)

        val currentPill = TextView(activity).apply {
            text = "Current: v$currentVersionName"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#555555"))
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 10f),
                NeobrutalistTheme.dpToPx(activity, 6f),
                NeobrutalistTheme.dpToPx(activity, 10f),
                NeobrutalistTheme.dpToPx(activity, 6f)
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F0E8"))
                setStroke(NeobrutalistTheme.dpToPx(activity, 1.5f), NeobrutalistTheme.COLOR_BORDER)
                cornerRadius = NeobrutalistTheme.dpToPx(activity, 8f).toFloat()
            }
        }
        versionRow.addView(currentPill)

        val arrowView = TextView(activity).apply {
            text = "  ➔  "
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
        }
        versionRow.addView(arrowView)

        val newPill = TextView(activity).apply {
            val tagDisplay = if (release.tagName.startsWith("v", ignoreCase = true)) release.tagName else "v${release.versionName}"
            text = "Latest: $tagDisplay"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 10f),
                NeobrutalistTheme.dpToPx(activity, 6f),
                NeobrutalistTheme.dpToPx(activity, 10f),
                NeobrutalistTheme.dpToPx(activity, 6f)
            )
            background = GradientDrawable().apply {
                setColor(NeobrutalistTheme.COLOR_SUCCESS)
                setStroke(NeobrutalistTheme.dpToPx(activity, 1.5f), NeobrutalistTheme.COLOR_BORDER)
                cornerRadius = NeobrutalistTheme.dpToPx(activity, 8f).toFloat()
            }
        }
        versionRow.addView(newPill)

        // 4. Release Notes Box
        val notesLabel = TextView(activity).apply {
            text = "RELEASE NOTES"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 4f))
        }
        contentLayout.addView(notesLabel)

        val notesScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(activity, 110f)
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(activity, 16f)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAF7F2"))
                setStroke(NeobrutalistTheme.dpToPx(activity, 1.5f), Color.parseColor("#D4CEBE"))
                cornerRadius = NeobrutalistTheme.dpToPx(activity, 8f).toFloat()
            }
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 12f),
                NeobrutalistTheme.dpToPx(activity, 10f),
                NeobrutalistTheme.dpToPx(activity, 12f),
                NeobrutalistTheme.dpToPx(activity, 10f)
            )
        }

        val notesContent = if (release.releaseNotes.isNotBlank()) {
            release.releaseNotes
        } else {
            release.name.ifBlank { "New version available with improvements and fixes." }
        }

        val notesTextView = TextView(activity).apply {
            text = notesContent
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(0f, 1.25f)
        }
        notesScroll.addView(notesTextView)
        contentLayout.addView(notesScroll)

        // 5. Action Buttons (Side by Side: LATER | UPDATE NOW)
        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        contentLayout.addView(buttonRow)

        // LATER Button
        val btnLater = Button(activity).apply {
            text = "LATER"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            background = NeobrutalistTheme.card(
                activity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 10f,
                shadowOffsetDp = 3f,
                strokeWidthDp = 2f
            )
            setPadding(0, NeobrutalistTheme.dpToPx(activity, 12f), 0, NeobrutalistTheme.dpToPx(activity, 12f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                marginEnd = NeobrutalistTheme.dpToPx(activity, 10f)
            }
            setOnClickListener {
                AppUpdateManager.hasDismissedUpdateThisSession = true
                dialog.dismiss()
            }
        }
        buttonRow.addView(btnLater)

        // UPDATE NOW Button
        val btnUpdate = Button(activity).apply {
            text = "UPDATE NOW"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.card(
                activity,
                fillColor = NeobrutalistTheme.COLOR_PRIMARY,
                cornerRadiusDp = 10f,
                shadowOffsetDp = 3f,
                strokeWidthDp = 2f
            )
            setPadding(0, NeobrutalistTheme.dpToPx(activity, 12f), 0, NeobrutalistTheme.dpToPx(activity, 12f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setOnClickListener {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(browserIntent)
                } catch (_: Exception) {}
                dialog.dismiss()
            }
        }
        buttonRow.addView(btnUpdate)

        dialog.setOnCancelListener {
            AppUpdateManager.hasDismissedUpdateThisSession = true
        }

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.show()
    }
}
