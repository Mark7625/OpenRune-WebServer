package dev.openrune.cache.osrs

import dev.openrune.cache.CacheIndex

/**
 * OSRS (Old School RuneScape) cache indices
 */
enum class OsrsCacheIndex(
    override val id: Int,
    override val displayName: String,
    override val shouldChecksum: Boolean = true,
    override val archives: Set<Int>? = null
) : CacheIndex {
    ANIMATIONS(0, "Animations", false),
    SKELETONS(1, "Skeletons", false),
    CONFIGS(2, "Configs", true, OsrsConfigsArchive.getArchivesToCrc()),
    INTERFACES(3, "Interfaces", false),
    SOUNDEFFECTS(4, "Sound Effects", false),
    MAPS(5, "Maps", true),
    MUSIC_TRACKS(6, "Music Tracks", false),
    MODELS(7, "Models", true),
    SPRITES(8, "Sprites", true),
    TEXTURES(9, "Textures", true),
    BINARY(10, "Binary", false),
    MUSIC_JINGLES(11, "Music Jingles", false),
    CLIENTSCRIPT(12, "Client Script", false),
    FONTS(13, "Fonts", false),
    MUSIC_SAMPLES(14, "Music Samples", false),
    MUSIC_PATCHES(15, "Music Patches", false),
    WORLDMAP_OLD(16, "Worldmap Old", false),
    ARCHIVE_17(17, "Archive 17", false),
    WORLDMAP_GEOGRAPHY(18, "Worldmap Geography", false),
    WORLDMAP(19, "Worldmap", false),
    WORLDMAP_GROUND(20, "Worldmap Ground", false),
    DBTABLEINDEX(21, "DB Table Index", false),
    ANIMAYAS(22, "Animayas", false),
    INTERFACES2(23, "Interfaces 2", false),
    GAMEVALS(24, "Game Vals", true),
    MODELSRT7(25, "Models RT7", false);

    companion object {
        /**
         * Get all OSRS cache indices that should be CRC'd (where shouldChecksum = true)
         */
        fun getIndicesToCrc(): Set<Int> {
            return entries.filter { it.shouldChecksum }.map { it.id }.toSet()
        }

        /**
         * Get indices to CRC based on a set of enum values
         */
        fun getIndicesToCrc(indices: Set<OsrsCacheIndex>): Set<Int> {
            return indices.filter { it.shouldChecksum }.map { it.id }.toSet()
        }
    }
}
