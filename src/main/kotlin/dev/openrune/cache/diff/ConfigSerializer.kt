package dev.openrune.cache.diff

import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.cache.gameval.GameValHandler.lookupAs
import dev.openrune.cache.gameval.impl.Interface
import dev.openrune.definition.EntityOpsDefinition
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.EnumType
import dev.openrune.definition.type.ParamType
import dev.openrune.definition.type.VarClanType
import dev.openrune.definition.util.CacheVarLiteral
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// ── Reflection cache ──────────────────────────────────────────────────────────

private data class PropAccessor(val name: String, val getter: Method)

private val accessorCache = mutableMapOf<Class<*>, List<PropAccessor>>()
private val defaultInstanceCache = mutableMapOf<Class<*>, Any?>()

private fun propAccessors(clazz: Class<*>): List<PropAccessor> {
    synchronized(accessorCache) {
        accessorCache[clazz]?.let { return it }
        val getters = clazz.methods
            .asSequence()
            .filter { it.parameterCount == 0 }
            .mapNotNull { m ->
                val name = when {
                    m.name.startsWith("get") && m.name.length > 3 ->
                        m.name[3].lowercaseChar() + m.name.drop(4)
                    m.name.startsWith("is") && m.name.length > 2 ->
                        m.name[2].lowercaseChar() + m.name.drop(3)
                    else -> null
                }?.takeUnless { it == "class" } ?: return@mapNotNull null
                name to m
            }
            .toMap()

        val ordered = linkedSetOf<String>()
        clazz.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .mapTo(ordered) { it.name }
        getters.keys.forEach(ordered::add)

        val result = ordered.mapNotNull { name -> getters[name]?.let { PropAccessor(name, it) } }
        accessorCache[clazz] = result
        return result
    }
}

