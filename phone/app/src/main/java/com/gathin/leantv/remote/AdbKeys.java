package com.gathin.leantv.remote;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The RSA key this phone shows the TV. The TV answers a first connection with a 20-byte challenge; signing it
 * proves the key is the one the person allowed on the TV's "Allow debugging?" prompt, so nothing is paired by
 * hand.
 *
 * <p>Adb wants the public key in its own struct, not in X.509: the modulus and R^2 as 64 little-endian 32-bit
 * words, plus -1/n[0] mod 2^32 for its Montgomery multiplication. {@link #publicKeyPayload(RSAPublicKey)} builds
 * exactly that.
 */
final class AdbKeys {
    private static final int KEY_WORDS = 64;          // 2048-bit modulus
    private static final int KEY_BYTES = KEY_WORDS * 4;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private AdbKeys(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    static AdbKeys generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_WORDS * 32);
        KeyPair pair = generator.generateKeyPair();
        return new AdbKeys(pair.getPrivate(), pair.getPublic());
    }

    /** The key kept in the app's preferences: the TV's permission is granted once, not once per launch. */
    static AdbKeys fromBase64(String privateBase64, String publicBase64) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return new AdbKeys(
                factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateBase64))),
                factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicBase64))));
    }

    String privateBase64() {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    String publicBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    byte[] sign(byte[] token) throws Exception {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(privateKey);
        signer.update(token);
        return signer.sign();
    }

    /** AUTH RSAPUBLICKEY's payload: adb's public key struct in base64, then the NUL byte adb expects. */
    String publicKeyPayload() {
        return publicKeyPayload((RSAPublicKey) publicKey);
    }

    static String publicKeyPayload(RSAPublicKey key) {
        BigInteger modulus = key.getModulus();
        BigInteger exponent = key.getPublicExponent();
        BigInteger r = BigInteger.ONE.shiftLeft(KEY_BYTES * 8);
        BigInteger rr = r.multiply(r).mod(modulus);
        BigInteger low = modulus.mod(BigInteger.ONE.shiftLeft(32));
        BigInteger n0inv = BigInteger.ONE.shiftLeft(32).subtract(low.modInverse(BigInteger.ONE.shiftLeft(32)));

        byte[] blob = new byte[4 + 4 + KEY_BYTES + KEY_BYTES + 4];
        putLittle(blob, 0, KEY_WORDS);
        putLittle(blob, 4, n0inv.intValue());
        littleEndian(modulus, blob, 8);
        littleEndian(rr, blob, 8 + KEY_BYTES);
        putLittle(blob, 8 + 2 * KEY_BYTES, exponent.intValue());
        return Base64.getEncoder().encodeToString(blob) + "\0";
    }

    private static void littleEndian(BigInteger value, byte[] out, int offset) {
        BigInteger rest = value;
        for (int i = 0; i < KEY_BYTES; i++) {
            out[offset + i] = (byte) rest.and(BigInteger.valueOf(0xFF)).intValue();
            rest = rest.shiftRight(8);
        }
    }

    private static void putLittle(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) (value >> 16);
        out[at + 3] = (byte) (value >> 24);
    }
}
