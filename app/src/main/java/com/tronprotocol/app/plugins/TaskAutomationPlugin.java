package com.tronprotocol.app.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Simple task automation plugin for queueing and tracking actionable tasks.
 */
public class TaskAutomationPlugin implements Plugin {
    private static final String ID = "task_automation";
    private static final String PREFS = "task_automation_plugin";
    private static final String KEY_TASKS = "tasks_json";

    private SharedPreferences preferences;
    private boolean enabled = true;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Task Automation";
    }

    @Override
    public String getDescription() {
        return "Task queue automation. Commands: create|title|details|dueEpochMs, list, due|nowEpochMs, run|taskId, complete|taskId";
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
        try {
            if (TextUtils.isEmpty(input)) {
                return PluginResult.error("No command provided", elapsed(start));
            }
            String[] parts = input.split("\\|", 4);
            String command = parts[0].trim().toLowerCase();
            switch (command) {
                case "create":
                    return createTask(parts, start);
                case "list":
                    return PluginResult.success(getTasks().toString(), elapsed(start));
                case "due":
                    return listDue(parts, start);
                case "run":
                    return runTask(parts, start);
                case "complete":
                    return completeTask(parts, start);
                default:
                    return PluginResult.error("Unknown command: " + command, elapsed(start));
            }
        } catch (Exception e) {
            return PluginResult.error("Task automation failed: " + e.getMessage(), elapsed(start));
        }
    }

    private PluginResult createTask(String[] parts, long start) throws Exception {
        if (parts.length < 4) {
            return PluginResult.error("Usage: create|title|details|dueEpochMs", elapsed(start));
        }
        JSONArray tasks = getTasks();
        JSONObject task = new JSONObject();
        task.put("id", UUID.randomUUID().toString());
        task.put("title", parts[1].trim());
        task.put("details", parts[2].trim());
        task.put("due", Long.parseLong(parts[3].trim()));
        task.put("status", "pending");
        task.put("created", System.currentTimeMillis());
        tasks.put(task);
        saveTasks(tasks);
        return PluginResult.success("Created task: " + task.getString("id"), elapsed(start));
    }

    private PluginResult listDue(String[] parts, long start) throws Exception {
        if (parts.length < 2) {
            return PluginResult.error("Usage: due|nowEpochMs", elapsed(start));
        }
        long now = Long.parseLong(parts[1].trim());
        JSONArray tasks = getTasks();
        JSONArray due = new JSONArray();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            if ("pending".equals(task.optString("status")) && task.optLong("due") <= now) {
                due.put(task);
            }
        }
        return PluginResult.success(due.toString(), elapsed(start));
    }

    private PluginResult runTask(String[] parts, long start) throws Exception {
        if (parts.length < 2) {
            return PluginResult.error("Usage: run|taskId", elapsed(start));
        }
        JSONArray tasks = getTasks();
        String id = parts[1].trim();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            if (id.equals(task.optString("id"))) {
                task.put("status", "running");
                task.put("lastRun", System.currentTimeMillis());
                saveTasks(tasks);
                return PluginResult.success("Task marked running: " + id, elapsed(start));
            }
        }
        return PluginResult.error("Task not found: " + id, elapsed(start));
    }

    private PluginResult completeTask(String[] parts, long start) throws Exception {
        if (parts.length < 2) {
            return PluginResult.error("Usage: complete|taskId", elapsed(start));
        }
        JSONArray tasks = getTasks();
        String id = parts[1].trim();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            if (id.equals(task.optString("id"))) {
                task.put("status", "completed");
                task.put("completed", System.currentTimeMillis());
                saveTasks(tasks);
                return PluginResult.success("Task completed: " + id, elapsed(start));
            }
        }
        return PluginResult.error("Task not found: " + id, elapsed(start));
    }

    private JSONArray getTasks() {
        String raw = preferences.getString(KEY_TASKS, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveTasks(JSONArray tasks) {
        preferences.edit().putString(KEY_TASKS, tasks.toString()).apply();
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    @Override
    public void initialize(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void destroy() {
        // No-op
    }
}
