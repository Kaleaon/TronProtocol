package com.tronprotocol.app.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed encryption manager using Android KeyStore
 * Provides AES-256-GCM encryption for sensitive data
 * 
 * Inspired by ToolNeuron's Memory Vault encryption architecture
 */
public class EncryptionManager {
    private static final String TAG = "EncryptionManager";
    private static final String KEY_ALIAS = "TronProtocolMasterKey";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    private final KeyStore keyStore;
    
    public EncryptionManager() throws Exception {
        keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        ensureMasterKey();
    }
    
    private void ensureMasterKey() throws Exception {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            );
            
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();
            
            keyGenerator.init(spec);
            keyGenerator.generateKey();
            
            Log.d(TAG, "Master key generated in hardware-backed KeyStore");
        }
    }
    
    private SecretKey getMasterKey() throws Exception {
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }
    
    /**
     * Encrypt data using AES-256-GCM
     * @param data Plain data to encrypt
     * @return Encrypted data with IV prepended
     */
    public byte[] encrypt(byte[] data) throws Exception {
        long startTime = System.currentTimeMillis();
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey());
        
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(data);
        
        // Prepend IV to encrypted data
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        
        long duration = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Encrypted " + data.length + " bytes in " + duration + "ms");
        
        return result;
    }
    
    /**
     * Decrypt data using AES-256-GCM
     * @param encryptedData Encrypted data with IV prepended
     * @return Decrypted plain data
     */
    public byte[] decrypt(byte[] encryptedData) throws Exception {
        if (encryptedData.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted data size");
        }
        
        long startTime = System.currentTimeMillis();
        
        // Extract IV from beginning
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH);
        
        // Extract encrypted data
        byte[] ciphertext = new byte[encryptedData.length - GCM_IV_LENGTH];
        System.arraycopy(encryptedData, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec);
        
        byte[] decrypted = cipher.doFinal(ciphertext);
        
        long duration = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Decrypted " + encryptedData.length + " bytes in " + duration + "ms");
        
        return decrypted;
    }
    
    /**
     * Encrypt a string using UTF-8 encoding
     */
    public byte[] encryptString(String plaintext) throws Exception {
        return encrypt(plaintext.getBytes("UTF-8"));
    }
    
    /**
     * Decrypt to a string using UTF-8 encoding
     */
    public String decryptString(byte[] encryptedData) throws Exception {
        return new String(decrypt(encryptedData), "UTF-8");
    }
}
