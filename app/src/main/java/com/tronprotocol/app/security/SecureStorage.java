package com.tronprotocol.app.security;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Secure storage for sensitive data using hardware-backed encryption
 * 
 * Inspired by ToolNeuron's Memory Vault secure storage architecture
 */
public class SecureStorage {
    private static final String TAG = "SecureStorage";
    private static final String STORAGE_DIR = "secure_data";
    
    private final Context context;
    private final EncryptionManager encryptionManager;
    private final File storageDir;
    
    public SecureStorage(Context context) throws Exception {
        this.context = context;
        this.encryptionManager = new EncryptionManager();
        this.storageDir = new File(context.getFilesDir(), STORAGE_DIR);
        
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }
    
    /**
     * Store encrypted data with a key
     */
    public void store(String key, String data) throws Exception {
        byte[] encryptedData = encryptionManager.encryptString(data);
        File file = new File(storageDir, sanitizeKey(key));
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(encryptedData);
        }
        
        Log.d(TAG, "Stored encrypted data for key: " + key);
    }
    
    /**
     * Store encrypted binary data with a key
     */
    public void storeBytes(String key, byte[] data) throws Exception {
        byte[] encryptedData = encryptionManager.encrypt(data);
        File file = new File(storageDir, sanitizeKey(key));
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(encryptedData);
        }
        
        Log.d(TAG, "Stored encrypted bytes for key: " + key);
    }
    
    /**
     * Retrieve and decrypt data by key
     */
    public String retrieve(String key) throws Exception {
        File file = new File(storageDir, sanitizeKey(key));
        
        if (!file.exists()) {
            return null;
        }
        
        byte[] encryptedData = readFile(file);
        return encryptionManager.decryptString(encryptedData);
    }
    
    /**
     * Retrieve and decrypt binary data by key
     */
    public byte[] retrieveBytes(String key) throws Exception {
        File file = new File(storageDir, sanitizeKey(key));
        
        if (!file.exists()) {
            return null;
        }
        
        byte[] encryptedData = readFile(file);
        return encryptionManager.decrypt(encryptedData);
    }
    
    /**
     * Check if a key exists
     */
    public boolean exists(String key) {
        File file = new File(storageDir, sanitizeKey(key));
        return file.exists();
    }
    
    /**
     * Delete stored data by key
     */
    public boolean delete(String key) {
        File file = new File(storageDir, sanitizeKey(key));
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "Deleted data for key: " + key);
            return deleted;
        }
        return false;
    }
    
    /**
     * Clear all stored data
     */
    public void clearAll() {
        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        Log.d(TAG, "Cleared all secure storage");
    }
    
    private String sanitizeKey(String key) {
        // Replace special characters that aren't safe for filenames
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
    
    private byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return data;
        }
    }
}
