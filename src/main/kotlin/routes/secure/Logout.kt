package routes.secure

import routes.user.Token
import routes.user.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.example.MongoDB
import org.litote.kmongo.elemMatch
import org.litote.kmongo.eq
import org.litote.kmongo.pullByFilter

object Logout {

    suspend fun handleLogout(call: ApplicationCall) {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
        if (token != null) {
            val foundUser = MongoDB.users.findOne(User::tokens.elemMatch(Token::token eq token))
            if (foundUser != null) {
                MongoDB.users.updateOne(
                    User::email eq foundUser.email,
                    pullByFilter(User::tokens, Token::token eq token)
                )
                call.respond(HttpStatusCode.OK, "Logged out successfully")
            } else {
                call.respond(HttpStatusCode.BadRequest, "Invalid token")
            }
        } else {
            call.respond(HttpStatusCode.BadRequest, "No token provided")
        }
    }
}