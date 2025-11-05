package dev.openrune.server.endpoints.gamevals

import dev.openrune.server.BaseQueryParams
import dev.openrune.server.ParamDescription
import dev.openrune.server.SearchMode

/**
 * Query parameters specific to gameval endpoints
 */
data class GameValQueryParams(
    override val id: Int? = null,
    @ParamDescription("GameVal type (e.g., SPRITETYPES, TABLETYPES, IFTYPES)")
    val type: String? = null,
    override val gameVal: String? = null,
    override val idRange: IntRange? = null,
    override val limit: Int? = null,
    override val searchMode: SearchMode?,
    override val q: String?
) : BaseQueryParams()

