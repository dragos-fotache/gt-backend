package com.dragosfotache.gtbackend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AuthCrypto {
    private final byte[] macKey, encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public AuthCrypto(@Value("${auth.encryption-key:}") String encoded) {
        byte[] master;
        try {
            master = Base64.getDecoder().decode(encoded);
        } catch (Exception e) {
            throw new IllegalStateException("Auth encryption key is not configured");
        }
        if (master.length != 32) throw new IllegalStateException("Auth encryption key must be 32 bytes");
        macKey = derive(master, "GymTracka auth HMAC v1");
        encryptionKey = derive(master, "GymTracka replay encryption v1");
    }

    private byte[] derive(byte[] master, String label) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(master, "HmacSHA256"));
            return m.doFinal(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public String nonce() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public byte[] digest(String value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return m.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] digest(byte[] value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return m.doFinal(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encrypt(byte[] value) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] x = c.doFinal(value);
            byte[] out = new byte[iv.length + x.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(x, 0, out, iv.length, x.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] decrypt(byte[] value) {
        try {
            byte[] iv = java.util.Arrays.copyOf(value, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            return c.doFinal(value, 12, value.length - 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
