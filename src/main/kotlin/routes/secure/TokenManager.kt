package routes.secure

import routes.user.Token
import routes.user.User
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.example.MongoDB
import org.litote.kmongo.elemMatch
import org.litote.kmongo.eq
import java.util.*

// routes.user.Token Manager
object TokenManager {
    private const val SECRET = "your-secret"
    private const val EXPIRY_DURATION = 20L * 24 * 60 * 60 * 1000 // 20 days

    fun generateToken(email: String): String {
        return JWT.create()
            .withClaim("email", email)
            .withClaim("exp", Date().time + EXPIRY_DURATION)
            .sign(Algorithm.HMAC256(SECRET))
    }

    fun isTokenExpired(expiry: Long): Boolean {
        return Date().time > expiry
    }

    fun calculateExpiry(): Long {
        return Date().time + EXPIRY_DURATION
    }

    suspend fun verifyToken(call: ApplicationCall) {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
        if (token != null) {
            val foundUser = MongoDB.users.findOne(User::tokens.elemMatch(Token::token eq token))
            if (foundUser != null) {
                call.respond(HttpStatusCode.OK, "routes.user.Token is valid")
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
            }
        } else {
            call.respond(HttpStatusCode.BadRequest, "No token provided")
        }
    }
}