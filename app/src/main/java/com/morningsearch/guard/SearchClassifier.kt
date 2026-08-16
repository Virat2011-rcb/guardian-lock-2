package com.morningsearch.guard

import com.morningsearch.guard.data.KeywordEntity
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class Detection(val category: String)

object SearchClassifier {
    private data class Term(val value: String, val category: String, val fuzzy: Boolean = true)

    private val bundled = listOf(
        Term("porn", "porn"), Term("pornography", "porn"), Term("porno", "porn"),
        Term("xxx", "porn", false), Term("xvideos", "porn"), Term("x videos", "porn"),
        Term("pornhub", "porn"), Term("porn hub", "porn"), Term("p hub", "porn"),
        Term("xnxx", "porn"), Term("xhamster", "porn"), Term("x hamster", "porn"),
        Term("redtube", "porn"), Term("red tube", "porn"), Term("youporn", "porn"),
        Term("you porn", "porn"), Term("spankbang", "porn"), Term("spank bang", "porn"),
        Term("tube8", "porn"), Term("tnaflix", "porn"), Term("eporner", "porn"),
        Term("hqporner", "porn"), Term("beeg", "porn", false), Term("brazzers", "porn"),
        Term("pornhd", "porn"), Term("porn hd", "porn"), Term("porndude", "porn"),
        Term("porn dude", "porn"), Term("javhd", "porn"), Term("jav hd", "porn"),
        Term("erome", "explicit"), Term("chaturbate", "explicit"), Term("stripchat", "explicit"),
        Term("cam girl", "explicit"), Term("camgirls", "explicit"),
        Term("nude", "nudity"), Term("nudes", "nudity"), Term("nudity", "nudity"),
        Term("naked video", "nudity"), Term("sex video", "explicit"),
        Term("adult video", "explicit"), Term("erotic video", "explicit"),
        Term("hentai", "fetish"), Term("rule 34", "fetish"), Term("onlyfans leak", "explicit"),
        Term("masturbation video", "masturbation"), Term("jerk off video", "masturbation"),
        Term("desi mms", "explicit"), Term("blue film", "explicit"),
        Term("nangi video", "nudity"), Term("nanga video", "nudity"),
        Term("पोर्न", "porn"), Term("अश्लील वीडियो", "explicit"), Term("नग्न वीडियो", "nudity"),
        Term("सेक्स वीडियो", "explicit"), Term("porno video", "porn"),
        Term("video desnudo", "nudity"), Term("contenido sexual", "explicit")
    )

    private val recoverySignals = listOf(
        "quit", "stop", "help", "recovery", "recover", "addiction", "therapy",
        "therapist", "counsellor", "counselor", "doctor", "treatment", "health",
        "side effects", "how to avoid", "कैसे छोड़", "लत", "इलाज", "मदद"
    )

    private val localTerms = AtomicReference<List<Term>>(emptyList())

    fun updateLocalDatabase(items: List<KeywordEntity>) {
        localTerms.set(items.filter { it.enabled }.map { Term(it.normalized, it.category, it.fuzzy) })
    }

    fun classify(raw: String): Detection? {
        val text = normalize(raw)
        if (text.length < 3 || recoverySignals.any { normalize(text).contains(normalize(it)) }) return null
        val tokens = text.split(' ').filter(String::isNotBlank)
        for (term in bundled + localTerms.get()) {
            val needle = normalize(term.value)
            if (containsPhrase(text, needle) || (term.fuzzy && fuzzyTokenMatch(tokens, needle))) {
                return Detection(term.category)
            }
        }
        return null
    }

    fun shouldBlock(raw: String): Boolean = classify(raw) != null

    internal fun normalize(raw: String): String {
        val decoded = runCatching {
            URLDecoder.decode(raw.replace("+", " "), StandardCharsets.UTF_8.name())
        }.getOrDefault(raw)
        return Normalizer.normalize(decoded, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('0', 'o').replace('1', 'i').replace('3', 'e')
            .replace('4', 'a').replace('5', 's').replace('7', 't')
            .replace(Regex("(.)\\1{2,}"), "${'$'}1")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsPhrase(text: String, phrase: String): Boolean =
        phrase.isNotBlank() && (" $text ".contains(" $phrase ") ||
            (phrase.contains(' ') && text.contains(phrase)))

    private fun fuzzyTokenMatch(tokens: List<String>, phrase: String): Boolean {
        if (phrase.contains(' ') || phrase.length < 5) return false
        return tokens.any { token ->
            token.length >= 5 && kotlin.math.abs(token.length - phrase.length) <= 1 &&
                levenshteinWithinOne(token, phrase)
        }
    }

    private fun levenshteinWithinOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }
}
