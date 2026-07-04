package org.homeflow.modules.users

import com.auth0.jwt.interfaces.Payload
import org.homeflow.core.dto.UserDto
import org.homeflow.lib.KeycloakAdminClient
import org.homeflow.lib.NotFoundException
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * User bootstrap + account lifecycle. Business logic only — DB access is delegated
 * to [UsersRepository], Keycloak deletion to [KeycloakAdminClient].
 */
class UsersService(
    private val usersRepository: UsersRepository,
    private val keycloakAdminClient: KeycloakAdminClient,
) {
    private val logger = LoggerFactory.getLogger(UsersService::class.java)

    /**
     * Resolves a [UserPrincipal] from validated JWT claims, upserting the `users`
     * row on first login. Returns `null` (→ 401) if the token carries no `sub`.
     * Called from the auth `validate` block — the `sub` here is already trusted
     * (signature/`iss`/`aud`/`exp` verified upstream).
     */
    fun principalFromClaims(payload: Payload): UserPrincipal? {
        val sub = payload.subject?.takeIf { it.isNotBlank() } ?: return null
        val user = usersRepository.upsertByKeycloakSub(sub)
        return UserPrincipal(id = user.id, keycloakSub = user.keycloakSub)
    }

    /** Returns the authenticated user's internal record. */
    fun getMe(principal: UserPrincipal): UserDto {
        val user = usersRepository.findById(principal.id) ?: throw NotFoundException("User record not found.")
        val createdAtUtc = user.createdAt.toInstant().atOffset(ZoneOffset.UTC)
        return UserDto(
            id = user.id.toString(),
            createdAt = createdAtUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    /**
     * Permanently deletes the account. Order matters (see `__docs/API.md`): all
     * health data + the `users` row first (one cascading delete), then the Keycloak
     * identity. If Keycloak deletion fails after the DB delete, we surface a 500 and
     * log that manual cleanup is needed — without logging the `sub` (PII).
     */
    suspend fun deleteAccount(principal: UserPrincipal) {
        usersRepository.deleteByUserId(principal.id)
        runCatching { keycloakAdminClient.deleteUser(principal.keycloakSub) }
            .onFailure { e ->
                logger.error("Keycloak account deletion failed after DB deletion; manual Keycloak cleanup required.", e)
                throw e
            }
    }
}
