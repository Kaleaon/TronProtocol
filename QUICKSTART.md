# TronProtocol Quick Start Guide

## Overview
TronProtocol is an Android app for AI-driven cellular device monitoring with secure background services, inspired by ToolNeuron's architecture.

## Key Features

### 🔐 Security (from ToolNeuron)
- Hardware-backed AES-256-GCM encryption
- Secure storage for sensitive data
- Android KeyStore integration

### 🔌 Plugin System (from ToolNeuron)
- Extensible architecture
- Easy to add new functionality
- Built-in device info plugin

### 🤖 AI/NPU Support
- TensorFlow Lite integration
- GPU acceleration
- ML Kit for text recognition

### ⚡ Background Service
- Continuous operation
- Battery optimization override
- Auto-start on boot

## Quick Examples

### 1. Using Secure Storage

```java
// In any Activity or Service
SecureStorage storage = new SecureStorage(context);

// Store sensitive data
storage.store("api_key", "your-secret-key");
storage.store("device_token", "device-123456");

// Retrieve data
String apiKey = storage.retrieve("api_key");

// Check if exists
if (storage.exists("api_key")) {
    // Key exists
}

// Delete data
storage.delete("api_key");

// Clear all
storage.clearAll();
```

### 2. Using Encryption Directly

```java
// Create encryption manager
EncryptionManager encryption = new EncryptionManager();

// Encrypt string
String secret = "sensitive data";
byte[] encrypted = encryption.encryptString(secret);

// Decrypt
String decrypted = encryption.decryptString(encrypted);

// Encrypt bytes
byte[] data = new byte[]{1, 2, 3, 4, 5};
byte[] encryptedData = encryption.encrypt(data);
byte[] decryptedData = encryption.decrypt(encryptedData);
```

### 3. Creating a Custom Plugin

```java
public class MyCustomPlugin implements Plugin {
    private boolean enabled = true;
    private Context context;
    
    @Override
    public String getId() {
        return "my_custom_plugin";
    }
    
    @Override
    public String getName() {
        return "My Custom Plugin";
    }
    
    @Override
    public String getDescription() {
        return "Does something useful";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
    
    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    @Override
    public PluginResult execute(String input) throws Exception {
        long startTime = System.currentTimeMillis();
        
        // Do your plugin work here
        String result = "Processed: " + input;
        
        long duration = System.currentTimeMillis() - startTime;
        return PluginResult.success(result, duration);
    }
    
    @Override
    public void initialize(Context context) {
        this.context = context;
    }
    
    @Override
    public void destroy() {
        this.context = null;
    }
}
```

### 4. Registering and Using Plugins

```java
// In MainActivity or Application class
PluginManager manager = PluginManager.getInstance();
manager.initialize(context);

// Register your custom plugin
manager.registerPlugin(new MyCustomPlugin());

// Execute plugin
PluginResult result = manager.executePlugin("my_custom_plugin", "test input");

if (result.isSuccess()) {
    Log.d(TAG, "Result: " + result.getData());
    Log.d(TAG, "Execution time: " + result.getExecutionTimeMs() + "ms");
} else {
    Log.e(TAG, "Error: " + result.getErrorMessage());
}

// Get all plugins
List<Plugin> plugins = manager.getAllPlugins();

// Get enabled plugins only
List<Plugin> enabledPlugins = manager.getEnabledPlugins();
```

### 5. Managing AI Models

```java
// Create model metadata
AIModel model = new AIModel(
    "model-id-123",           // Unique ID
    "Llama 2 7B Q4",          // Display name
    "/path/to/model.gguf",    // File path
    "GGUF",                   // Model type
    4_000_000_000L            // Size in bytes
);

// Set category
model.setCategory("General");

// Track loading state
model.setLoaded(true);

// Get info
Log.d(TAG, "Model: " + model.toString());
// Output: Model{id='model-id-123', name='Llama 2 7B Q4', modelType='GGUF', size=3.7 GB, isLoaded=true, category='General'}
```

### 6. Background Service Usage

The TronProtocolService runs automatically and uses secure storage:

