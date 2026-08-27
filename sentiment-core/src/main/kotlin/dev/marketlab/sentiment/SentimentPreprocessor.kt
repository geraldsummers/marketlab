package dev.marketlab.sentiment

object SentimentPreprocessor {
    const val SPEC =
        "marketlab.sentiment.preprocessing.v1|unicode=NFKC|null=space|whitespace=collapse|" +
            "social.handle=@user|social.url=http|news=normalized|trim=true"
    /**
     * Frozen preprocessing v1.
     *
     * Social handles and URLs use the substitutions prescribed by the
     * CardiffNLP model card. News retains lexical content but shares exact
     * Unicode/whitespace normalization. The implementation identity is locked
     * by `research/sentiment-models.lock.json`.
     */
    fun social(text: String): String =
        normalize(text)
            .split(' ')
            .joinToString(" ") { token ->
                when {
                    token.startsWith("@") && token.length > 1 -> "@user"
                    token.startsWith("http://") || token.startsWith("https://") -> "http"
                    else -> token
                }
            }

    fun news(text: String): String = normalize(text)

    private fun normalize(text: String): String =
        java.text.Normalizer
            .normalize(text, java.text.Normalizer.Form.NFKC)
            .replace('\u0000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
