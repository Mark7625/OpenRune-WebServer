package dev.openrune.cache.diff

data class DiffManifest(
    val revision: Int,
    val sprites: SpriteDiffSummary,
    val configs: Map<String, ConfigDiffSummary>,
    val gamevals: Map<String, ConfigDiffSummary> = emptyMap(),
)

data class SpriteDiffSummary(
    val added: List<Int>,
    val removed: List<Int>,
    val changed: List<Int>
)

data class ConfigDiffSummary(
    val added: List<Int>,
    val removed: List<Int>,
    val changed: List<Int>
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}