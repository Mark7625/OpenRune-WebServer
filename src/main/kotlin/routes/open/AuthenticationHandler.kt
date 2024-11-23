package routes.open

import routes.user.Token
import routes.user.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.example.MongoDB
import org.litote.kmongo.eq
import org.litote.kmongo.push
import org.mindrot.jbcrypt.BCrypt
import routes.secure.TokenManager

// Authentication Handler
object AuthenticationHandler {
    suspend fun handleLogin(call: ApplicationCall) {
        val loginRequest = call.receive<LoginRequest>()
        val foundUser = MongoDB.users.findOne(User::email eq loginRequest.email)

        when {
            foundUser == null -> call.respond(HttpStatusCode.NotFound, "No account found with this email")
            !BCrypt.checkpw(loginRequest.password, foundUser.password) -> call.respond(HttpStatusCode.Unauthorized, "Invalid password")
            else -> {
                val token = TokenManager.generateToken(loginRequest.email)
                val sessionToken = Token(
                    token = token,
                    expiry = TokenManager.calculateExpiry(),
                    browser = loginRequest.browser,
                    location = ""
                )
                MongoDB.users.updateOne(
                    User::email eq foundUser.email,
                    push(User::tokens, sessionToken)
                )

                call.respond(mapOf("token" to token))
            }
        }
    }

}