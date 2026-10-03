package com.github.vgirotto.prism.toolwindow

import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.StyleSheetUtil
import java.awt.Color
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

internal fun createChangelogEditorKit(textColor: Color, linkColor: Color): HTMLEditorKit {
    val styles = StyleSheet().apply {
        // A replacement root must inherit the IDE rules for strong, em and other tags.
        // Keep custom styles local rather than mutating the shared default sheet.
        addStyleSheet(HTMLEditorKit().styleSheet)
        addStyleSheet(StyleSheetUtil.getDefaultStyleSheet())
        addRule("body { color: ${textColor.toCssColor()}; margin: 0; }")
        addRule("h1 { font-size: 180%; margin-top: 0; margin-bottom: 12px; }")
        addRule("h2 { font-size: 150%; margin-top: 20px; margin-bottom: 10px; }")
        addRule("h3 { font-size: 120%; margin-top: 14px; margin-bottom: 8px; }")
        addRule("p { margin-top: 6px; margin-bottom: 8px; }")
        addRule("ul, ol { margin-left: 22px; margin-top: 6px; margin-bottom: 8px; }")
        addRule("li { margin-bottom: 6px; }")
        addRule("a { color: ${linkColor.toCssColor()}; text-decoration: underline; }")
        addRule("code, pre { font-family: monospace; }")
        addRule("blockquote { margin-left: 16px; }")
    }

    return HTMLEditorKitBuilder()
        .withWordWrapViewFactory()
        .withStyleSheet(styles)
        .build()
}

private fun Color.toCssColor(): String = "#%06x".format(rgb and 0xFFFFFF)
