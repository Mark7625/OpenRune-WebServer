package dev.openrune.server.endpoints.sprites

import dev.openrune.server.BaseQueryParams
import dev.openrune.server.ParamDescription
import dev.openrune.server.SearchMode

/**
 * Query parameters specific to sprite endpoints
 */
data class SpriteQueryParams(
    override val id: Int? = null,
    override val gameVal: String? = null,
    override val idRange: IntRange? = null,
    override val limit: Int? = null,
    override val searchMode: SearchMode? = null,
    override val q: String? = null,
    
    @ParamDescription("Target width for image resizing")
    val width: Int? = null,
    
    @ParamDescription("Target height for image resizing")
    val height: Int? = null,
    
    @ParamDescription("Whether to preserve aspect ratio when resizing")
    val keepAspectRatio: Boolean = true,
    
    @ParamDescription("Indexed sprite frame (0-based) for multi-frame sprites")
    val indexed: Int? = null
) : BaseQueryParams()

