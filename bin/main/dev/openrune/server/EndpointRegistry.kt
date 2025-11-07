package dev.openrune.server

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

/**
 * Registry for API endpoint documentation
 */
object EndpointRegistry {
    
    data class QueryParam(
        val name: String,
        val type: String,
        val required: Boolean,
        val description: String,
        val defaultValue: String? = null
    )
    
    data class EndpointInfo(
        val method: String,
        val path: String,
        val description: String,
        val category: String,
        val queryParams: List<QueryParam> = emptyList(),
        val examples: List<String> = emptyList(),
        val responseType: String? = null
    )
    
    data class EndpointsData(
        val baseUrl: String,
        val endpoints: List<EndpointInfo>
    )
    
    private val endpoints = mutableListOf<EndpointInfo>()
    
    /**
     * Register an endpoint for documentation
     */
    fun register(endpoint: EndpointInfo) {
        endpoints.add(endpoint)
    }
    
    /**
     * Register an endpoint automatically from Route registration
     */
    fun registerEndpoint(
        method: String,
        path: String,
        description: String,
        category: String,
        queryParamsClass: KClass<*>? = null,
        responseType: String? = null,
        examples: List<String> = emptyList()
    ) {
        val queryParams = queryParamsClass?.let { extractQueryParams(it) } ?: emptyList()
        
        register(
            EndpointInfo(
                method = method,
                path = path,
                description = description,
                category = category,
                queryParams = queryParams,
                examples = examples,
                responseType = responseType
            )
        )
    }
    
    /**
     * Extract query parameters from a data class using reflection
     */
    private fun extractQueryParams(queryParamsClass: KClass<*>): List<QueryParam> {
        val params = mutableListOf<QueryParam>()
        val constructor = queryParamsClass.primaryConstructor ?: return emptyList()
        
        // Create a default instance using all default values by calling constructor with empty map
        // This works because callBy with an empty map uses all default parameter values
        val defaultInstance = try {
            constructor.callBy(emptyMap())
        } catch (e: Exception) {
            null
        }
        
        constructor.parameters.forEach { param ->
            val paramName = param.name ?: return@forEach
            val prop = queryParamsClass.memberProperties.find { it.name == paramName }
                ?: return@forEach
            
            // Get type name, handling nullable types
            val typeName = prop.returnType.toString()
                .replace("kotlin.", "")
                .replace("java.lang.", "")
                .replace("?", "")
                .let { name ->
                    // Simplify common types
                    when {
                        name.contains("Int") && !name.contains("Range") -> "Int"
                        name.contains("IntRange") -> "IntRange"
                        name.contains("Boolean") -> "Boolean"
                        name.contains("String") -> "String"
                        else -> name
                    }
                }
            
            // Check if it's nullable
            val isNullable = param.type.isMarkedNullable
            val hasDefault = param.isOptional
            
            // Determine required status
            // For BaseQueryParams, most are optional except id/gameVal which have special logic
            // We'll mark as optional if nullable OR has default
            val required = !isNullable && !hasDefault
            
            // Get default value from the default instance
            // Use call() method to safely access the property value
            val defaultValue = try {
                if (defaultInstance != null) {
                    val value = prop.call(defaultInstance)
                    when {
                        value == null -> null
                        value is Boolean -> value.toString()
                        value is IntRange -> null // Don't show default for IntRange
                        else -> {
                            val str = value.toString()
                            if (str != "null" && str.isNotEmpty()) str else null
                        }
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            
            // Get description from annotation - check property first, then base class property
            val description = prop.findAnnotation<ParamDescription>()?.value
                ?: queryParamsClass.supertypes.firstOrNull()?.classifier
                    ?.let { baseClass ->
                        if (baseClass is KClass<*>) {
                            baseClass.memberProperties.find { it.name == paramName }
                                ?.findAnnotation<ParamDescription>()?.value
                        } else null
                    }
                ?: generateDescription(paramName, typeName)
            
            params.add(
                QueryParam(
                    name = paramName,
                    type = typeName,
                    required = required,
                    description = description,
                    defaultValue = defaultValue
                )
            )
        }
        
        return params
    }
    
    /**
     * Generate a description for a parameter based on its name and type
     */
    private fun generateDescription(name: String, type: String): String {
        return when (name.lowercase()) {
            "id" -> "Archive ID of the resource"
            "gameval" -> "Lookup resource by name (gameVal field) or use as archive ID"
            "idrange" -> "Filter by ID range. Formats: \"45..49\", \"45-49\", or \"45,49\". Ignored when using searchMode."
            "limit" -> "Limit the number of results returned"
            "searchmode" -> "Search mode: 'id' (ID ranges like \"45+90\" or single IDs), 'gameval' (comma-separated list, quoted for exact match), 'regex' (regex pattern), or 'name' (case-insensitive substring). When used, idRange is ignored."
            "q" -> "Search query string. With searchMode='id': supports ranges (\"45+90\") or comma-separated IDs. With searchMode='gameval': comma-separated list (quoted strings for exact match). With searchMode='regex': regex pattern. With searchMode='name': substring to match."
            else -> "$type parameter"
        }
    }
    
    /**
     * Get all registered endpoints as JSON data
     */
    fun getEndpointsData(baseUrl: String): EndpointsData {
        return EndpointsData(
            baseUrl = baseUrl,
            endpoints = endpoints.sortedBy { it.category + it.path }
        )
    }
    
    /**
     * Clear all registered endpoints (useful for testing)
     */
    fun clear() {
        endpoints.clear()
    }
}
