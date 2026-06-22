package org.homeflow.app.shared.auth

/** Shared in-memory fakes for the auth orchestration tests. */

fun tokens(
    accessToken: String,
    refreshToken: String?,
): OidcTokens = OidcTokens(accessToken, refreshToken, accessTokenExpiresAt = null)

class FakeOidcClient(
    var loginTokens: OidcTokens = tokens("login-AT", "login-RT"),
    var refreshTokens: OidcTokens = tokens("refresh-AT", "refresh-RT"),
) : OidcClient {
    var loginCount = 0
    var refreshCount = 0
    var logoutCount = 0
    var lastLoggedOutToken: String? = null

    override suspend fun login(): OidcTokens {
        loginCount++
        return loginTokens
    }

    override suspend fun refresh(refreshToken: String): OidcTokens {
        refreshCount++
        return refreshTokens
    }

    override suspend fun logout(refreshToken: String) {
        logoutCount++
        lastLoggedOutToken = refreshToken
    }
}

class FakeTokenStore(
    var token: String? = null,
) : TokenStore {
    override fun saveRefreshToken(token: String) {
        this.token = token
    }

    override fun loadRefreshToken(): String? = token

    override fun clear() {
        token = null
    }
}

class FakeAppLockGate(
    override val usesPassphrase: Boolean = true,
    var enrolled: Boolean = true,
    var authResult: Boolean = true,
) : AppLockGate {
    var enrollCount = 0
    var authenticateCount = 0

    override fun needsEnrollment(): Boolean = !enrolled

    override suspend fun enroll(secret: String) {
        enrolled = true
        enrollCount++
    }

    override suspend fun authenticate(secret: String?): Boolean {
        authenticateCount++
        return authResult
    }
}
