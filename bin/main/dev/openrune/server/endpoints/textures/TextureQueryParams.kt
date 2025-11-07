package dev.openrune.server.endpoints.textures

import dev.openrune.server.BaseQueryParams
import dev.openrune.server.SearchMode

data class TextureQueryParams(
    override val id: Int? = null,
    override val gameVal: String? = null,
    override val idRange: IntRange? = null,
    override val limit: Int? = null,
    override val searchMode: SearchMode? = null,
    override val q: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val keepAspectRatio: Boolean = true
) : BaseQueryParams()




