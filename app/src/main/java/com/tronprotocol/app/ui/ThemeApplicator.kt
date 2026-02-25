package com.tronprotocol.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ktheme.utils.ColorUtils
import com.ktheme.core.ThemeEngine
import java.io.File

/**
 * Utility for loading Ktheme themes and applying colors to views.
 * Extracted from MainActivity to be reusable across Fragments.
 */
class ThemeApplicator(private val context: Context) {

    data class ThemeColors(
        val background: Int,
        val surface: Int,
        val surfaceVariant: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val onPrimary: Int,
        val outline: Int,
        val themeName: String = "",
        val themeDescription: String = ""
    )

    /**
     * Load theme colors from assets. Returns null if the theme cannot be loaded.
     */
    fun loadThemeColors(themeId: String, fallbackId: String = DEFAULT_THEME_ID): ThemeColors? {
        return try {
            val engine = ThemeEngine()
            val themeAssetPath = "themes/$themeId.json"
            val themeFile = try {
                File.createTempFile(themeId, ".json", context.cacheDir).apply {
                    outputStream().use { out ->
                        context.assets.open(themeAssetPath).use { it.copyTo(out) }
                    }
                    deleteOnExit()
                }
            } catch (_: Exception) {
                File.createTempFile(fallbackId, ".json", context.cacheDir).apply {
                    outputStream().use { out ->
                        context.assets.open("themes/$fallbackId.json").use { it.copyTo(out) }
                    }
                    deleteOnExit()
                }
            }

            val theme = engine.loadThemeFromFile(themeFile)
            engine.setActiveTheme(theme.metadata.id)
            val activeTheme = engine.getActiveTheme() ?: return null
            val colors = activeTheme.colorScheme

            ThemeColors(
                background = ColorUtils.hexToColorInt(colors.background),
                surface = ColorUtils.hexToColorInt(colors.surface),
                surfaceVariant = ColorUtils.hexToColorInt(colors.surfaceVariant),
                onSurface = ColorUtils.hexToColorInt(colors.onSurface),
                onSurfaceVariant = ColorUtils.hexToColorInt(colors.onSurfaceVariant),
                primary = ColorUtils.hexToColorInt(colors.primary),
                onPrimary = ColorUtils.hexToColorInt(colors.onPrimary),
                outline = ColorUtils.hexToColorInt(colors.outline),
                themeName = activeTheme.metadata.name,
                themeDescription = activeTheme.metadata.description
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to load ktheme: $themeId", t)
            null
        }
    }

    /**
     * Apply theme colors to a MaterialCardView.
     */
    fun applyToCard(card: MaterialCardView, colors: ThemeColors) {
        card.setCardBackgroundColor(colors.surface)
        card.strokeColor = colors.outline
    }

    /**
     * Apply theme colors to a filled MaterialButton.
     */
    fun applyToFilledButton(button: MaterialButton, colors: ThemeColors) {
        button.backgroundTintList = ColorStateList.valueOf(colors.primary)
        button.setTextColor(colors.onPrimary)
    }

    /**
     * Apply theme colors to an outlined MaterialButton.
     */
    fun applyToOutlinedButton(button: MaterialButton, colors: ThemeColors) {
        button.strokeColor = ColorStateList.valueOf(colors.primary)
        button.setTextColor(colors.primary)
    }

    /**
     * Apply theme colors to a text MaterialButton.
     */
    fun applyToTextButton(button: MaterialButton, colors: ThemeColors) {
        button.setTextColor(colors.primary)
    }

    /**
     * Recursively apply text color to all TextViews in a ViewGroup (excluding EditTexts).
     */
    fun applyTextColorToAllTextViews(viewGroup: ViewGroup, color: Int) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is TextView && child !is EditText) {
                child.setTextColor(color)
            } else if (child is ViewGroup) {
                applyTextColorToAllTextViews(child, color)
            }
        }
    }

    /**
     * Apply theme to a root view containing cards, buttons, and text views.
     * Walks the view tree and applies appropriate colors based on view type.
     */
    fun applyToViewTree(root: View, colors: ThemeColors) {
        when (root) {
            is MaterialCardView -> applyToCard(root, colors)
            is MaterialButton -> {
                // Determine button style by checking for outlined style marker
                // (MaterialButton with strokeWidth > 0 is outlined)
                if (root.strokeWidth > 0) {
                    applyToOutlinedButton(root, colors)
                } else {
                    applyToTextButton(root, colors)
                }
            }
            is EditText -> {
                root.setTextColor(colors.onSurface)
                root.setHintTextColor(colors.onSurfaceVariant)
            }
            is TextView -> root.setTextColor(colors.onSurface)
        }

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyToViewTree(root.getChildAt(i), colors)
            }
        }
    }

    companion object {
        private const val TAG = "ThemeApplicator"
        const val DEFAULT_THEME_ID = "navy-gold"
        const val PREF_SELECTED_THEME = "ktheme_selected_theme_id"
    }
}
