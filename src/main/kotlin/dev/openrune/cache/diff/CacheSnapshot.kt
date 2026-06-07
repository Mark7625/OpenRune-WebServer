package dev.openrune.cache.diff

data class GamevalRef(val group: String, val id: Int, val name: String)

data class FieldEntry(val value: Any?, val ref: GamevalRef? = null)

typealias DefinitionSnapshot = Map<String, FieldEntry>

typealias ConfigTypeSnapshot = Map<Int, DefinitionSnapshot>