private fun defaultInstance(clazz: Class<*>): Any? {
    synchronized(defaultInstanceCache) {
        if (defaultInstanceCache.containsKey(clazz)) return defaultInstanceCache[clazz]
        val instance = try {
            val ctor = clazz.declaredConstructors.find { it.parameterCount == 0 } ?: return null
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (_: Exception) { null }
        defaultInstanceCache[clazz] = instance
        return instance
    }
}

// ── Gameval literal → group mapping (same as the old DiffBlockBuilder) ────────

private val literalToGroup = mapOf(
    CacheVarLiteral.OBJ       to GameValGroupTypes.OBJTYPES,
    CacheVarLiteral.NPC       to GameValGroupTypes.NPCTYPES,
    CacheVarLiteral.LOC       to GameValGroupTypes.LOCTYPES,
    CacheVarLiteral.SEQ       to GameValGroupTypes.SEQTYPES,
    CacheVarLiteral.SPOTANIM  to GameValGroupTypes.SPOTTYPES,
    CacheVarLiteral.INV       to GameValGroupTypes.INVTYPES,
    CacheVarLiteral.STRUCT    to GameValGroupTypes.ROWTYPES,
    CacheVarLiteral.DBROW     to GameValGroupTypes.ROWTYPES,
    CacheVarLiteral.DBTABLE   to GameValGroupTypes.TABLETYPES,
    CacheVarLiteral.MIDI      to GameValGroupTypes.SOUNDTYPES,
    CacheVarLiteral.GRAPHIC   to GameValGroupTypes.SPRITETYPES,
    CacheVarLiteral.INTERFACE to GameValGroupTypes.IFTYPES,
    CacheVarLiteral.COMPONENT to GameValGroupTypes.IFTYPES,
)


@Suppress("UNCHECKED_CAST")
private fun toGamevalNameList(
    group: GameValGroupTypes,
    list: List<*>,
    gamevals: Map<GameValGroupTypes, List<*>>,
): List<String> {
    val allInts = list.all { it is Int }
    if (!allInts) return list.map { it?.toString() ?: "" }
    val typed = list as List<Int>
    val source = gamevals[group] as? List<dev.openrune.cache.gameval.GameValElement>
    return typed.map { id ->
        if (id < 0) return@map id.toString()
        val name = source?.lookup(id)?.name
        if (name != null) "${group.groupName}.$name" else id.toString()
    }
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Converts cache definition objects into typed [DefinitionSnapshot] maps.
 * Uses reflection in declaration-field order (same ordering as the old DiffBlockBuilder).
 * Only non-default fields are included in the output.
 */
object ConfigSerializer {

    /** Serialize every entry in [map] for [diffType]. */
    fun <T> serializeAll(
        diffType: ConfigDiffType<T>,
        map: Map<Int, T>,
        gamevals: Map<GameValGroupTypes, List<*>>,
        paramTypes: Map<Int, ParamType>,
    ): ConfigTypeSnapshot = map.mapValues { (_, def) ->
        serialize(diffType, def, gamevals, paramTypes)
    }

    /** Serialize a single definition. */
    @Suppress("UNCHECKED_CAST")
    fun <T> serialize(
        diffType: ConfigDiffType<T>,
        definition: T,
        gamevals: Map<GameValGroupTypes, List<*>>,
        paramTypes: Map<Int, ParamType>,
    ): DefinitionSnapshot {
        val clazz = (definition as Any)::class.java
        val defaultInst = defaultInstance(clazz)
        val setup = diffType.typeSetup
        val result = linkedMapOf<String, FieldEntry>()

        for (acc in propAccessors(clazz)) {
            val propName = acc.name
            if (propName == "id" || propName in setup.ignoredProps) continue
            if (clazz == EnumType::class.java && propName in setOf("keyType", "valueType", "defaultInt", "defaultString", "values")) continue

            val value = try { acc.getter.invoke(definition) } catch (_: Exception) { continue }
            val default = defaultInst?.let { try { acc.getter.invoke(it) } catch (_: Exception) { null } }
            if (value == default) continue

            val outputKey = setup.customKeys[propName] ?: propName
            
            // For ParamType "type" field, extract just the literal "name" property.
            val entryValue = if (clazz == ParamType::class.java && propName == "type" && value != null) {
                try {
                    value::class.java.getMethod("getName").invoke(value)
                } catch (_: Exception) { value }
            } else value
            
            val gamevalGroup = setup.gamevalBindings[propName]
            result[outputKey] = buildEntry(outputKey, entryValue, gamevalGroup, gamevals, paramTypes)
        }

        if (clazz == ParamType::class.java && definition is ParamType) {
            if (definition.defaultInt != 0) {
                result["defaultInt"] = buildParamEntry(definition.id, definition.defaultInt, gamevals, paramTypes)
            }
            if (definition.defaultString != null) {
                result["defaultString"] = FieldEntry(definition.defaultString)
            }
            if (definition.defaultLong != 0L) {
                result["defaultLong"] = buildParamEntry(definition.id, definition.defaultLong.toInt(), gamevals, paramTypes)
            }
        }

        if (clazz == VarClanType::class.java && definition is VarClanType) {
            result["type"] = FieldEntry(definition.type?.name)
        }

        if (clazz == EnumType::class.java && definition is EnumType) {
            result["key"] = FieldEntry(definition.keyType.name)
            result["value"] = FieldEntry(definition.valueType.name)
            result["default"] = buildEnumDefaultEntry(definition, gamevals)
            result["valuesCount"] = FieldEntry(definition.values.size)

            if (definition.values.isNotEmpty()) {
                val enumValues = definition.values.entries.associate { (k, v) ->
                    renderEnumTypedKey(definition.keyType, k, gamevals) to buildEnumTypedEntry(definition.valueType, v, gamevals)
                }
                result["values"] = FieldEntry(enumValues)
            }
        }

        return result
    }

    // ── Field entry construction ──────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun buildEntry(
        key: String,
        value: Any?,
        gamevalGroup: GameValGroupTypes?,
        gamevals: Map<GameValGroupTypes, List<*>>,
        paramTypes: Map<Int, ParamType>,
    ): FieldEntry {
        if (value == null) return FieldEntry(null)
        val effectiveGroup = gamevalGroup

        // EntityOpsDefinition → structured map (no gameval refs needed at this level)
        if (value is EntityOpsDefinition) return FieldEntry(serializeOps(value, gamevals))

        // Params map (Map<Int, Any?>) → preserve gameval refs per param
        if (key == "params" && value is Map<*, *>) {
            val params: Map<Int, FieldEntry> = value.entries
                .mapNotNull { (k, v) -> (k as? Int)?.let { k to buildParamEntry(k, v, gamevals, paramTypes) } }
                .toMap()
            return FieldEntry(params)
        }

        // List / array types → convert to typed lists of primitives
        if (value is List<*>) {
            val normalized = normalizeList(value)
            val withNames = if (effectiveGroup != null) toGamevalNameList(effectiveGroup, normalized, gamevals) else normalized
            return FieldEntry(withNames)
        }
        if (value is IntArray) {
            val normalized = value.toList()
            val withNames = if (effectiveGroup != null) toGamevalNameList(effectiveGroup, normalized, gamevals) else normalized
            return FieldEntry(withNames)
        }
        if (value is LongArray) {
            val normalized = value.toList()
            return FieldEntry(normalized)
        }
        if (value is Array<*>) return FieldEntry(value.map { normalizeAnyValue(it) })

        // Scalar with optional gameval ref
        val ref = if (effectiveGroup != null && value is Int) resolveRef(effectiveGroup, value, gamevals) else null
        return FieldEntry(value, ref)
    }

    private fun normalizeList(list: List<*>): List<*> = when {
        list.all { it is Int }    -> list.map { it as Int }
        list.all { it is Long }   -> list.map { it as Long }
        list.all { it is String } -> list.map { it as String }
        else                      -> list.map { normalizeAnyValue(it) }
    }

    private fun normalizeAnyValue(value: Any?): Any? = when (value) {
        null -> null
        is Int, is Long, is String, is Boolean, is Double, is Float -> value
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is Array<*> -> value.map { normalizeAnyValue(it) }
        is List<*> -> value.map { normalizeAnyValue(it) }
        else -> value.toString()
    }

    private fun buildParamEntry(
        paramId: Int,
        value: Any?,
        gamevals: Map<GameValGroupTypes, List<*>>,
        paramTypes: Map<Int, ParamType>,
    ): FieldEntry {
        val param = paramTypes[paramId]
        if (param?.type != null && value is Int) {
            if (param.type == CacheVarLiteral.COMPONENT) {
                val interfaceId = (value ushr 16) and 0xFFFF
                val componentId = value and 0xFFFF
                @Suppress("UNCHECKED_CAST")
                val name = (gamevals[GameValGroupTypes.IFTYPES] as? List<dev.openrune.cache.gameval.GameValElement>)
                    ?.lookupAs<Interface>(interfaceId)
                    ?.components
                    ?.firstOrNull { it.id == componentId }
                    ?.name
                if (name != null)
                    return FieldEntry(value, GamevalRef(GameValGroupTypes.IFTYPES.groupName, value, name))
            } else {
                val group = literalToGroup[param.type]
                if (group != null) {
                    val ref = resolveRef(group, value, gamevals)
                    if (ref != null) return FieldEntry(value, ref)
                }
            }
        }
        return FieldEntry(value)
    }

    private fun buildEnumDefaultEntry(
        definition: EnumType,
        gamevals: Map<GameValGroupTypes, List<*>>,
    ): FieldEntry {
        if (definition.valueType == CacheVarLiteral.STRING) {
            val text = definition.defaultString.trim()
            return if (text.isEmpty()) FieldEntry(null) else FieldEntry(definition.defaultString)
        }
        if (definition.defaultInt == 0) return FieldEntry(null)
        return buildEnumTypedEntry(definition.valueType, definition.defaultInt, gamevals)
    }

    private fun buildEnumTypedEntry(
        literal: CacheVarLiteral,
        value: Any?,
        gamevals: Map<GameValGroupTypes, List<*>>,
    ): FieldEntry {
        if (value !is Int) return FieldEntry(value)

        if (literal == CacheVarLiteral.COMPONENT) {
            val interfaceId = (value ushr 16) and 0xFFFF
            val componentId = value and 0xFFFF
            @Suppress("UNCHECKED_CAST")
            val name = (gamevals[GameValGroupTypes.IFTYPES] as? List<dev.openrune.cache.gameval.GameValElement>)
                ?.lookupAs<Interface>(interfaceId)
                ?.components
                ?.firstOrNull { it.id == componentId }
                ?.name
            if (name != null) {
                return FieldEntry(value, GamevalRef(GameValGroupTypes.IFTYPES.groupName, value, name))
            }
            return FieldEntry(value)
        }

        val group = literalToGroup[literal] ?: return FieldEntry(value)
        val ref = resolveRef(group, value, gamevals)
        return if (ref != null) FieldEntry(value, ref) else FieldEntry(value)
    }

    private fun renderEnumTypedKey(
        literal: CacheVarLiteral,
        key: Int,
        gamevals: Map<GameValGroupTypes, List<*>>,
    ): String {
        if (literal == CacheVarLiteral.COMPONENT) {
            val interfaceId = (key ushr 16) and 0xFFFF
            val componentId = key and 0xFFFF
            @Suppress("UNCHECKED_CAST")
            val name = (gamevals[GameValGroupTypes.IFTYPES] as? List<dev.openrune.cache.gameval.GameValElement>)
                ?.lookupAs<Interface>(interfaceId)
                ?.components
                ?.firstOrNull { it.id == componentId }
                ?.name
            if (name != null) return "components.$name"
            return key.toString()
        }

        val group = literalToGroup[literal] ?: return key.toString()
        val ref = resolveRef(group, key, gamevals) ?: return key.toString()
        return "${ref.group}.${ref.name}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveRef(
        group: GameValGroupTypes,
        id: Int,
        gamevals: Map<GameValGroupTypes, List<*>>,
    ): GamevalRef? {
        val list = gamevals[group] as? List<dev.openrune.cache.gameval.GameValElement> ?: return null
        val name = list.lookup(id)?.name ?: return null
        return GamevalRef(group.groupName, id, name)
    }

    // ── EntityOpsDefinition → plain map ──────────────────────────────────────

    private fun serializeOps(
        ops: EntityOpsDefinition,
        gamevals: Map<GameValGroupTypes, List<*>>,
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        val activeOps = mutableMapOf<String, String>()
        ops.ops.forEachIndexed { i, op -> if (op != null) activeOps[i.toString()] = op.text }
        if (activeOps.isNotEmpty()) result["ops"] = activeOps

        val subOpMap = mutableMapOf<String, List<Map<String, Any>>>()
        ops.subOps.forEachIndexed { i, list ->
            if (list.isNotEmpty())
                subOpMap[i.toString()] = list.sortedBy { it.subID }.map { mapOf("subID" to it.subID, "text" to it.text) }
        }
        if (subOpMap.isNotEmpty()) result["subOps"] = subOpMap

        val conditionalOpMap = mutableMapOf<String, List<Map<String, Any?>>>()
        ops.conditionalOps.forEachIndexed { i, list ->
            if (list.isNotEmpty())
                conditionalOpMap[i.toString()] = list.map { op ->
                    mapOf(
                        "text"   to op.text,
                        "varpID"   to resolveVarRef(GameValGroupTypes.VARPTYPES, op.varpID, gamevals),
                        "varbitID" to resolveVarRef(GameValGroupTypes.VARBITTYPES, op.varbitID, gamevals),
                        "min"    to op.minValue,
                        "max"    to op.maxValue,
                    )
                }
        }
        if (conditionalOpMap.isNotEmpty()) result["conditionalOps"] = conditionalOpMap

        val conditionalSubOpMap = mutableMapOf<String, Map<String, List<Map<String, Any?>>>>()
        ops.conditionalSubOps.forEachIndexed { i, bySubId ->
            if (bySubId.isNotEmpty()) {
                val inner = bySubId.toSortedMap().mapValues { (_, list) ->
                    list.map { op ->
                        mapOf(
                            "text"   to op.text,
                            "varpID"   to resolveVarRef(GameValGroupTypes.VARPTYPES, op.varpID, gamevals),
                            "varbitID" to resolveVarRef(GameValGroupTypes.VARBITTYPES, op.varbitID, gamevals),
                            "min"    to op.minValue,
                            "max"    to op.maxValue,
                        )
                    }
                }.mapKeys { (k, _) -> k.toString() }
                conditionalSubOpMap[i.toString()] = inner
            }
        }
        if (conditionalSubOpMap.isNotEmpty()) result["conditionalSubOps"] = conditionalSubOpMap

        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveVarRef(
        group: GameValGroupTypes,
        id: Int,
        gamevals: Map<GameValGroupTypes, List<*>>,
    ): String {
        val list = gamevals[group] as? List<dev.openrune.cache.gameval.GameValElement> ?: return "${group.groupName}=$id"
        val name = list.lookup(id)?.name ?: return "${group.groupName}=$id"
        return "${group.groupName}.$name"
    }
}
