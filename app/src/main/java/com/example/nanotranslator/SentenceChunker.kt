package com.example.nanotranslator

/**
 * 음성 인식 중간 결과에서 "이미 끝난 문장"을 골라냅니다.
 * 문장부호(. ? ! 。 ？ ！)가 나오면 그 문장까지 바로 내보내고, 부호 없이 길어지면(영·스페인어)
 * 일정 단어 수마다 끊어서 보냅니다. 이미 보낸 부분은 다시 보내지 않습니다.
 */
class SentenceChunker {
    private var committed = 0

    fun reset() {
        committed = 0
    }

    fun onPartial(text: String, lang: SupportedLanguage): List<String> {
        val spaced = isSpaced(lang)
        val units = toUnits(text, spaced)
        if (units.size < committed) committed = 0 // 새 발화가 시작됨
        val pending = units.drop(committed)
        val lastEnd = pending.indexOfLast { endsSentence(it) }
        val out = mutableListOf<String>()
        when {
            lastEnd >= 0 -> {
                out += splitSentences(pending.take(lastEnd + 1), spaced)
                committed += lastEnd + 1
            }
            spaced && pending.size >= LONG_WORDS -> {
                out += join(pending.take(CUT_WORDS), spaced)
                committed += CUT_WORDS
            }
            !spaced && pending.size >= LONG_CHARS -> {
                out += join(pending.take(CUT_CHARS), spaced)
                committed += CUT_CHARS
            }
        }
        return out.filter { it.isNotBlank() }
    }

    fun onFinal(text: String, lang: SupportedLanguage): List<String> {
        val spaced = isSpaced(lang)
        val units = toUnits(text, spaced)
        val pending = if (committed <= units.size) units.drop(committed) else emptyList()
        committed = 0
        return splitSentences(pending, spaced).filter { it.isNotBlank() }
    }

    private fun isSpaced(lang: SupportedLanguage) =
        lang != SupportedLanguage.JAPANESE && lang != SupportedLanguage.CHINESE

    private fun toUnits(text: String, spaced: Boolean): List<String> =
        if (spaced) text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        else text.trim().filter { !it.isWhitespace() }.map { it.toString() }

    private fun endsSentence(unit: String) = unit.isNotEmpty() && unit.last() in TERMINATORS

    private fun join(units: List<String>, spaced: Boolean) = units.joinToString(if (spaced) " " else "")

    private fun splitSentences(units: List<String>, spaced: Boolean): List<String> {
        val sentences = mutableListOf<String>()
        var current = mutableListOf<String>()
        for (u in units) {
            current.add(u)
            if (endsSentence(u)) {
                sentences += join(current, spaced)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) sentences += join(current, spaced)
        return sentences
    }

    private companion object {
        const val TERMINATORS = ".?!。？！…"
        const val LONG_WORDS = 18
        const val CUT_WORDS = 14
        const val LONG_CHARS = 40
        const val CUT_CHARS = 30
    }
}
