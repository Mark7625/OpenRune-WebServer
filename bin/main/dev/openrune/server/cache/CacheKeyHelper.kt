package dev.openrune.server.cache

import dev.openrune.server.BaseQueryParams
import dev.openrune.server.endpoints.objects.ObjectQueryParams
import dev.openrune.server.endpoints.items.ItemQueryParams
import dev.openrune.server.endpoints.sprites.SpriteQueryParams
import dev.openrune.server.endpoints.gamevals.GameValQueryParams

object CacheKeyHelper {
    fun generateKey(params: BaseQueryParams, prefix: String = ""): String {
        val parts = mutableListOf<String>()
        if (prefix.isNotEmpty()) {
            parts.add(prefix)
        }
        
        params.id?.let { parts.add("id:$it") }
        params.gameVal?.let { parts.add("gameVal:$it") }
        params.idRange?.let { parts.add("idRange:${it.first}..${it.last}") }
        params.limit?.let { parts.add("limit:$it") }
        params.searchMode?.let { parts.add("searchMode:$it") }
        params.q?.let { parts.add("q:$it") }
        
        // Add custom parameters for ObjectQueryParams
        if (params is ObjectQueryParams) {
            params.interactiveOnly?.let { parts.add("interactiveOnly:$it") }
            params.filterNulls?.let { parts.add("filterNulls:$it") }
        }
        
        // Add custom parameters for ItemQueryParams
        if (params is ItemQueryParams) {
            params.notedOnly?.let { parts.add("notedOnly:$it") }
            params.filterNulls?.let { parts.add("filterNulls:$it") }
        }
        
        // Add custom parameters for SpriteQueryParams
        if (params is SpriteQueryParams) {
            params.width?.let { parts.add("width:$it") }
            params.height?.let { parts.add("height:$it") }
            if (!params.keepAspectRatio) {
                parts.add("keepAspectRatio:false")
            }
            params.indexed?.let { parts.add("indexed:$it") }
        }
        
        // Add custom parameters for GameValQueryParams
        if (params is GameValQueryParams) {
            params.type?.let { parts.add("type:$it") }
        }
        
        return parts.joinToString("&")
    }
}
