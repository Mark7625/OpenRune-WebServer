package routes.open

import routes.user.Token
import routes.user.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.example.MongoDB
import org.litote.kmongo.eq
import org.mindrot.jbcrypt.BCrypt
import routes.secure.TokenManager
import java.util.regex.Pattern

// Registration Handler
object RegistrationHandler {
    suspend fun handleRegistration(call: ApplicationCall) {
        val user = call.receive<User>()

        // Validation logic
        val validationError = validateUser(user)
        if (validationError != null) {
            call.respond(HttpStatusCode.BadRequest, validationError)
            return
        }

        // Check if the username or email already exists
        val existingUsername = MongoDB.users.findOne(User::username eq user.username)
        val existingEmail = MongoDB.users.findOne(User::email eq user.email)

        when {
            existingUsername != null -> call.respond(HttpStatusCode.Conflict, "Username is already taken.")
            existingEmail != null -> call.respond(HttpStatusCode.Conflict, "Email is already registered.")
            else -> {
                val hashedPassword = BCrypt.hashpw(user.password, BCrypt.gensalt())
                val newUser = user.copy(password = hashedPassword)
                MongoDB.users.insertOne(newUser)

                val token = TokenManager.generateToken(user.email)
                val sessionToken = Token(
                    token = token,
                    expiry = TokenManager.calculateExpiry(),
                    browser = "Unknown",
                    location = "Unknown"
                )
                newUser.tokens.add(sessionToken)

                MongoDB.users.updateOne(User::email eq user.email, newUser)

                call.respond(HttpStatusCode.Created, mapOf("token" to token))
            }
        }
    }

    private fun validateUser(user: User): String? {
        if (user.username.isBlank() || user.email.isBlank() || user.password.isBlank()) {
            return "All fields (username, email, and password) must be filled out."
        }

        val emailPattern = "^[A-Za-z0-9+_.-]+@(.+)\$"
        if (!Pattern.compile(emailPattern).matcher(user.email).matches()) {
            return "Invalid email format."
        }

        val passwordPattern = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}\$"
        if (!Pattern.compile(passwordPattern).matcher(user.password).matches()) {
            return "Password must be at least 8 characters long, contain at least one uppercase letter, one lowercase letter, and one digit."
        }

        return null
    }
}