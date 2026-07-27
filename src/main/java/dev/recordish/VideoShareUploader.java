package dev.recordish;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Account-free video sharing used by the V1-0.09 collection screen.
 *
 * <p>The official Record-able host uses its chunked API so every request stays
 * below common reverse-proxy limits. Litterbox keeps its native multipart API.
 * Uploads are invoked only after the user presses Share and chooses a host.</p>
 */
public final class VideoShareUploader {
    public static final String RESHAREABLE_BASE_URL =
            "https://re.share-abl.ink";
    public static final int DEFAULT_RETENTION_DAYS = 60;
    public static final int[] RETENTION_DAY_OPTIONS = {7, 30, 60};

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 300_000;
    private static final long CHUNK_SIZE = 64L * 1024L * 1024L;
    private static final int UPLOAD_BUFFER_SIZE = 1024 * 1024;
    private static final int FILE_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;
    private static final String CRLF = "\r\n";

    public static volatile int lastProgressPercent;

    public enum Host {
        RESHAREABLE(
                "re.share-abl.ink",
                RESHAREABLE_BASE_URL + "/upload",
                600L * 1024L * 1024L,
                false,
                "60 days",
                "file",
                false),
        LITTERBOX(
                "Litterbox",
                "https://litterbox.catbox.moe/resources/internals/api.php",
                1024L * 1024L * 1024L,
                true,
                "72h",
                "fileToUpload",
                true);

        public final String displayName;
        public final String endpoint;
        public final long maxBytes;
        public final boolean temporary;
        public final String retention;
        public final String fileFieldName;
        public final boolean requiresCatboxFields;

        Host(
                String displayName,
                String endpoint,
                long maxBytes,
                boolean temporary,
                String retention,
                String fileFieldName,
                boolean requiresCatboxFields) {
            this.displayName = displayName;
            this.endpoint = endpoint;
            this.maxBytes = maxBytes;
            this.temporary = temporary;
            this.retention = retention;
            this.fileFieldName = fileFieldName;
            this.requiresCatboxFields = requiresCatboxFields;
        }

        public String maxSizeLabel() {
            long mebibytes = maxBytes / (1024L * 1024L);
            return mebibytes >= 1024L
                    ? (mebibytes / 1024L) + " GB"
                    : mebibytes + " MB";
        }
    }

    private VideoShareUploader() {
    }

    public static String upload(Path file, Host host) throws IOException {
        return upload(file, host, DEFAULT_RETENTION_DAYS);
    }

    public static String upload(
            Path file,
            Host host,
            int retentionDays) throws IOException {
        requireUsableFile(file, host);

        long size = Files.size(file);
        String fileName = file.getFileName() == null
                ? "recording.mp4"
                : safeMultipartFilename(file.getFileName().toString());

        if (host == Host.RESHAREABLE) {
            return uploadChunked(
                    file,
                    host,
                    fileName,
                    size,
                    retentionDays);
        }
        return uploadMultipart(file, host, fileName, size);
    }

    private static void requireUsableFile(Path file, Host host)
            throws IOException {
        if (file == null || host == null) {
            throw new IOException("Missing file or host.");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("File not found.");
        }
        long size = Files.size(file);
        if (size <= 0L) {
            throw new IOException("File is empty.");
        }
        if (size > host.maxBytes) {
            throw new IOException(
                    "File is "
                            + formatSize(size)
                            + ", over the "
                            + host.maxSizeLabel()
                            + " limit for "
                            + host.displayName
                            + ".");
        }
    }

