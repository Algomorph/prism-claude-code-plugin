package com.github.vgirotto.prism.toolwindow

import com.intellij.openapi.util.text.StringUtil
import javax.swing.plaf.basic.BasicHTML

/**
 * Text that holds a chat name, made safe for the components that render it. A chat name comes
 * from the agent's terminal title, which any program in the terminal can set, so it must show as
 * the literal text, never as markup:
 *
 *  - A tab label goes to a `JLabel` (`ContentTabLabel`), which renders a text that starts with
 *    `<html>` (in any case) as HTML.
 *  - A tooltip goes to the IDE's tooltip pane, a `JEditorPane` that renders markup in the text
 *    even when it does not start with `<html>`.
 *  - A notification's content is HTML.
 */
internal object ChatNameText {

    /** Invisible, and not whitespace, so Swing no longer finds `<html>` at the start. */
    private const val ZERO_WIDTH_SPACE = "​"

    /** [text] as a tab label that shows it literally. */
    fun label(text: String): String =
        if (BasicHTML.isHTMLString(text)) ZERO_WIDTH_SPACE + text else text

    /** [text] as a tooltip that shows it literally: escaped, and HTML for every renderer alike. */
    fun tooltip(text: String): String = "<html>" + StringUtil.escapeXmlEntities(text) + "</html>"

    /** The notification that chat [name]'s agent process ended, showing [name] literally. */
    fun sessionEndedNotice(name: String): String =
        "Session '${StringUtil.escapeXmlEntities(name)}' ended unexpectedly.\n\nClick 'Restart' to start a new session."
}
