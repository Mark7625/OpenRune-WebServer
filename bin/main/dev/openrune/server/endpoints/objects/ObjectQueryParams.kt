package dev.openrune.server.endpoints.objects

import dev.openrune.server.BaseQueryParams
import dev.openrune.server.ParamDescription
import dev.openrune.server.SearchMode

data class ObjectQueryParams(
    override val id: Int? = null,
    override val gameVal: String? = null,
    override val idRange: IntRange? = null,
    override val limit: Int? = null,
    override val searchMode: SearchMode? = null,
    override val q: String? = null,
    @ParamDescription("Filter to show only interactive objects (true/false)")
    val interactiveOnly: Boolean? = null,
    @ParamDescription("Filter out entries with null or 'null' names (true/false)")
    val filterNulls: Boolean? = null
) : BaseQueryParams()
