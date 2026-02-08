package com.tronprotocol.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tronprotocol.app.plugins.CalculatorPlugin;
import com.tronprotocol.app.plugins.DateTimePlugin;
import com.tronprotocol.app.plugins.DeviceInfoPlugin;
import com.tronprotocol.app.plugins.FileManagerPlugin;
import com.tronprotocol.app.plugins.PluginManager;
import com.tronprotocol.app.plugins.TextAnalysisPlugin;
import com.tronprotocol.app.plugins.WebSearchPlugin;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 1002;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize plugin system (inspired by ToolNeuron)
        initializePlugins();
        
        // Request permissions
        requestPermissions();
        
        // Request to disable battery optimization
        requestBatteryOptimizationExemption();
        
        // Start the background service
        startTronProtocolService();
    }
    
    private void initializePlugins() {
        PluginManager pluginManager = PluginManager.getInstance();
        pluginManager.initialize(this);
        
        // Register built-in plugins
        pluginManager.registerPlugin(new DeviceInfoPlugin());
        pluginManager.registerPlugin(new WebSearchPlugin());
        pluginManager.registerPlugin(new CalculatorPlugin());
        pluginManager.registerPlugin(new DateTimePlugin());
        pluginManager.registerPlugin(new TextAnalysisPlugin());
        pluginManager.registerPlugin(new FileManagerPlugin());
        
        Toast.makeText(this, "Plugin system initialized with 6 plugins", Toast.LENGTH_SHORT).show();
    }

    private void requestPermissions() {
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }

    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
            }
        }
    }

    private void startTronProtocolService() {
        Intent serviceIntent = new Intent(this, TronProtocolService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(this, "Battery optimization disabled", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
