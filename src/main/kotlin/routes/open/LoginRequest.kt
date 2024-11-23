package routes.open

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val browser: String,
    val rememberMe: String
)