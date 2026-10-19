package com.br.resonance

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan

/**
 * Cream Neobrutalist Mobile Design System:
 * - Warm, paper-stock cream canvas (#F5F0E8)
 * - 2px solid ink-black borders (#1A1A1A) on all components
 * - Hard offset drop shadows (no blur): 2px and 4px solid #1A1A1A
 * - Editorial serif headings with signature italic flourish
 * - Heavy uppercase letter-spaced Inter labels
 * - Saturated coral (#E85D3A) primary CTAs, leafy green (#7BB069) success, ink (#1A1A1A) feature cards
 */
object NeobrutalistTheme {
    val COLOR_BACKGROUND = Color.parseColor("#F5F0E8") // Cream canvas
    val COLOR_SURFACE = Color.parseColor("#FFFFFF")    // Pure white card
    val COLOR_BORDER = Color.parseColor("#1A1A1A")     // 2px ink black
    val COLOR_ON_SURFACE = Color.parseColor("#1A1A1A")
    val COLOR_MUTED = Color.parseColor("#6B7280")      // Secondary text
    val COLOR_PRIMARY = Color.parseColor("#E85D3A")    // Saturated Coral
    val COLOR_SECONDARY = Color.parseColor("#1A1A1A")  // Hero ink
    val COLOR_ACCENT = Color.parseColor("#E8B93A")     // Warm Yellow
    val COLOR_SUCCESS = Color.parseColor("#7BB069")    // Leafy Green
    val COLOR_ERROR = Color.parseColor("#EF4444")      // Error Red
    val COLOR_TRACK_MUTED = Color.parseColor("#E5E7EB") // Progress bar track

    fun dpToPx(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun createEditorialTitle(firstWord: String, secondWord: String): CharSequence {
        val ssb = SpannableStringBuilder()
        val span1 = SpannableString("$firstWord ")
        span1.setSpan(StyleSpan(Typeface.BOLD), 0, span1.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.append(span1)

        val span2 = SpannableString(secondWord)
        span2.setSpan(StyleSpan(Typeface.ITALIC), 0, span2.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.append(span2)

        return ssb
    }

    fun card(
        context: Context,
        fillColor: Int = COLOR_SURFACE,
        cornerRadiusDp: Float = 16f,
        shadowOffsetDp: Float = 4f,
        strokeWidthDp: Float = 2f
    ): Drawable {
        val dx = dpToPx(context, shadowOffsetDp)
        val dy = dpToPx(context, shadowOffsetDp)
        val strokeWidth = dpToPx(context, strokeWidthDp)
        val radius = dpToPx(context, cornerRadiusDp).toFloat()

        val shadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(COLOR_BORDER)
        }

        val surface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
            setStroke(strokeWidth, COLOR_BORDER)
        }

        return LayerDrawable(arrayOf(shadow, surface)).apply {
            setLayerInset(0, dx, dy, 0, 0)
            setLayerInset(1, 0, 0, dx, dy)
        }
    }

    fun button(
        context: Context,
        bgColor: Int = COLOR_PRIMARY,
        cornerRadiusDp: Float = 12f,
        shadowOffsetDp: Float = 4f,
        strokeWidthDp: Float = 2f
    ): StateListDrawable {
        val dx = dpToPx(context, shadowOffsetDp)
        val dy = dpToPx(context, shadowOffsetDp)
        val strokeWidth = dpToPx(context, strokeWidthDp)
        val radius = dpToPx(context, cornerRadiusDp).toFloat()

        val normalShadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(COLOR_BORDER)
        }
        val normalSurface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bgColor)
            setStroke(strokeWidth, COLOR_BORDER)
        }
        val normalDrawable = LayerDrawable(arrayOf(normalShadow, normalSurface)).apply {
            setLayerInset(0, dx, dy, 0, 0)
            setLayerInset(1, 0, 0, dx, dy)
        }

        val pDx = dpToPx(context, 1f)
        val pDy = dpToPx(context, 1f)
        val pressedShadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(COLOR_BORDER)
        }
        val pressedSurface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bgColor)
            setStroke(strokeWidth, COLOR_BORDER)
        }
        val pressedDrawable = LayerDrawable(arrayOf(pressedShadow, pressedSurface)).apply {
            setLayerInset(0, dx - pDx, dy - pDy, 0, 0)
            setLayerInset(1, dx - pDx, dy - pDy, pDx, pDy)
        }

        val disabledSurface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(COLOR_TRACK_MUTED)
            setStroke(strokeWidth, Color.parseColor("#9E9E9E"))
        }

        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabledSurface)
            addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
            addState(intArrayOf(), normalDrawable)
        }
    }

    fun pill(
        context: Context,
        fillColor: Int = COLOR_SURFACE,
        shadowOffsetDp: Float = 2f,
        strokeWidthDp: Float = 2f
    ): Drawable {
        return card(
            context = context,
            fillColor = fillColor,
            cornerRadiusDp = 999f,
            shadowOffsetDp = shadowOffsetDp,
            strokeWidthDp = strokeWidthDp
        )
    }

    fun circle(
        context: Context,
        fillColor: Int = COLOR_SURFACE,
        shadowOffsetDp: Float = 2f,
        strokeWidthDp: Float = 2f
    ): Drawable {
        return card(
            context = context,
            fillColor = fillColor,
            cornerRadiusDp = 999f,
            shadowOffsetDp = shadowOffsetDp,
            strokeWidthDp = strokeWidthDp
        )
    }

    fun input(
        context: Context,
        cornerRadiusDp: Float = 12f
    ): Drawable {
        return card(
            context = context,
            fillColor = COLOR_SURFACE,
            cornerRadiusDp = cornerRadiusDp,
            shadowOffsetDp = 2f,
            strokeWidthDp = 2f
        )
    }
}
