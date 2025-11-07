package dev.openrune.server

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.PipelineContext

/**
 * Extension function to register GET endpoint with auto-documentation
 * This version accepts a handler that already extracts query params
 */
inline fun <reified T : BaseQueryParams> Route.getDocumented(
    path: String,
    category: String,
    description: String,
    responseType: String? = null,
    examples: List<String> = emptyList(),
    crossinline handler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit
) {
    // Register endpoint for documentation
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = path,
        description = description,
        category = category,
        queryParamsClass = T::class,
        responseType = responseType,
        examples = examples
    )
    
    // Register the actual route - pass handler directly to preserve coroutine context
    get(path) {
        handler(this, Unit)
    }
}

/**
 * Extension function to register GET endpoint without query parameters
 */
inline fun Route.getDocumentedNoParams(
    path: String,
    category: String,
    description: String,
    responseType: String? = null,
    examples: List<String> = emptyList(),
    crossinline handler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit
) {
    // Register endpoint for documentation
    EndpointRegistry.registerEndpoint(
        method = "GET",
        path = path,
        description = description,
        category = category,
        queryParamsClass = null,
        responseType = responseType,
        examples = examples
    )
    
    // Register the actual route - pass handler directly to preserve coroutine context
    get(path) {
        handler(this, Unit)
    }
}


