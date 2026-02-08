package com.tronprotocol.app.plugins;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple notes plugin to demonstrate additional plugin extensibility.
 */
public class NotesPlugin implements Plugin {
    private static final String ID = "notes";
    private final List<String> notes = new ArrayList<>();
    private boolean enabled = true;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Notes";
    }

    @Override
    public String getDescription() {
        return "Create quick notes using add|text, list, and clear commands";
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
    public PluginResult execute(String input) {
        long start = System.currentTimeMillis();
        String[] parts = input.split("\\|", 2);
        String command = parts[0].trim().toLowerCase();

        switch (command) {
            case "add":
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    return PluginResult.error("Use add|<note>", System.currentTimeMillis() - start);
                }
                notes.add(parts[1].trim());
                return PluginResult.success("Added note. Total notes: " + notes.size(), System.currentTimeMillis() - start);
            case "list":
                if (notes.isEmpty()) {
                    return PluginResult.success("No notes yet", System.currentTimeMillis() - start);
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < notes.size(); i++) {
                    sb.append(i + 1).append(". ").append(notes.get(i)).append("\n");
                }
                return PluginResult.success(sb.toString(), System.currentTimeMillis() - start);
            case "clear":
                notes.clear();
                return PluginResult.success("Cleared all notes", System.currentTimeMillis() - start);
            default:
                return PluginResult.error("Unknown command. Use add|text, list, clear", System.currentTimeMillis() - start);
        }
    }

    @Override
    public void initialize(Context context) {
        // No-op
    }

    @Override
    public void destroy() {
        notes.clear();
    }
}
