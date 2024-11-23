package routes.user

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val username: String,
    val email: String,
    val password: String,
    val tokens: MutableList<Token> = mutableListOf()
)