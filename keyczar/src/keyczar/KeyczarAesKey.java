// Keyczar (http://code.google.com/p/keyczar/) 2008

package keyczar;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import keyczar.internal.*;

/**
 * Wrapping class for AES keys
 * 
 * @author steveweis@gmail.com (Steve Weis)
 *
 */
public class KeyczarAesKey extends KeyczarKey {
  private static final String AES_ALGORITHM = "AES";
  private enum CipherMode {
    CBC(0, "AES/CBC/PKCS5Padding", true),
    CTR(1, "AES/CTR/NoPadding", true),
    ECB(2, "AES/ECB/NoPadding", false),
    DET_CBC(3, "AES/CBC/PKCS5Padding", false);
    private String jceMode;
    private int value;
    private boolean useIv;
    private CipherMode(int v, String s, boolean useIv) {
      this.value = v;
      this.jceMode = s;
      this.useIv = useIv;
    }
    static CipherMode getMode(int value) {
      switch (value) {
        case 0: return CBC;
        case 1: return CTR;
        case 2: return ECB;
        case 3: return DET_CBC;
      }
      return null;
    }

    int getValue() {
      return value;
    }
    
    String getMode() {
      return jceMode;
    }
  }
  
  private KeyczarHmacKey hmacKey = new KeyczarHmacKey();
  private Key aesKey;
  // Default mode is CBC
  private CipherMode mode = CipherMode.CBC;
  private byte[] hash = new byte[Constants.KEY_HASH_SIZE];
  private int hashCode;
  private int blockSize;
  
  private void init(byte[] aesBytes) {
    byte[] fullHash = Util.hash(Util.fromInt(aesBytes.length), aesBytes);
    System.arraycopy(fullHash, 0, hash, 0, hash.length);
    hashCode = Util.toInt(hash);
    aesKey = new SecretKeySpec(aesBytes, AES_ALGORITHM);
    blockSize = aesBytes.length;
  }

  @Override
  protected byte[] hash() {
    return hash;
  }  
  
  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  protected void read(DataUnpacker unpacker)
      throws KeyczarException {
    int typeValue = unpacker.getInt();
    if (typeValue != getType().getValue()) {
      throw new KeyczarException("Invalid key type for AES key: " +
          KeyType.getType(typeValue));
    }
    CipherMode mode = CipherMode.getMode(unpacker.getInt()); 
    byte[] aesKeyMaterial = unpacker.getArray();
    hmacKey.read(unpacker);
    
    init(aesKeyMaterial);
  }

  @Override
  protected void generate() {
    init(Util.rand(getType().defaultSize()));
    hmacKey = new KeyczarHmacKey();
    hmacKey.generate();
  }

  @Override
  protected int write(DataPacker packer) throws KeyczarException {
    if (hmacKey == null) {
      throw new KeyczarException("Cannot write uninitialized key");
    }
    int written = packer.putInt(getType().getValue());
    written += packer.putInt(mode.getValue());
    written += packer.putArray(aesKey.getEncoded());
    written += hmacKey.write(packer);
    return written;
  }

  @Override
  protected KeyType getType() {
    return KeyType.AES;
  }
  
  @Override
  protected Stream getStream() throws KeyczarException {
    return new AesStream();
  }

  private class AesStream extends Stream
      implements EncryptingStream, DecryptingStream {
    Cipher cipher;
    
    public AesStream() throws KeyczarException {
      try {
        cipher = Cipher.getInstance(mode.getMode());
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }
    
    @Override
    public void initDecrypt(ByteBuffer input) throws KeyczarException {
      try {
        byte[] ivBytes = new byte[cipher.getBlockSize()];
        input.get(ivBytes);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(ivBytes));
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      } 
    }
    
    @Override
    public byte[] initEncrypt() throws KeyczarException {
      try {
        cipher.init(Cipher.ENCRYPT_MODE, aesKey);
        return cipher.getIV();
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public int doFinal(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      try {
        return cipher.doFinal(input, output);
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }
    
    @Override
    public int update(ByteBuffer input, ByteBuffer output) throws KeyczarException {
      try {
        return cipher.update(input, output);
      } catch (ShortBufferException e) {
        throw new KeyczarException(e);
      }
    }
    
    @Override
    public int maxOutputSize(int inputLen) {
      switch(mode) {
        case ECB:
          return blockSize;
        case CTR:
          return inputLen;
        case CBC: default:
          return (inputLen / blockSize + 2) * blockSize;
      }
    }
    
    @Override
    public int ivSize() {
      switch(mode) {
        case ECB:
          return 0;
        case CTR:
          return blockSize / 2;
        case CBC: default:
          return blockSize;
      }
    }

    @Override
    public SigningStream getSigningStream() throws KeyczarException {
      return (SigningStream) hmacKey.getStream();
    }

    @Override
    public VerifyingStream getVerifyingStream() throws KeyczarException {
      return (VerifyingStream) hmacKey.getStream();
    }
  }
}
