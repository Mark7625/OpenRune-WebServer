package dev.openrune.server

/**
 * Search mode for query params. Used by doc extraction and future endpoints.
 */
enum class SearchMode {
    ID, GAMEVAL, REGEX, NAME
}

/**
 * Base type for documented GET query parameters. Kept for EndpointRegistry / getDocumented.
 */
abstract class BaseQueryParams {
    @ParamDescription("Archive ID of the resource")
    abstract val id: Int?

    @ParamDescription("Lookup resource by name (gameVal field)")
    abstract val gameVal: String?

    @ParamDescription("Filter by ID range. Formats: \"45..49\", \"45-49\", or \"45,49\". Ignored when using searchMode.")
    abstract val idRange: IntRange?

    @ParamDescription("Limit the number of results returned")
    abstract val limit: Int?

    @ParamDescription("Search mode: 'id' (ID ranges like \"45+90\" or single IDs), 'gameval' (comma-separated list, quoted for exact match), 'regex' (regex pattern), or 'name' (case-insensitive substring). When used, idRange is ignored.")
    abstract val searchMode: SearchMode?

    @ParamDescription("Search query string. With searchMode='id': supports ranges (\"45+90\") or comma-separated IDs. With searchMode='gameval': comma-separated list (quoted strings for exact match). With searchMode='regex': regex pattern. With searchMode='name': substring to match.")
    abstract val q: String?

    fun validateIdOrGameVal(): String? {
        if (id != null && gameVal != null) {
            throw IllegalArgumentException("Cannot specify both 'id' and 'gameVal'. Please use only one.")
        }
        return gameVal
    }

    companion object {
        fun parseSearchMode(searchModeStr: String?): SearchMode? {
            return try {
                searchModeStr?.uppercase()?.let { SearchMode.valueOf(it) }
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        fun parseIdRange(rangeStr: String?): IntRange? {
            if (rangeStr == null) return null
            return try {
                when {
                    rangeStr.contains("..") -> {
                        val parts = rangeStr.split("..")
                        if (parts.size == 2) parts[0].trim().toInt()..parts[1].trim().toInt() else null
                    }
                    rangeStr.contains("-") -> {
                        val parts = rangeStr.split("-")
                        if (parts.size == 2) parts[0].trim().toInt()..parts[1].trim().toInt() else null
                    }
                    rangeStr.contains(",") -> {
                        val parts = rangeStr.split(",")
                        if (parts.size == 2) parts[0].trim().toInt()..parts[1].trim().toInt() else null
                    }
                    else -> null
                }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
