package org.coryd.identity.dto.mfa;

/**
 * What the authenticator app needs: secret (base32, for typing in) and otpauthUrl (for the QR code). issuer "CoRyd Ops";
 * account is the admin's email (or phone).
 */
public record MfaSetupView(String secret, String otpauthUrl, String issuer, String account) {
}
