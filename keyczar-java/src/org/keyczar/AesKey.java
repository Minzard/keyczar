/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keyczar;

import com.google.gson.annotations.Expose;

import org.keyczar.enums.CipherMode;
import org.keyczar.enums.KeyType;
import org.keyczar.exceptions.KeyczarException;
import org.keyczar.exceptions.ShortBufferException;
import org.keyczar.exceptions.UnsupportedTypeException;
import org.keyczar.i18n.Messages;
import org.keyczar.interfaces.DecryptingStream;
import org.keyczar.interfaces.EncryptingStream;
import org.keyczar.interfaces.SigningStream;
import org.keyczar.interfaces.Stream;
import org.keyczar.interfaces.VerifyingStream;
import org.keyczar.util.Base64Coder;
import org.keyczar.util.Util;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wrapping class for AES keys. Currently the default is to use CBC mode
 * 
 * @author steveweis@gmail.com (Steve Weis)
 */
class AesKey extends KeyczarKey {
  private Key aesKey;
  private int blockSize;

  private static final String AES_ALGORITHM = "AES"; //$NON-NLS-1$
  // Default mode is CBC
  private static final CipherMode DEFAULT_MODE = CipherMode.CBC;

  @Expose private String aesKeyString = ""; //$NON-NLS-1$
  @Expose private HmacKey hmacKey = new HmacKey();
  @Expose private CipherMode mode = DEFAULT_MODE;
  
  private byte[] hash = new byte[Keyczar.KEY_HASH_SIZE];

  @Override
  public String toString() {
    return Util.gson().toJson(this);
  }

  static AesKey generate() throws KeyczarException {
    AesKey key = new AesKey();
    key.size = key.getType().keySize();
    byte[] aesBytes = Util.rand(key.size() / 8);
    key.aesKeyString = Base64Coder.encode(aesBytes);
    key.mode = DEFAULT_MODE;
    key.hmacKey = HmacKey.generate();
    key.init();
    return key;
  }

  @Override
  KeyType getType() {
    return KeyType.AES;
  }

  @Override
  byte[] hash() {
    return hash;
  }

  static AesKey read(String input) throws KeyczarException {
    AesKey key = Util.gson().fromJson(input, AesKey.class);
    key.hmacKey.init();
    key.init();
    return key;
  }

  private void init() throws KeyczarException {
    byte[] aesBytes = Base64Coder.decode(aesKeyString);
    aesKey = new SecretKeySpec(aesBytes, AES_ALGORITHM);
    blockSize = aesBytes.length;
    byte[] fullHash = Util.prefixHash(aesBytes, hmacKey.hash());
    System.arraycopy(fullHash, 0, hash, 0, hash.length);
  }

  @Override
  Stream getStream() throws KeyczarException {
    return new AesStream();
  }

  private class AesStream implements EncryptingStream, DecryptingStream {
    private Cipher encryptingCipher;
    private Cipher decryptingCipher;
    private SigningStream signStream;
    boolean ivRead = false;

    public AesStream() throws KeyczarException  {
      /* 
       * The JCE Cipher.init() call essentially reallocates a new Cipher object
       * We avoid this by initializing two Cipher objects with zero-valued IVs,
       * Then passing IVs for CBC mode ourselves. The Ciphers will be cached in
       * this stream
       */
      IvParameterSpec zeroIv = new IvParameterSpec(new byte[blockSize]);
      try {
        encryptingCipher = Cipher.getInstance(mode.getMode());
        encryptingCipher.init(Cipher.ENCRYPT_MODE, aesKey, zeroIv);
        decryptingCipher = Cipher.getInstance(mode.getMode());
        decryptingCipher.init(Cipher.DECRYPT_MODE, aesKey, zeroIv);
        signStream = (SigningStream) hmacKey.getStream();
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public SigningStream getSigningStream() {
      return signStream;
    }

    @Override
    public VerifyingStream getVerifyingStream() {
      return (VerifyingStream) signStream;
    }

    @Override
    public void initDecrypt(ByteBuffer input) {
      // This will simply decrypt the first block, leaving the CBC Cipher
      // ready for the next block of input.
      byte[] iv = new byte[blockSize];
      input.get(iv);
      decryptingCipher.update(iv);
      ivRead = true;
    }

    @Override
    public int initEncrypt(ByteBuffer output) throws KeyczarException {
      // Generate a random value and encrypt it. This will be the IV.
      byte[] ivPreImage = new byte[blockSize];
      Util.rand(ivPreImage);
      try {
        return encryptingCipher.update(ByteBuffer.wrap(ivPreImage), output);
      } catch (javax.crypto.ShortBufferException e) {
        throw new ShortBufferException(e);
      }
    }

    @Override
    public int updateDecrypt(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      if (ivRead) {
        // The next output block will be the IV preimage, which we'll discard
        byte[] temp = new byte[blockSize];
        input.get(temp);
        byte[] ivPreimage = decryptingCipher.update(temp);
        ivRead = false;
      }
      try {
        return decryptingCipher.update(input, output);
      } catch (javax.crypto.ShortBufferException e) {
        throw new ShortBufferException(e);
      }
    }

    @Override
    public int updateEncrypt(ByteBuffer input, ByteBuffer output)
    throws KeyczarException {
      try {
        return encryptingCipher.update(input, output);
        //return encryptingCipher.update(input, output);
      } catch (javax.crypto.ShortBufferException e) {
        throw new ShortBufferException(e);
      }
    }

    @Override
    public int doFinalDecrypt(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      if (ivRead) {
        // The next output block will be the IV preimage, which we'll discard
        byte[] temp = new byte[blockSize];
        input.get(temp);
        byte[] ivPreimage = decryptingCipher.update(temp);
        ivRead = false;
      }
      try {
        return decryptingCipher.doFinal(input, output);
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public int doFinalEncrypt(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      try {
        return encryptingCipher.doFinal(input, output);
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public int maxOutputSize(int inputLen) {
      return mode.getOutputSize(blockSize, inputLen);
    }
  }
}