```java
// Service is started automatically by MainActivity
// You can also start it manually:
Intent serviceIntent = new Intent(context, TronProtocolService.class);
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    context.startForegroundService(serviceIntent);
} else {
    context.startService(serviceIntent);
}

// The service automatically:
// 1. Acquires wake lock
// 2. Initializes secure storage
// 3. Runs heartbeat every 30 seconds
// 4. Stores heartbeat timestamps securely
```

## Project Structure

```
app/src/main/java/com/tronprotocol/app/
├── MainActivity.java              # Main entry point
├── TronProtocolService.java       # Background service
├── BootReceiver.java              # Auto-start receiver
├── security/
│   ├── EncryptionManager.java     # Encryption utilities
│   └── SecureStorage.java         # Secure key-value store
├── plugins/
│   ├── Plugin.java                # Plugin interface
│   ├── PluginManager.java         # Plugin orchestrator
│   ├── PluginResult.java          # Execution results
│   └── DeviceInfoPlugin.java      # Example plugin
└── models/
    └── AIModel.java               # AI model metadata
```

## Common Tasks

### Adding a New Permission

1. Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.YOUR_PERMISSION" />
```

2. Add to `MainActivity.java` REQUIRED_PERMISSIONS array:
```java
private static final String[] REQUIRED_PERMISSIONS = {
    // ... existing permissions
    Manifest.permission.YOUR_PERMISSION
};
```

### Storing Sensitive Configuration

```java
// In your initialization code
SecureStorage storage = new SecureStorage(context);
storage.store("server_url", "https://api.example.com");
storage.store("auth_token", "your-auth-token");

// Later, retrieve
String serverUrl = storage.retrieve("server_url");
String authToken = storage.retrieve("auth_token");
```

### Adding Custom Heartbeat Logic

Edit `TronProtocolService.java`, method `performHeartbeat()`:

```java
private void performHeartbeat() {
    try {
        // Your custom logic here
        checkCellularStatus();
        processAIModels();
        
        // Store results securely
        if (secureStorage != null) {
            long timestamp = System.currentTimeMillis();
            secureStorage.store("last_heartbeat", String.valueOf(timestamp));
            secureStorage.store("status", getCurrentStatus());
        }
        
        android.util.Log.d("TronProtocol", "Heartbeat completed");
    } catch (Exception e) {
        android.util.Log.e("TronProtocol", "Error in heartbeat", e);
    }
}
```

## Security Best Practices

1. **Never hardcode secrets** - Use SecureStorage
2. **Clear sensitive data** - After use, null out references
3. **Validate input** - Before encryption/decryption
4. **Handle exceptions** - Encryption can fail
5. **Use permissions wisely** - Only request what you need

## Performance Tips

1. **Batch operations** - Encrypt/decrypt multiple items at once
2. **Cache results** - Don't re-encrypt the same data
3. **Async plugins** - For long-running operations
4. **Monitor metrics** - Use PluginResult timing data

## Testing

### Test Encryption
```java
@Test
public void testEncryption() throws Exception {
    EncryptionManager manager = new EncryptionManager();
    String original = "test data";
    byte[] encrypted = manager.encryptString(original);
    String decrypted = manager.decryptString(encrypted);
    assertEquals(original, decrypted);
}
```

### Test Secure Storage
```java
@Test
public void testSecureStorage() throws Exception {
    SecureStorage storage = new SecureStorage(context);
    storage.store("test_key", "test_value");
    String value = storage.retrieve("test_key");
    assertEquals("test_value", value);
    storage.delete("test_key");
}
```

## Troubleshooting

### Encryption fails
- Check device API level (must be >= 24)
- Verify Android KeyStore is available
- Check for sufficient storage space

### Plugin not executing
- Verify plugin is registered: `manager.getPlugin("plugin_id")`
- Check if plugin is enabled: `plugin.isEnabled()`
- Look for exceptions in logs

### Service stops
- Check battery optimization settings
- Verify FOREGROUND_SERVICE permission
- Check wake lock acquisition

## Resources

- [TOOLNEURON_INTEGRATION.md](TOOLNEURON_INTEGRATION.md) - Detailed integration guide
- [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md) - Implementation details
- [README.md](README.md) - Project overview
- [ToolNeuron GitHub](https://github.com/Siddhesh2377/ToolNeuron) - Original inspiration

## License

This project is provided as-is for development purposes.
