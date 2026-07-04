package org.homeflow.modules.users

import java.util.UUID

/**
 * The authenticated user, resolved from a validated Keycloak JWT and attached to
 * the call. [id] is the **internal app UUID** (the `users.id`) that every
 * downstream service/repository scopes health-data queries on — never trust an id
 * from a request body. [keycloakSub] is the Keycloak `sub` (identity owner).
 */
data class UserPrincipal(
    val id: UUID,
    val keycloakSub: String,
)
