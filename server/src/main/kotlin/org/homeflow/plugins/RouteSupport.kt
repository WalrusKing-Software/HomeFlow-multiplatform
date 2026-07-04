package org.homeflow.plugins

import io.ktor.server.auth.principal
import io.ktor.server.routing.RoutingContext
import org.homeflow.lib.UnauthorizedException
import org.homeflow.modules.users.UserPrincipal

/**
 * The [UserPrincipal] attached by the JWT auth plugin. Inside an `authenticate`
 * block it is always present; the null branch is a defensive `401` only. Every
 * protected handler scopes its work to this principal's internal user id.
 */
fun RoutingContext.requirePrincipal(): UserPrincipal = call.principal<UserPrincipal>() ?: throw UnauthorizedException()
