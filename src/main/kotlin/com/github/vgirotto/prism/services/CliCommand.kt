package com.github.vgirotto.prism.services

/** A CLI executable resolved to an absolute path plus its literal arguments. */
data class ResolvedCliCommand(
    val executable: String,
    val arguments: List<String>,
)

/**
 * Parses the command configured in Settings into literal command-line tokens.
 *
 * This deliberately implements only word splitting and quoting; it never evaluates shell
 * syntax. The eventual PTY command quotes every returned token independently, so a setting
 * such as `claude --plugin-dir "/my plugins/local"` is safe and retains its arguments.
 */
internal object CliCommandParser {

    sealed interface Result {
        data class Success(val executable: String, val arguments: List<String>) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(value: String, defaultExecutable: String): Result {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var tokenStarted = false

        fun finishToken() {
            if (tokenStarted) {
                tokens += token.toString()
                token.clear()
                tokenStarted = false
            }
        }

        for (character in value) {
            if (escaped) {
                token.append(character)
                tokenStarted = true
                escaped = false
                continue
            }

            when (quote) {
                '\'' -> when (character) {
                    '\'' -> quote = null
                    else -> token.append(character)
                }
                '"' -> when (character) {
                    '"' -> quote = null
                    '\\' -> escaped = true
                    else -> token.append(character)
                }
                null -> when {
                    character == '\\' -> {
                        escaped = true
                        tokenStarted = true
                    }
                    character == '\'' || character == '"' -> {
                        quote = character
                        tokenStarted = true
                    }
                    character.isWhitespace() -> finishToken()
                    else -> {
                        token.append(character)
                        tokenStarted = true
                    }
                }
                else -> error("Unsupported quote state")
            }
        }

        if (escaped) return Result.Invalid("Command ends with an escape character")
        if (quote != null) return Result.Invalid("Command contains an unclosed quote")
        finishToken()

        val executable = tokens.firstOrNull() ?: defaultExecutable
        return Result.Success(executable, tokens.drop(1))
    }
}
