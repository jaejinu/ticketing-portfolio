package com.ticketing.auth.jwt;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * RS256 JWT 서명에 사용할 RSA 키 페어 관리자.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>설정된 경로의 PEM 파일에서 RSA 키 페어 로드.</li>
 *   <li>파일이 없으면 RSA 2048 비트 키 페어 자동 생성 후 PEM 저장(개발 편의).</li>
 *   <li>kid(키 식별자) 산출 — public key 의 SHA-256 해시 앞 16바이트의 hex.</li>
 *   <li>JWKS 응답에 필요한 modulus(n), exponent(e) base64url 인코딩 제공.</li>
 * </ul>
 *
 * <h2>왜 RS256 인가 (HS256 대비)</h2>
 * <ul>
 *   <li>RS256 은 비대칭 → 검증자(다른 모듈/외부) 가 public key 만 알면 검증 가능.
 *       향후 마이크로서비스 분리 시 신뢰 경계가 깔끔해진다.</li>
 *   <li>HS256 은 검증자도 비밀키를 알아야 해 secret leak 시 모두 깨진다.</li>
 *   <li>JWKS 표준({@code /.well-known/jwks.json}) 노출이 자연스러워 frontend 가 검증
 *       해야 하는 시나리오에서도 통용된다.</li>
 * </ul>
 *
 * <h2>파일 형식</h2>
 * PKCS#8 PEM (--BEGIN PRIVATE KEY--/--BEGIN PUBLIC KEY--).
 */
@Component
public class JwtKeyManager {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyManager.class);

    private final Path privateKeyPath;
    private final Path publicKeyPath;
    private final boolean autoGenerate;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String kid;

    public JwtKeyManager(
            @Value("${app.auth.jwt.private-key-path:./backend/module-auth/src/main/resources/keys/jwt-private.pem}") String privateKeyPath,
            @Value("${app.auth.jwt.public-key-path:./backend/module-auth/src/main/resources/keys/jwt-public.pem}") String publicKeyPath,
            @Value("${app.auth.jwt.auto-generate-keys:true}") boolean autoGenerate) {
        this.privateKeyPath = Path.of(privateKeyPath);
        this.publicKeyPath = Path.of(publicKeyPath);
        this.autoGenerate = autoGenerate;
    }

    @PostConstruct
    void init() throws Exception {
        if (!Files.exists(privateKeyPath) || !Files.exists(publicKeyPath)) {
            if (!autoGenerate) {
                throw new IllegalStateException(
                        "JWT key files not found and auto-generate disabled: " + privateKeyPath);
            }
            log.warn("[auth] JWT key files missing — generating RSA 2048 key pair at {}", privateKeyPath.getParent());
            generateAndStore();
        }
        load();
        this.kid = computeKid(publicKey);
        log.info("[auth] JWT key loaded. kid={}", kid);
    }

    private void load() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        this.privateKey = (RSAPrivateKey) readPrivateKey(privateKeyPath);
        this.publicKey = (RSAPublicKey) readPublicKey(publicKeyPath);
    }

    private void generateAndStore() throws Exception {
        Files.createDirectories(privateKeyPath.getParent());
        Files.createDirectories(publicKeyPath.getParent());

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        writePem(privateKeyPath, "PRIVATE KEY", pair.getPrivate().getEncoded());
        writePem(publicKeyPath, "PUBLIC KEY", pair.getPublic().getEncoded());
    }

    private static void writePem(Path path, String label, byte[] der) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        String pem = "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
        Files.writeString(path, pem, StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static PrivateKey readPrivateKey(Path path) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] der = readPemBody(path);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static PublicKey readPublicKey(Path path) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] der = readPemBody(path);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] readPemBody(Path path) throws IOException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        String body = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /**
     * kid = sha256(public key DER) 의 앞 16 바이트(hex 32자).
     * JWKS 응답과 JWT 헤더 양쪽에서 동일하게 산출되어야 frontend/외부가 매칭 가능.
     */
    private static String computeKid(RSAPublicKey publicKey) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
        byte[] head = new byte[16];
        System.arraycopy(hash, 0, head, 0, 16);
        return HexFormat.of().formatHex(head);
    }

    public RSAPrivateKey privateKey() { return privateKey; }
    public RSAPublicKey publicKey() { return publicKey; }
    public String kid() { return kid; }
}
