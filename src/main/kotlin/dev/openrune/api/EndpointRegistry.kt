package dev.openrune.api

/**
 * Self-documentation for the HTTP API, served by `/endpoints/data`, `/api` and `/docs` and
 * rendered by the website's API docs page. Routes register themselves as they are installed.
 */
object EndpointRegistry {

    data class QueryParam(
        val name: String,
        val type: String,
        val required: Boolean,
        val description: String,
        val defaultValue: String? = null,
    )

    data class EndpointInfo(
        val method: String,
        val path: String,
        val description: String,
        val category: String,
        val queryParams: List<QueryParam> = emptyList(),
        val examples: List<String> = emptyList(),
        val responseType: String? = null,
        /** When set, the docs page shows one entry with a dropdown of path segments. */
        val pathOptions: List<String>? = null,
    )

    data class EndpointsData(val baseUrl: String, val endpoints: List<EndpointInfo>)

    private val endpoints = LinkedHashMap<String, EndpointInfo>()

    fun registerEndpoint(
        method: String,
        path: String,
        description: String,
        category: String,
        /** Documented query parameters; null or empty when the docs page just shows the examples. */
        queryParams: List<QueryParam>? = null,
        responseType: String? = null,
        examples: List<String> = emptyList(),
        pathOptions: List<String>? = null,
    ) {
        endpoints["$method $path"] =
            EndpointInfo(method, path, description, category, queryParams.orEmpty(), examples, responseType, pathOptions)
    }

    fun getEndpointsData(baseUrl: String): EndpointsData =
        EndpointsData(baseUrl, endpoints.values.sortedBy { it.category + it.path })
}
