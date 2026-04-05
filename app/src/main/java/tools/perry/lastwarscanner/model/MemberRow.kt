package tools.perry.lastwarscanner.model

data class MemberRow(
    val name: String,
    val scores: Map<String, Long> = emptyMap(),
    val latestTimestamp: Long = 0
) {
    fun getScore(key: String): Long? = scores[key]
}