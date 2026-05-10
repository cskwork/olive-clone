package com.olive.commerce.common.security;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM 인코딩된 RSA 키를 로드한다.
 *
 * - private key: PKCS#8 PEM (BEGIN PRIVATE KEY)
 * - public  key: X.509 SubjectPublicKeyInfo PEM (BEGIN PUBLIC KEY)
 *
 * 키 부재/파싱 실패 시 startup fail-fast.
 */
final class RsaKeyLoader {

    private static final String BEGIN_PRIVATE = "-----BEGIN PRIVATE KEY-----";
    private static final String END_PRIVATE = "-----END PRIVATE KEY-----";
    private static final String BEGIN_PUBLIC = "-----BEGIN PUBLIC KEY-----";
    private static final String END_PUBLIC = "-----END PUBLIC KEY-----";

    private RsaKeyLoader() {}

    static RSAPrivateKey loadPrivate(Resource resource) {
        byte[] der = decodePem(readUtf8(resource), BEGIN_PRIVATE, END_PRIVATE);
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("invalid RSA private key: " + describe(resource), ex);
        }
    }

    static RSAPublicKey loadPublic(Resource resource) {
        byte[] der = decodePem(readUtf8(resource), BEGIN_PUBLIC, END_PUBLIC);
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("invalid RSA public key: " + describe(resource), ex);
        }
    }

    private static String readUtf8(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read RSA key resource: " + describe(resource), ex);
        }
    }

    private static byte[] decodePem(String pem, String beginMarker, String endMarker) {
        int begin = pem.indexOf(beginMarker);
        int end = pem.indexOf(endMarker);
        if (begin < 0 || end < 0 || end <= begin) {
            throw new IllegalStateException(
                "PEM markers missing — expected '" + beginMarker + "' and '" + endMarker + "'");
        }
        String base64 = pem.substring(begin + beginMarker.length(), end)
            .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(base64);
    }

    private static String describe(Resource resource) {
        try {
            return resource.getURI().toString();
        } catch (IOException ex) {
            return resource.getDescription();
        }
    }
}
