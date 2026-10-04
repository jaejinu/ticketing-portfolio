package com.ticketing.auth.jwt;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtTokenVerifierTest {
    JwtKeyManager keys;
    JwtTokenVerifier verifier;
    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        keys = mock(JwtKeyManager.class);
        when(keys.privateKey()).thenReturn((java.security.interfaces.RSAPrivateKey) pair.getPrivate());
        when(keys.publicKey()).thenReturn((java.security.interfaces.RSAPublicKey) pair.getPublic());
        when(keys.kid()).thenReturn("fixture");
        verifier = new JwtTokenVerifier(keys, "ticketing");
    }
    @Test void acceptsSignedIdentity() {
        UUID user = UUID.randomUUID();
        var issuer = new JwtTokenIssuer(keys, "ticketing", Duration.ofMinutes(15), Clock.systemUTC());
        assertThat(verifier.tryVerify(issuer.issue(user, "test@example.com", "Fixture", List.of("USER"))))
                .hasValueSatisfying(parsed -> assertThat(parsed.userId()).isEqualTo(user));
    }
    @Test void rejectsExpiredToken() {
        var issuer = new JwtTokenIssuer(keys, "ticketing", Duration.ofMinutes(-1), Clock.systemUTC());
        assertThat(verifier.tryVerify(issuer.issue(UUID.randomUUID(), "test@example.com", "Fixture", List.of("USER")))).isEmpty();
    }
    @Test void rejectsWrongIssuer() {
        var issuer = new JwtTokenIssuer(keys, "untrusted", Duration.ofMinutes(15), Clock.systemUTC());
        assertThat(verifier.tryVerify(issuer.issue(UUID.randomUUID(), "test@example.com", "Fixture", List.of("USER")))).isEmpty();
    }
    @Test void rejectsMalformedSubjectWithoutThrowing() {
        String token = Jwts.builder().issuer("ticketing").subject("not-a-uuid")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(keys.privateKey(), Jwts.SIG.RS256).compact();
        assertThat(verifier.tryVerify(token)).isEmpty();
    }
    @Test void rejectsInvalidSignatureAndArbitraryBearer() throws Exception {
        var other = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String token = Jwts.builder().issuer("ticketing").subject(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(other.getPrivate(), Jwts.SIG.RS256).compact();
        assertThat(verifier.tryVerify(token)).isEmpty();
        assertThat(verifier.tryVerify("forged")).isEmpty();
    }
}
