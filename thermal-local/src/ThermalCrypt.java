import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ThermalCrypt {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: ThermalCrypt e|d IN OUT");
        }
        boolean encrypt = args[0].equals("e");
        byte[] key = "thermalopenssl.h".getBytes("UTF-8");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(key));
        byte[] out = cipher.doFinal(Files.readAllBytes(Paths.get(args[1])));
        Files.write(Paths.get(args[2]), out);
    }
}
