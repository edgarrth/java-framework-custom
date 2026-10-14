package pe.axiz.core.fileformats;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;

/** File checksums for integrity controls with legacy systems. */
public final class Checksums {

  private Checksums() {}

  public static String sha256(InputStream in) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        d.update(buf, 0, n);
      }
      return HexFormat.of().formatHex(d.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static long crc32(byte[] data) {
    CRC32 crc = new CRC32();
    crc.update(data);
    return crc.getValue();
  }
}
