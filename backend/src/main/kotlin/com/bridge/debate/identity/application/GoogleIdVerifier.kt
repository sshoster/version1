package com.bridge.debate.identity.application

/** What Google attests about the person after their ID token has been verified. */
data class GoogleIdentity(
    val email: String,
    val displayName: String,
    val emailVerified: Boolean,
)

/**
 * Port for verifying a Google Identity Services ID token (the credential the "Sign in with
 * Google" button returns). Returns null for anything invalid: bad signature, expired, or a
 * token minted for a different OAuth client.
 */
interface GoogleIdVerifier {
    fun verify(idToken: String): GoogleIdentity?
}
