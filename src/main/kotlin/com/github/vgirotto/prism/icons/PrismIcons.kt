package com.github.vgirotto.prism.icons

import com.github.vgirotto.prism.model.AgentCli
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Per-agent icons, so a chat tab says *which* CLI is behind it at a glance rather than only
 * in its title. Both marks are single-hue mid-tones that stay legible on light and dark
 * backgrounds, which is why there are no `_dark` variants to keep in sync.
 *
 * The marks are deliberately generic geometry (a ray burst, a shell prompt) rather than the
 * vendors' logos — Prism is unaffiliated with Anthropic and OpenAI, and says so in its
 * plugin description.
 */
object PrismIcons {

    val CLAUDE: Icon = IconLoader.getIcon("/icons/claude.svg", PrismIcons::class.java)
    val CODEX: Icon = IconLoader.getIcon("/icons/codex.svg", PrismIcons::class.java)

    fun forCli(cli: AgentCli): Icon = when (cli) {
        AgentCli.CLAUDE -> CLAUDE
        AgentCli.CODEX -> CODEX
    }
}
