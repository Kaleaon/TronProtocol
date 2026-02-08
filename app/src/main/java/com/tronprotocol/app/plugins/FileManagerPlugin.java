package com.tronprotocol.app.plugins;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File Manager Plugin
 * 
 * Provides full filesystem access for document editing, creation, and management
 * Inspired by ToolNeuron's FileManagerPlugin and landseek's document processing
 * 
 * SECURITY NOTE: This plugin provides extensive file access. Use with caution.
 */
public class FileManagerPlugin implements Plugin {
    private static final String TAG = "FileManagerPlugin";
    private static final String ID = "file_manager";
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB limit
    
    private boolean enabled = true;
    private Context context;
    
    @Override
    public String getId() {
        return ID;
    }
    
    @Override
    public String getName() {
        return "File Manager";
    }
    
    @Override
    public String getDescription() {
        return "Full filesystem access: read, write, create, delete, list files and directories. " +
               "Supports document editing and creation. Commands: read|path, write|path|content, " +
               "list|path, delete|path, create|path, move|from|to, copy|from|to, mkdir|path";
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
        
        try {
            // Parse command: "operation|arg1|arg2|..."
            String[] parts = input.split("\\|");
            if (parts.length == 0) {
                throw new Exception("No command specified");
            }
            
            String command = parts[0].trim().toLowerCase();
            String result;
            
            switch (command) {
                case "read":
                    if (parts.length < 2) throw new Exception("Path required");
                    result = readFile(parts[1]);
                    break;
                    
                case "write":
                    if (parts.length < 3) throw new Exception("Path and content required");
                    result = writeFile(parts[1], parts[2]);
                    break;
                    
                case "append":
                    if (parts.length < 3) throw new Exception("Path and content required");
                    result = appendFile(parts[1], parts[2]);
                    break;
                    
                case "create":
                    if (parts.length < 2) throw new Exception("Path required");
                    result = createFile(parts[1]);
                    break;
                    
                case "delete":
                    if (parts.length < 2) throw new Exception("Path required");
                    result = deleteFile(parts[1]);
                    break;
                    
                case "list":
                    String path = parts.length > 1 ? parts[1] : ".";
                    result = listFiles(path);
                    break;
                    
                case "mkdir":
                    if (parts.length < 2) throw new Exception("Path required");
                    result = createDirectory(parts[1]);
                    break;
                    
                case "move":
                    if (parts.length < 3) throw new Exception("Source and destination required");
                    result = moveFile(parts[1], parts[2]);
                    break;
                    
                case "copy":
                    if (parts.length < 3) throw new Exception("Source and destination required");
                    result = copyFile(parts[1], parts[2]);
                    break;
                    
                case "exists":
                    if (parts.length < 2) throw new Exception("Path required");
                    result = checkExists(parts[1]);
                    break;
                    
                case "info":
                    if (parts.length < 2) throw new Exception("Path required");
                    result = getFileInfo(parts[1]);
                    break;
                    
                case "search":
                    if (parts.length < 3) throw new Exception("Directory and pattern required");
                    result = searchFiles(parts[1], parts[2]);
                    break;
                    
                default:
                    throw new Exception("Unknown command: " + command);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.success(result, duration);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return PluginResult.error("File operation failed: " + e.getMessage(), duration);
        }
    }
    
    /**
     * Read file contents
     */
    private String readFile(String path) throws IOException {
        File file = resolveFile(path);
        
        if (!file.exists()) {
            throw new IOException("File not found: " + path);
        }
        
        if (!file.isFile()) {
            throw new IOException("Not a file: " + path);
        }
        
        if (file.length() > MAX_FILE_SIZE) {
            throw new IOException("File too large (max 10MB): " + file.length() + " bytes");
        }
        
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        
        return "Read " + file.length() + " bytes from " + path + ":\n\n" + content.toString();
    }
    
    /**
     * Write content to file (overwrites existing)
     */
    private String writeFile(String path, String content) throws IOException {
        File file = resolveFile(path);
        
        // Create parent directories if needed
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
            writer.write(content);
        }
        
        return "Wrote " + content.length() + " characters to " + path;
    }
    
    /**
     * Append content to file
     */
    private String appendFile(String path, String content) throws IOException {
        File file = resolveFile(path);
        
        if (!file.exists()) {
            throw new IOException("File not found: " + path);
        }
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.write(content);
        }
        