    private static String uploadMultipart(
            Path file,
            Host host,
            String fileName,
            long size) throws IOException {
        String boundary = newBoundary();
        long contentLength =
                calculateContentLength(host, boundary, fileName, size);
        HttpURLConnection connection = openPost(host.endpoint);
        connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=" + boundary);
        connection.setFixedLengthStreamingMode(contentLength);

        try {
            OutputStream output = connection.getOutputStream();
            try {
                if (host.requiresCatboxFields) {
                    writeField(
                            output,
                            boundary,
                            "reqtype",
                            "fileupload");
                    if (host.temporary && host.retention != null) {
                        writeField(
                                output,
                                boundary,
                                "time",
                                host.retention);
                    }
                }
                writeFileHeader(
                        output,
                        boundary,
                        host.fileFieldName,
                        fileName);
                InputStream input = Files.newInputStream(file);
                try {
                    copyInterruptibly(input, output, -1L);
                } finally {
                    input.close();
                }
                writeUtf8(output, CRLF);
                writeUtf8(output, "--" + boundary + "--" + CRLF);
                output.flush();
            } finally {
                output.close();
            }

            int status = connection.getResponseCode();
            String body = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throwHostError(host, status, body);
            }

            String link = extractShareLink(body);
            if (!isWebLink(link)) {
                throw new IOException(
                        "Unexpected response: " + trimForMessage(body));
            }
            verifyStoredFile(link);
            lastProgressPercent = 100;
            return link;
        } finally {
            connection.disconnect();
        }
    }

    private static String uploadChunked(
            Path file,
            Host host,
            String fileName,
            long size,
            int retentionDays) throws IOException {
        int totalChunks =
                (int) ((size + CHUNK_SIZE - 1L) / CHUNK_SIZE);
        totalChunks = Math.max(1, totalChunks);
        lastProgressPercent = 0;

        String initBody =
                "{\"filename\":\""
                        + jsonEscape(fileName)
                        + "\",\"total_size\":"
                        + size
                        + ",\"total_chunks\":"
                        + totalChunks
                        + ",\"retention_days\":"
                        + normalizeRetentionDays(retentionDays)
                        + "}";
        String initResponse = postJson(
                host,
                RESHAREABLE_BASE_URL + "/upload/chunk/init",
                initBody);
        String uploadId =
                extractJsonString(initResponse, "upload_id");
        if (isBlank(uploadId)) {
            throw new IOException(
                    "Upload could not be started: unexpected server response "
                            + trimForMessage(initResponse));
        }
        uploadId = safePathToken(uploadId);

        InputStream input = new BufferedInputStream(
                Files.newInputStream(file),
                FILE_BUFFER_SIZE);
        try {
            for (int index = 0; index < totalChunks; index++) {
                checkInterrupted();
                long chunkLength = Math.min(
                        CHUNK_SIZE,
                        size - (long) index * CHUNK_SIZE);
                uploadChunk(
                        host,
                        RESHAREABLE_BASE_URL
                                + "/upload/chunk/"
                                + uploadId
                                + "/"
                                + index,
                        input,
                        chunkLength,
                        fileName);
                lastProgressPercent =
                        (int) (((index + 1L) * 100L) / totalChunks);
                RecordishMod.LOGGER.info(
                        "Recordish share upload: chunk {}/{} ({}%)",
                        index + 1,
                        totalChunks,
                        lastProgressPercent);
            }
        } finally {
            input.close();
        }

        String response = finalizeUpload(
                host,
                RESHAREABLE_BASE_URL
                        + "/upload/chunk/"
                        + uploadId
                        + "/finalize");
        String link = extractShareLink(response);
        if (!isWebLink(link)) {
            throw new IOException(
                    "Unexpected response after finalizing upload: "
                            + trimForMessage(response));
        }
        lastProgressPercent = 100;
        verifyStoredFile(link);
        return link;
    }

    private static String postJson(
            Host host,
            String endpoint,
            String jsonBody) throws IOException {
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = openPost(endpoint);
        connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
                output.flush();
            } finally {
                output.close();
            }
            int status = connection.getResponseCode();
            String body = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throwHostError(host, status, body);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static void uploadChunk(
            Host host,
            String endpoint,
            InputStream input,
            long chunkLength,
            String fileName) throws IOException {
        String boundary = newBoundary();
        byte[] header = (
                "--"
                        + boundary
                        + CRLF
                        + "Content-Disposition: form-data; name=\"chunk\"; "
                        + "filename=\""
                        + fileName
                        + "\""
                        + CRLF
                        + "Content-Type: application/octet-stream"
                        + CRLF
                        + CRLF).getBytes(StandardCharsets.UTF_8);
        byte[] footer = (
                CRLF
                        + "--"
                        + boundary
                        + "--"
                        + CRLF).getBytes(StandardCharsets.UTF_8);
        long contentLength =
                header.length + chunkLength + footer.length;

        HttpURLConnection connection = openPost(endpoint);
        connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=" + boundary);
        connection.setFixedLengthStreamingMode(contentLength);
        try {
            OutputStream output = connection.getOutputStream();
            try {
                output.write(header);
                copyInterruptibly(input, output, chunkLength);
                output.write(footer);
                output.flush();
            } finally {
                output.close();
            }
            int status = connection.getResponseCode();
            String body = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throwHostError(host, status, body);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String finalizeUpload(
            Host host,
            String endpoint) throws IOException {
        HttpURLConnection connection = openPost(endpoint);
        connection.setFixedLengthStreamingMode(0);
        try {
            connection.getOutputStream().close();
            int status = connection.getResponseCode();
            String body = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throwHostError(host, status, body);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openPost(String endpoint)
            throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setRequestProperty(
                "User-Agent",
                "Recordish (Minecraft mod)");
        return connection;
    }

    private static void copyInterruptibly(
            InputStream input,
            OutputStream output,
            long exactBytes) throws IOException {
        byte[] buffer = new byte[UPLOAD_BUFFER_SIZE];
        long remaining = exactBytes;
        while (exactBytes < 0L || remaining > 0L) {
            checkInterrupted();
            int wanted = exactBytes < 0L
                    ? buffer.length
                    : (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, wanted);
            if (read < 0) {
                if (exactBytes >= 0L && remaining > 0L) {
                    throw new IOException(
                            "Unexpected end of file while uploading.");
                }
                break;
            }
            output.write(buffer, 0, read);
            if (exactBytes >= 0L) {
                remaining -= read;
            }
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Upload cancelled.");
        }
    }

    private static int normalizeRetentionDays(int retentionDays) {
        for (int option : RETENTION_DAY_OPTIONS) {
            if (retentionDays == option) {
                return retentionDays;
            }
        }
        return DEFAULT_RETENTION_DAYS;
    }

    private static long calculateContentLength(
            Host host,
            String boundary,
            String fileName,
            long fileSize) {
        long total = 0L;
        if (host.requiresCatboxFields) {
            total += fieldLength(
                    boundary,
                    "reqtype",
                    "fileupload");
            if (host.temporary && host.retention != null) {
                total += fieldLength(
                        boundary,
                        "time",
                        host.retention);
            }
        }
        total += fileHeaderLength(
                boundary,
                host.fileFieldName,
                fileName);
        total += fileSize;
        total += utf8Length(CRLF);
        total += utf8Length(
                "--" + boundary + "--" + CRLF);
        return total;
    }

    private static long fieldLength(
            String boundary,
            String name,
            String value) {
        return utf8Length("--" + boundary + CRLF)
                + utf8Length(
                        "Content-Disposition: form-data; name=\""
                                + name
                                + "\""
                                + CRLF)
                + utf8Length(CRLF)
                + utf8Length(value)
                + utf8Length(CRLF);
    }

    private static long fileHeaderLength(
            String boundary,
            String name,
            String fileName) {
        return utf8Length("--" + boundary + CRLF)
                + utf8Length(
                        "Content-Disposition: form-data; name=\""
                                + name
                                + "\"; filename=\""
                                + fileName
                                + "\""
                                + CRLF)
                + utf8Length(
                        "Content-Type: application/octet-stream"
                                + CRLF)
                + utf8Length(CRLF);
    }

    private static void writeField(
            OutputStream output,
            String boundary,
            String name,
            String value) throws IOException {
        writeUtf8(output, "--" + boundary + CRLF);
        writeUtf8(
                output,
                "Content-Disposition: form-data; name=\""
                        + name
                        + "\""
                        + CRLF);
        writeUtf8(output, CRLF + value + CRLF);
    }

    private static void writeFileHeader(
            OutputStream output,
            String boundary,
            String name,
            String fileName) throws IOException {
        writeUtf8(output, "--" + boundary + CRLF);
        writeUtf8(
                output,
                "Content-Disposition: form-data; name=\""
                        + name
                        + "\"; filename=\""
                        + fileName
                        + "\""
                        + CRLF);
        writeUtf8(
                output,
                "Content-Type: application/octet-stream"
                        + CRLF
                        + CRLF);
    }

    private static void writeUtf8(OutputStream output, String text)
            throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String readResponse(
            HttpURLConnection connection,
            int status) {
        InputStream stream = null;
        try {
            stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (stream == null) {
                return "";
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            stream,
                            StandardCharsets.UTF_8));
            try {
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (result.length() > 0) {
                        result.append('\n');
                    }
                    int room = MAX_RESPONSE_CHARS - result.length();
                    if (room <= 0) {
                        break;
                    }
                    result.append(
                            line,
                            0,
                            Math.min(room, line.length()));
                }
                return result.toString();
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void verifyStoredFile(String link)
            throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)
                        new URL(link).openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(CONNECT_TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setRequestProperty(
                        "User-Agent",
                        "Recordish (Minecraft mod)");
                int status = connection.getResponseCode();
                long stored = connection.getContentLengthLong();
                if (status >= 200 && status < 400 && stored != 0L) {
                    return;
                }
                if (status >= 200 && status < 400) {
                    lastError = new IOException(
                            "The upload host saved an empty (0 byte) file. "
                                    + "Please try again or choose the other host.");
                } else {
                    lastError = new IOException(
                            "The upload host is not serving the file "
                                    + "(HTTP "
                                    + status
                                    + ").");
                }
            } catch (IOException exception) {
                lastError = exception;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            if (attempt < 2) {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Upload verification cancelled.",
                            interrupted);
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    private static void throwHostError(
            Host host,
            int status,
            String body) throws IOException {
        String friendly = friendlyHostError(host, status, body);
        if (friendly != null) {
            throw new IOException(friendly);
        }
        throw new IOException(
                "Host returned HTTP "
                        + status
                        + (isBlank(body)
                                ? "."
                                : ": " + trimForMessage(body)));
    }

    private static String friendlyHostError(
            Host host,
            int status,
            String body) {
        String lower = body == null
                ? ""
                : body.toLowerCase(Locale.ROOT);
        boolean cloudflareChallenge =
                lower.contains("just a moment")
                        || lower.contains("challenges.cloudflare.com")
                        || lower.contains("cf-mitigated")
                        || lower.contains("enable javascript and cookies")
                        || (status == 403
                                && lower.contains("cloudflare"));
        if (cloudflareChallenge
                || (status == 403 && host == Host.RESHAREABLE)) {
            String alternative = host == Host.RESHAREABLE
                    ? "Litterbox"
                    : "re.share-abl.ink";
            return host.displayName
                    + " is blocking uploads behind a Cloudflare "
                    + "challenge (HTTP 403). Use "
                    + alternative
                    + " or try again later.";
        }
        if (lower.contains("invalid uploader")
                || status == 412
                || status == 502
                || status == 503) {
            String alternative = host == Host.RESHAREABLE
                    ? "Litterbox"
                    : "re.share-abl.ink";
            return host.displayName
                    + " is having problems right now (HTTP "
                    + status
                    + "). Try later or use "
                    + alternative
                    + ".";
        }
        return null;
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped =
                new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            switch (value) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (value < 0x20) {
                        escaped.append(String.format(
                                Locale.ROOT,
                                "\\u%04x",
                                (int) value));
                    } else {
                        escaped.append(value);
                    }
            }
        }
        return escaped.toString();
    }

    private static String extractJsonString(
            String json,
            String key) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int index = json.indexOf(needle);
        if (index < 0) {
            return null;
        }
        index = json.indexOf(':', index + needle.length());
        if (index < 0) {
            return null;
        }
        index++;
        while (index < json.length()
                && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        if (index >= json.length() || json.charAt(index) != '"') {
            return null;
        }
        index++;
        StringBuilder value = new StringBuilder();
        while (index < json.length()) {
            char current = json.charAt(index++);
            if (current == '"') {
                return value.toString();
            }
            if (current == '\\' && index < json.length()) {
                char escaped = json.charAt(index++);
                if (escaped == 'n') {
                    value.append('\n');
                } else if (escaped == 'r') {
                    value.append('\r');
                } else if (escaped == 't') {
                    value.append('\t');
                } else {
                    value.append(escaped);
                }
            } else {
                value.append(current);
            }
        }
        return null;
    }

    private static String extractShareLink(String body) {
        if (body == null) {
            return "";
        }
        String cleaned = body.trim();
        int http = cleaned.indexOf("http://");
        int https = cleaned.indexOf("https://");
        int start;
        if (http >= 0 && https >= 0) {
            start = Math.min(http, https);
        } else {
            start = Math.max(http, https);
        }
        if (start < 0) {
            return cleaned;
        }
        String candidate = cleaned.substring(start).trim();
        int end = candidate.length();
        for (int index = 0; index < candidate.length(); index++) {
            if (Character.isWhitespace(candidate.charAt(index))) {
                end = index;
                break;
            }
        }
        candidate = candidate.substring(0, end);
        int secondHttp = candidate.indexOf("http://", 7);
        int secondHttps = candidate.indexOf("https://", 8);
        int split = smallestPositive(secondHttp, secondHttps);
        if (split > 0) {
            candidate = candidate.substring(0, split);
        }
        return candidate.replace("\"", "").trim();
    }

    private static int smallestPositive(int left, int right) {
        if (left > 0 && right > 0) {
            return Math.min(left, right);
        }
        return Math.max(left, right);
    }

    private static String newBoundary() {
        return "----RecordishShareBoundary"
                + Long.toHexString(System.nanoTime())
                + Long.toHexString(
                        Thread.currentThread().getId());
    }

    private static String safeMultipartFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "recording.mp4";
        }
        return filename
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('"', '_')
                .replace('\\', '_')
                .replace('/', '_');
    }

    private static String safePathToken(String token)
            throws IOException {
        if (token == null
                || !token.matches("[A-Za-z0-9._~-]+")) {
            throw new IOException(
                    "Upload server returned an invalid upload ID.");
        }
        return token;
    }

    private static boolean isWebLink(String link) {
        if (link == null) {
            return false;
        }
        String lower = link.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://")
                || lower.startsWith("http://");
    }

    private static String trimForMessage(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace('\n', ' ').trim();
        return cleaned.length() > 120
                ? cleaned.substring(0, 117) + "..."
                : cleaned;
    }

    private static String formatSize(long bytes) {
        double mebibytes = bytes / (1024.0D * 1024.0D);
        if (mebibytes >= 1024.0D) {
            return String.format(
                    Locale.ROOT,
                    "%.2f GB",
                    mebibytes / 1024.0D);
        }
        return String.format(
                Locale.ROOT,
                "%.1f MB",
                mebibytes);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
