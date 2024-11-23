package routes.user

import kotlinx.serialization.Serializable

@Serializable
data class Token(
    val token: String,
    val expiry: Long,
    val browser: String,
    val location: String
)