        return "Appended " + content.length() + " characters to " + path;
    }
    
    /**
     * Create empty file
     */
    private String createFile(String path) throws IOException {
        File file = resolveFile(path);
        
        if (file.exists()) {
            throw new IOException("File already exists: " + path);
        }
        
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        
        if (!file.createNewFile()) {
            throw new IOException("Failed to create file: " + path);
        }
        
        return "Created file: " + file.getAbsolutePath();
    }
    
    /**
     * Delete file or directory
     */
    private String deleteFile(String path) throws IOException {
        File file = resolveFile(path);
        
        if (!file.exists()) {
            throw new IOException("File not found: " + path);
        }
        
        if (file.isDirectory() && file.list() != null && file.list().length > 0) {
            throw new IOException("Directory not empty: " + path);
        }
        
        if (!file.delete()) {
            throw new IOException("Failed to delete: " + path);
        }
        
        return "Deleted: " + path;
    }
    
    /**
     * List files in directory
     */
    private String listFiles(String path) throws IOException {
        File dir = resolveFile(path);
        
        if (!dir.exists()) {
            throw new IOException("Directory not found: " + path);
        }
        
        if (!dir.isDirectory()) {
            throw new IOException("Not a directory: " + path);
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            return "Empty directory";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("Contents of ").append(dir.getAbsolutePath()).append(":\n\n");
        
        int fileCount = 0, dirCount = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                result.append("[DIR]  ").append(file.getName()).append("/\n");
                dirCount++;
            } else {
                result.append("[FILE] ").append(file.getName())
                      .append(" (").append(formatSize(file.length())).append(")\n");
                fileCount++;
            }
        }
        
        result.append("\nTotal: ").append(dirCount).append(" directories, ")
              .append(fileCount).append(" files");
        
        return result.toString();
    }
    
    /**
     * Create directory
     */
    private String createDirectory(String path) throws IOException {
        File dir = resolveFile(path);
        
        if (dir.exists()) {
            throw new IOException("Directory already exists: " + path);
        }
        
        if (!dir.mkdirs()) {
            throw new IOException("Failed to create directory: " + path);
        }
        
        return "Created directory: " + dir.getAbsolutePath();
    }
    
    /**
     * Move/rename file
     */
    private String moveFile(String from, String to) throws IOException {
        File source = resolveFile(from);
        File dest = resolveFile(to);
        
        if (!source.exists()) {
            throw new IOException("Source not found: " + from);
        }
        
        if (dest.exists()) {
            throw new IOException("Destination already exists: " + to);
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } else {
            if (!source.renameTo(dest)) {
                throw new IOException("Failed to move file");
            }
        }
        
        return "Moved " + from + " to " + to;
    }
    
    /**
     * Copy file
     */
    private String copyFile(String from, String to) throws IOException {
        File source = resolveFile(from);
        File dest = resolveFile(to);
        
        if (!source.exists()) {
            throw new IOException("Source not found: " + from);
        }
        
        if (!source.isFile()) {
            throw new IOException("Can only copy files, not directories: " + from);
        }
        
        if (dest.exists()) {
            throw new IOException("Destination already exists: " + to);
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            // Fallback for older Android versions
            try (BufferedReader reader = new BufferedReader(new FileReader(source));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(dest))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
        
        return "Copied " + from + " to " + to;
    }
    
    /**
     * Check if file/directory exists
     */
    private String checkExists(String path) {
        File file = resolveFile(path);
        if (file.exists()) {
            String type = file.isDirectory() ? "directory" : "file";
            return "Yes, " + type + " exists: " + file.getAbsolutePath();
        }
        return "No, does not exist: " + path;
    }
    
    /**
     * Get file information
     */
    private String getFileInfo(String path) throws IOException {
        File file = resolveFile(path);
        
        if (!file.exists()) {
            throw new IOException("File not found: " + path);
        }
        
        StringBuilder info = new StringBuilder();
        info.append("Path: ").append(file.getAbsolutePath()).append("\n");
        info.append("Type: ").append(file.isDirectory() ? "Directory" : "File").append("\n");
        info.append("Size: ").append(formatSize(file.length())).append("\n");
        info.append("Modified: ").append(new java.util.Date(file.lastModified())).append("\n");
        info.append("Readable: ").append(file.canRead()).append("\n");
        info.append("Writable: ").append(file.canWrite()).append("\n");
        info.append("Executable: ").append(file.canExecute()).append("\n");
        
        return info.toString();
    }
    
    /**
     * Search for files matching pattern
     */
    private String searchFiles(String dirPath, String pattern) throws IOException {
        File dir = resolveFile(dirPath);
        
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IOException("Invalid directory: " + dirPath);
        }
        
        List<File> matches = new ArrayList<>();
        searchRecursive(dir, pattern, matches, 0);
        
        if (matches.isEmpty()) {
            return "No files found matching: " + pattern;
        }
        
        StringBuilder result = new StringBuilder();
        result.append("Found ").append(matches.size()).append(" file(s) matching '")
              .append(pattern).append("':\n\n");
        
        for (File file : matches) {
            result.append(file.getAbsolutePath()).append("\n");
        }
        
        return result.toString();
    }
    
    private void searchRecursive(File dir, String pattern, List<File> matches, int depth) {
        if (depth > 10) return; // Limit recursion depth
        
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.getName().toLowerCase().contains(pattern.toLowerCase())) {
                matches.add(file);
            }
            
            if (file.isDirectory()) {
                searchRecursive(file, pattern, matches, depth + 1);
            }
        }
    }
    
    /**
     * Resolve file path (supports relative and absolute paths)
     */
    private File resolveFile(String path) {
        File file = new File(path);
        
        // If relative path, resolve from external storage
        if (!file.isAbsolute()) {
            File externalStorage = Environment.getExternalStorageDirectory();
            file = new File(externalStorage, path);
        }
        
        return file;
    }
    
    /**
     * Format file size
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
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
