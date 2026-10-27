package com.claudeauto

/** Turns Claude's Markdown into something that sounds right through a car speaker. */
object Speech {
    private const val MAX_SPOKEN_CHARS = 1200

    fun fromMarkdown(markdown: String, maxChars: Int = MAX_SPOKEN_CHARS): String {
        var text = markdown
        // Code is useless read aloud; say that it exists instead.
        text = text.replace(Regex("```[\\s\\S]*?```"), " (code block omitted) ")
        text = text.replace(Regex("`([^`]+)`"), "$1")
        // Links and images: keep the label, drop the URL.
        text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("https?://\\S+"), "a link")
        // Headings, emphasis, quotes, rules, table pipes.
        text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")
        text = text.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
        text = text.replace(Regex("(^|\\W)[*_]([^*_\\n]+)[*_](?=\\W|$)"), "$1$2")
        text = text.replace(Regex("(?m)^>\\s?"), "")
        text = text.replace(Regex("(?m)^\\s*([-*_]\\s*){3,}$"), "")
        text = text.replace(Regex("(?m)^\\s*\\|?[-:\\s|]+\\|[-:\\s|]*$"), "")
        text = text.replace("|", ", ")
        // List markers become sentence breaks so TTS pauses between items.
        text = text.replace(Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+(.*)$")) { match ->
            val item = match.groupValues[1]
            if (item.trim().matches(Regex(".*[.!?:]$"))) item else "$item."
        }
        text = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*"), " ").trim()

        if (text.length > maxChars) {
            val cut = text.take(maxChars)
            val lastStop = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "))
            text = (if (lastStop > maxChars / 2) cut.take(lastStop + 1) else cut) + " The rest is on your phone."
        }
        return text
    }

    /** One short phrase describing a tool call, for progress updates and approval prompts. */
    fun describeTool(name: String, input: Map<String, Any?>): String {
        fun str(key: String) = input[key] as? String ?: ""
        fun file(path: String) = path.split('/').lastOrNull { it.isNotEmpty() } ?: path
        fun clip(s: String, n: Int = 80) = if (s.length > n) s.take(n) + "…" else s

        return when (name.lowercase()) {
            "bash" -> "run the command ${clip(str("command"))}"
            "edit", "multiedit" -> "edit ${file(str("file_path"))}"
            "write" -> "write ${file(str("file_path"))}"
            "read" -> "read ${file(str("file_path"))}"
            "glob", "grep" -> "search for ${clip(str("pattern"), 40)}"
            "web_fetch", "webfetch" -> "fetch a web page"
            "web_search", "websearch" -> "search the web for ${clip(str("query"), 60)}"
            else -> "use $name"
        }
    }
}
