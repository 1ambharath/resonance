package com.br.resonance

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Neobrutalist Dialog displaying connection instructions, dynamic QR code,
 * and direct URL for pairing an iOS device (or laptop/tablet) via Mobile Safari.
 */
object IosConnectDialogHelper {

    fun showDialog(
        activity: Activity,
        port: Int = 8080,
        connectedWebClients: Int = 0
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val localIp = getLocalIpAddress()
        val serverUrl = "http://$localIp:$port"

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = FrameLayout(activity).apply {
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 16f),
                NeobrutalistTheme.dpToPx(activity, 16f),
                NeobrutalistTheme.dpToPx(activity, 16f),
                NeobrutalistTheme.dpToPx(activity, 16f)
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

        // Top Golden Accent Strip
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

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
        }
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 20f),
                NeobrutalistTheme.dpToPx(activity, 16f),
                NeobrutalistTheme.dpToPx(activity, 20f),
                NeobrutalistTheme.dpToPx(activity, 20f)
            )
        }
        scroll.addView(contentLayout)

        // Header Row: Badge + Close [✕]
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 8f))
        }
        contentLayout.addView(headerRow)

        val badge = TextView(activity).apply {
            text = "📱 iOS / SAFARI LINK"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            background = NeobrutalistTheme.pill(
                activity,
                fillColor = Color.parseColor("#FEF3C7"),
                shadowOffsetDp = 2f
            )
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 10f),
                NeobrutalistTheme.dpToPx(activity, 4f),
                NeobrutalistTheme.dpToPx(activity, 10f),
                NeobrutalistTheme.dpToPx(activity, 4f)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(badge)

        val closeBtn = TextView(activity).apply {
            text = "✕"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            val btnSize = NeobrutalistTheme.dpToPx(activity, 30f)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = NeobrutalistTheme.card(
                activity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 8f,
                shadowOffsetDp = 2f,
                strokeWidthDp = 1.5f
            )
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)

        // Title: Connect iOS Device
        val title = TextView(activity).apply {
            text = NeobrutalistTheme.createEditorialTitle("Connect", "iOS")
            textSize = 24f
            typeface = Typeface.SERIF
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 2f))
        }
        contentLayout.addView(title)

        val subtitle = TextView(activity).apply {
            text = "ZERO-INSTALL AUDIO LINK FOR IPHONE & IPAD"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 14f))
        }
        contentLayout.addView(subtitle)

        // QR Code Box
        val qrBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = NeobrutalistTheme.card(
                activity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 14f,
                shadowOffsetDp = 4f,
                strokeWidthDp = 2f
            )
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 12f),
                NeobrutalistTheme.dpToPx(activity, 12f),
                NeobrutalistTheme.dpToPx(activity, 12f),
                NeobrutalistTheme.dpToPx(activity, 12f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(activity, 14f)
            }
        }

        val qrImageView = ImageView(activity).apply {
            val qrSize = NeobrutalistTheme.dpToPx(activity, 190f)
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val qrBitmap = QrCodeGenerator.generate(serverUrl, 500, 500)
            if (qrBitmap != null) {
                setImageBitmap(qrBitmap)
            }
            contentDescription = "QR Code for iOS Safari Connection"
        }
        qrBox.addView(qrImageView)

        val qrCaption = TextView(activity).apply {
            text = "Scan with iPhone Camera to Open"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
            setPadding(0, NeobrutalistTheme.dpToPx(activity, 6f), 0, 0)
        }
        qrBox.addView(qrCaption)
        contentLayout.addView(qrBox)

        // URL Pill & Copy Link Row
        val urlRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NeobrutalistTheme.card(
                activity,
                fillColor = Color.parseColor("#F5F0E8"),
                cornerRadiusDp = 10f,
                shadowOffsetDp = 2f,
                strokeWidthDp = 1.5f
            )
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 12f),
                NeobrutalistTheme.dpToPx(activity, 8f),
                NeobrutalistTheme.dpToPx(activity, 8f),
                NeobrutalistTheme.dpToPx(activity, 8f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(activity, 16f)
            }
        }

        val urlText = TextView(activity).apply {
            text = serverUrl
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        urlRow.addView(urlText)

        val copyBtn = Button(activity).apply {
            text = "Copy"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(
                activity,
                bgColor = NeobrutalistTheme.COLOR_SECONDARY,
                shadowOffsetDp = 2f
            )
            val btnHeight = NeobrutalistTheme.dpToPx(activity, 32f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                btnHeight
            )
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Resonance Web URL", serverUrl))
                Toast.makeText(activity, "URL copied: $serverUrl", Toast.LENGTH_SHORT).show()
            }
        }
        urlRow.addView(copyBtn)
        contentLayout.addView(urlRow)

        // Step-by-Step Instructions Card
        val instructionsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = NeobrutalistTheme.card(
                activity,
                fillColor = Color.WHITE,
                cornerRadiusDp = 12f,
                shadowOffsetDp = 3f,
                strokeWidthDp = 1.5f
            )
            setPadding(
                NeobrutalistTheme.dpToPx(activity, 14f),
                NeobrutalistTheme.dpToPx(activity, 12f),
                NeobrutalistTheme.dpToPx(activity, 14f),
                NeobrutalistTheme.dpToPx(activity, 12f)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = NeobrutalistTheme.dpToPx(activity, 16f)
            }
        }

        val instTitle = TextView(activity).apply {
            text = "QUICK SETUP GUIDE"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 8f))
        }
        instructionsCard.addView(instTitle)

        instructionsCard.addView(createInstructionStep(activity, "1", "Connect to Same Wi-Fi or Hotspot", "Ensure both devices are on the same Wi-Fi, or connect iPhone to this phone's Personal Hotspot."))
        instructionsCard.addView(createInstructionStep(activity, "2", "Scan with iPhone Camera", "Open native iOS Camera and tap the yellow link to launch the Resonance Web Receiver."))
        instructionsCard.addView(createInstructionStep(activity, "3", "Tap 'Start Listening'", "Safari will output live audio to AirPods/headphones (ensure iPad Silent Mode is OFF in Control Center)."))
        instructionsCard.addView(createInstructionStep(activity, "4", "Calibrate Delay Slider", "Use the -500 to +500 ms sync offset slider on iOS / Web to align the audio between both listeners."))

        contentLayout.addView(instructionsCard)

        // Server Status Row
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 14f))
        }

        val serverIndicator = TextView(activity).apply {
            val clientStr = if (connectedWebClients > 0) "$connectedWebClients web receiver(s) connected" else "Listening on Port $port"
            text = "⚡ Web Relay: $clientStr"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (connectedWebClients > 0) Color.parseColor("#15803D") else NeobrutalistTheme.COLOR_MUTED)
        }
        statusRow.addView(serverIndicator)
        contentLayout.addView(statusRow)

        // Dismiss Action Button
        val doneBtn = Button(activity).apply {
            text = "GOT IT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            background = NeobrutalistTheme.button(
                activity,
                bgColor = NeobrutalistTheme.COLOR_PRIMARY,
                shadowOffsetDp = 4f
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                NeobrutalistTheme.dpToPx(activity, 48f)
            )
            setOnClickListener { dialog.dismiss() }
        }
        contentLayout.addView(doneBtn)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun createInstructionStep(activity: Context, number: String, headline: String, detail: String): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, NeobrutalistTheme.dpToPx(activity, 8f))
        }

        val numBadge = TextView(activity).apply {
            text = number
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val size = NeobrutalistTheme.dpToPx(activity, 18f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = NeobrutalistTheme.dpToPx(activity, 8f)
                topMargin = NeobrutalistTheme.dpToPx(activity, 1f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(NeobrutalistTheme.COLOR_BORDER)
            }
        }
        row.addView(numBadge)

        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val head = TextView(activity).apply {
            text = headline
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(NeobrutalistTheme.COLOR_ON_SURFACE)
        }
        textCol.addView(head)

        val body = TextView(activity).apply {
            text = detail
            textSize = 10f
            setTextColor(NeobrutalistTheme.COLOR_MUTED)
        }
        textCol.addView(body)

        row.addView(textCol)
        return row
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host != "127.0.0.1") return host
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }
}
