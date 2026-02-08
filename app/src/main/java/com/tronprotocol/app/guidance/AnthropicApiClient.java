package com.tronprotocol.app.guidance;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Anthropic API client with auth, rate limiting and robust error handling.
 */
public class AnthropicApiClient {
    public static final String MODEL_SONNET = "claude-sonnet-4-5-20250929";
    public static final String MODEL_OPUS = "claude-opus-4-6";

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final Object rateLock = new Object();
    private final Deque<Long> requestTimes = new ArrayDeque<>();
    private final int maxRequestsPerMinute;
    private final long minRequestIntervalMs;
    private long lastRequestMs = 0;

    public AnthropicApiClient(int maxRequestsPerMinute, long minRequestIntervalMs) {
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
        this.minRequestIntervalMs = Math.max(0, minRequestIntervalMs);
    }

    public String createGuidance(String apiKey, String model, String prompt, int maxTokens) throws AnthropicException {
        if (TextUtils.isEmpty(apiKey)) {
            throw new AnthropicException("Anthropic API key is missing", 401, false);
        }
        if (TextUtils.isEmpty(prompt)) {
            throw new AnthropicException("Prompt is empty", 400, false);
        }

        throttle();

        HttpURLConnection connection = null;
        try {
            URL url = new URL(API_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-version", API_VERSION);

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", maxTokens);

            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.put(userMessage);
            body.put("messages", messages);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload);
            }

            int status = connection.getResponseCode();
            String responseBody = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (status >= 400) {
                throw buildError(responseBody, status);
            }

            JSONObject responseJson = new JSONObject(responseBody);
            JSONArray content = responseJson.optJSONArray("content");
            if (content == null || content.length() == 0) {
                throw new AnthropicException("Anthropic response did not contain content", status, true);
            }

            StringBuilder answer = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    answer.append(block.optString("text", ""));
                }
            }

            if (answer.length() == 0) {
                throw new AnthropicException("Anthropic response text was empty", status, true);
            }

            return answer.toString().trim();
        } catch (AnthropicException e) {
            throw e;
        } catch (Exception e) {
            throw new AnthropicException("Failed to call Anthropic API: " + e.getMessage(), 500, true);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void throttle() throws AnthropicException {
        synchronized (rateLock) {
            long now = System.currentTimeMillis();

            while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() > 60000) {
                requestTimes.pollFirst();
            }

            if (requestTimes.size() >= maxRequestsPerMinute) {
                throw new AnthropicException("Rate limit hit in client guardrail", 429, true);
            }

            long sinceLast = now - lastRequestMs;
            if (sinceLast < minRequestIntervalMs) {
                long wait = minRequestIntervalMs - sinceLast;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AnthropicException("Rate limiter interrupted", 429, true);
                }
            }

            long stamp = System.currentTimeMillis();
            requestTimes.addLast(stamp);
            lastRequestMs = stamp;
        }
    }

    private AnthropicException buildError(String body, int status) {
        String msg = "Anthropic API error";
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                msg = error.optString("message", msg);
            }
        } catch (Exception ignored) {
            if (!TextUtils.isEmpty(body)) {
                msg = body;
            }
        }

        boolean retryable = status == 429 || status >= 500;
        return new AnthropicException(msg, status, retryable);
    }

    private String readBody(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    public static class AnthropicException extends Exception {
        private final int statusCode;
        private final boolean retryable;

        public AnthropicException(String message, int statusCode, boolean retryable) {
            super(message);
            this.statusCode = statusCode;
            this.retryable = retryable;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}
