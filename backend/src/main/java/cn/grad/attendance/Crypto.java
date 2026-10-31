package cn.grad.attendance;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Crypto {
    private final SecretKeySpec key;
    private static final SecureRandom RANDOM=new SecureRandom();
    public Crypto(@Value("${app.encryption-key}") String encoded,@Value("${app.internal-key}") String internalKey) {
        try { byte[] bytes=Base64.getDecoder().decode(encoded); if(bytes.length!=32) throw new IllegalArgumentException(); key=new SecretKeySpec(bytes,"AES"); }
        catch(Exception e) { throw new IllegalStateException("FACE_ENCRYPTION_KEY must be base64 encoding of exactly 32 random bytes; run scripts/init-env.ps1"); }
        if(internalKey.length()<32) throw new IllegalStateException("INTERNAL_API_KEY must contain at least 32 random characters");
    }
    public String encrypt(String plain,Long employeeId) {
        try { byte[] iv=new byte[12];RANDOM.nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));c.updateAAD(employeeId.toString().getBytes(StandardCharsets.UTF_8));byte[] out=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));byte[] all=new byte[iv.length+out.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(out,0,all,iv.length,out.length);return Base64.getEncoder().encodeToString(all); }
        catch(GeneralSecurityException e) { throw new IllegalStateException("Template encryption failed",e); }
    }
    public String decrypt(String encoded,Long employeeId) {
        try { byte[] all=Base64.getDecoder().decode(encoded);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(all,0,12)));c.updateAAD(employeeId.toString().getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Arrays.copyOfRange(all,12,all.length)),StandardCharsets.UTF_8); }
        catch(Exception e) { throw new IllegalStateException("Template decryption failed: verify original encryption key",e); }
    }
    public static String token() { byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
