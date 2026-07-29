package dev.marketlab.sentiment

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

/**
 * Offline, version-locked language decision. Publisher language tags are
 * accepted only when they explicitly identify English; absent tags are sent
 * through the frozen detector and ambiguous text is rejected.
 */
class FrozenLanguageDetector(
    lock: LockedLanguageDetector,
) {
    private val detector: LanguageDetector

    init {
        val languages = lock.languages.map(Language::valueOf).toTypedArray()
        detector =
            LanguageDetectorBuilder
                .fromLanguages(*languages)
                .withMinimumRelativeDistance(lock.minimumRelativeDistance)
                .build()
    }

    fun acceptsEnglish(
        publisherLanguage: String?,
        text: String,
    ): Boolean {
        if (publisherLanguage != null) {
            return publisherLanguage.substringBefore('-').equals("en", ignoreCase = true)
        }
        return detector.detectLanguageOf(text) == Language.ENGLISH
    }
}
