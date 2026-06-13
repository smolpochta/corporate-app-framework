/**
 * Модуль для фоновой загрузки файлов из различных источников.
 * Поддерживает HTTP/HTTPS, SMB (Windows shares) и локальные файлы.
 *
 * Copyright (c) 2025 Алексей smolpochta
 * Email: smolpochta@gmail.com
 *
 */

package com.smolpochta.decanter;

import android.content.Context;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import java.util.EnumSet;

/**
 * Класс для загрузки файлов из различных источников в фоновом режиме.
 */
public class DownloadFileTask {
    private static final String TAG = "DownloadFileTask";
    private static final String GOOGLE_DRIVE_HOST = "drive=";

    private final Context context;
    private final SeanceDataStorage dataStorage;
    private final FileStorageManager fileStorage;

    private volatile boolean isRunning = false;
    private HttpURLConnection connection;
    private java.io.File tempFile;

    public DownloadFileTask(Context context, SeanceDataStorage dataStorage,
                            FileStorageManager fileStorage) {
        this.context = context.getApplicationContext();
        this.dataStorage = dataStorage;
        this.fileStorage = fileStorage;
    }

    /**
     * Основной метод запуска загрузки файла.
     */
    public void execute() {
        Log.d(TAG, "Запуск execute(), isRunning: " + isRunning);

        if (isRunning) {
            Log.w(TAG, "Загрузка уже выполняется");
            return;
        }

        isRunning = true;
        long startTime = System.currentTimeMillis();

        try {
            initializeDownloadParameters(startTime);
            performFileDownload();
        } catch (Exception e) {
            Log.e(TAG, "Критическая ошибка в загрузке файла", e);
            handleDownloadError(1, "Непредвиденная системная ошибка: " + e.getMessage());
        } finally {
            isRunning = false;
            cleanupResources();
        }
    }

    /**
     * Инициализация параметров загрузки.
     */
    private void initializeDownloadParameters(long startTime) {
        dataStorage.put("DownloadFile_start", startTime);
        dataStorage.put("DownloadFile_stop", 0L);
        dataStorage.put("DownloadFile_errorCode", 0);
        dataStorage.put("DownloadFile_errorText", "");
        dataStorage.put("DownloadFile_totalSize", 0L);
        dataStorage.put("DownloadFile_downloadedSize", 0L);
        dataStorage.put("DownloadFile_progress", 0);
        dataStorage.put("DownloadFile_speed", 0L);
        dataStorage.put("DownloadFile_elapsedTime", 0L);
        dataStorage.put("DownloadFile_remainingTime", 0L);
    }

    /**
     * Основная логика загрузки файла с проверкой кэша.
     */
    private void performFileDownload() {
        try {
            String from = dataStorage.getString("DownloadFile_from");
            int to = dataStorage.getInt("DownloadFile_to");
            String folder = dataStorage.getString("DownloadFile_folder");
            long expires = dataStorage.getLong("DownloadFile_expires");
            String fileName = dataStorage.getString("DownloadFile_fileName");

            String downloadFileStatus = dataStorage.getString("DownloadFile_status");
            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress_visible", true);
                dataStorage.put("Progress", dataStorage.getInt("DownloadFile_downloadProgressStart"));
                dataStorage.put("Progress_status", downloadFileStatus + ": проверка кэша");
                updateMessage(downloadFileStatus + ": проверка кэша");
            }

            JSONObject getResult;
            if (fileName != null && !fileName.isEmpty()) {
                getResult = fileStorage.getFile(to, fileName, folder);
            } else {
                String extractedName = fileStorage.extractFileName(from);
                getResult = fileStorage.getFile(to, extractedName, folder);
            }

            if (getResult.optBoolean("success", false)) {
                handleExistingFile(getResult);
                return;
            }

            startFileDownload(from, to, folder, expires, fileName);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации загрузки", e);
            handleDownloadError(3, "Ошибка подготовки загрузки: " + e.getMessage());
        }
    }

    /**
     * Обработка файла, существующего в кэше.
     */
    private void handleExistingFile(JSONObject getResult) {
        try {
            String filePath = getResult.getString("path");
            dataStorage.put("DownloadFile_targetPath", filePath);
            dataStorage.put("DownloadFile_stop", System.currentTimeMillis());
            dataStorage.put("DownloadFile_progress", 100);

            String downloadFileStatus = dataStorage.getString("DownloadFile_status");
            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress", dataStorage.getInt("DownloadFile_downloadProgressStop"));
                dataStorage.put("Progress_status", downloadFileStatus + ": уже загружено");
                updateMessage(downloadFileStatus + ": уже загружено");
            }

            Log.i(TAG, "Файл уже существует в кэше: " + filePath);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки существующего файла", e);
            handleDownloadError(4, "Ошибка обработки кэшированного файла: " + e.getMessage());
        }
    }

    /**
     * Параметры SMB URL.
     */
    private static class SmbUrl {
        String username;
        String password;
        String server;
        int port = 445;
        String shareName;
        String filePath;
        String domain = "";
    }

    /**
     * Определение типа источника и запуск соответствующей загрузки.
     */
    private void startFileDownload(String from, int to, String folder, long expires, String fileName) {
        try {
            if (fileStorage.isSmbSource(from)) {
                downloadFromSmbSource(from, to, folder, expires, fileName);
            } else if (fileStorage.isUrl(from) || isGoogleDriveUrl(from)) {
                downloadFromUrlSource(from, to, folder, expires, fileName);
            } else {
                downloadFromLocalSource(from, to, folder, expires, fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка анализа источника", e);
            handleDownloadError(5, "Ошибка определения типа источника: " + e.getMessage());
        }
    }

    /**
     * Загрузка файла из HTTP/HTTPS URL.
     */
    private void downloadFromUrlSource(String from, int to, String folder, long expires, String fileName) {
        try {
            tempFile = fileStorage.getTempFile(FileStorageManager.STORAGE_WORKING,
                    "backgroundDownload", fileName);
            if (tempFile == null) {
                handleDownloadError(5, "Не удалось создать временный файл");
                return;
            }

            String downloadUrl = from;

            if (isGoogleDriveUrl(from)) {
                try {
                    Log.i(TAG, "Обработка Google Drive ссылки");
                    downloadUrl = processGoogleDriveLink(from);
                    Log.i(TAG, "Google Drive ссылка обработана");
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обработки Google Drive ссылки", e);
                    handleDownloadError(15, "Ошибка обработки Google Drive ссылки: " + e.getMessage());
                    return;
                }
            }

            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            connection.setRequestProperty("Accept", "*/*");

            long totalSize = connection.getContentLengthLong();
            if (totalSize <= 0) {
                String contentRange = connection.getHeaderField("Content-Range");
                if (contentRange != null) {
                    String[] parts = contentRange.split("/");
                    if (parts.length > 1) {
                        totalSize = Long.parseLong(parts[1]);
                    }
                }
            }

            dataStorage.put("DownloadFile_totalSize", totalSize);
            if (totalSize > 0) {
                Log.i(TAG, "Размер файла: " + formatFileSize(totalSize));
            }

            downloadFileWithProgress(from, to, folder, expires, fileName);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка настройки HTTP соединения", e);
            handleDownloadError(12, "Ошибка настройки соединения: " + e.getMessage());
        }
    }

    /**
     * Загрузка файла из SMB источника.
     */
    private void downloadFromSmbSource(String from, int to, String folder, long expires, String fileName) {
        SMBClient smbClient = null;
        Connection smbConnection = null;
        Session session = null;
        DiskShare share = null;
        com.hierynomus.smbj.share.File smbFile = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;

        long startTime = System.currentTimeMillis();

        try {
            tempFile = fileStorage.getTempFile(FileStorageManager.STORAGE_WORKING, "banners", fileName);
            if (tempFile == null) {
                handleDownloadError(5, "Не удалось создать временный файл");
                return;
            }

            SmbUrl parsedUrl = parseSmbUrl(from);
            if (parsedUrl == null) {
                handleDownloadError(9, "Неверный формат SMB URL: " + maskPasswordInUrl(from));
                return;
            }

            Log.i(TAG, "Подключение к SMB: " + parsedUrl.server + ":" + parsedUrl.port +
                    " share: " + parsedUrl.shareName);

            smbClient = new SMBClient();
            smbConnection = smbClient.connect(parsedUrl.server, parsedUrl.port);

            com.hierynomus.smbj.auth.AuthenticationContext authContext =
                    new com.hierynomus.smbj.auth.AuthenticationContext(
                            parsedUrl.username,
                            parsedUrl.password.toCharArray(),
                            parsedUrl.domain
                    );

            session = smbConnection.authenticate(authContext);
            share = (DiskShare) session.connectShare(parsedUrl.shareName);

            if (!share.fileExists(parsedUrl.filePath)) {
                handleDownloadError(9, "SMB файл не существует: " + parsedUrl.filePath);
                return;
            }

            smbFile = share.openFile(
                    parsedUrl.filePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.noneOf(FileAttributes.class),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions.class)
            );

            long totalSize = smbFile.getFileInformation().getStandardInformation().getEndOfFile();
            dataStorage.put("DownloadFile_totalSize", totalSize);

            Log.i(TAG, "Размер SMB файла: " + formatFileSize(totalSize));

            inputStream = smbFile.getInputStream();
            outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long downloadedSize = 0;
            long lastUpdateTime = startTime;

            String downloadFileStatus = dataStorage.getString("DownloadFile_status");
            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress_status", downloadFileStatus + ": загрузка");
                updateMessage(downloadFileStatus + ": загрузка");
            }

            while ((bytesRead = inputStream.read(buffer)) != -1 && isRunning) {
                outputStream.write(buffer, 0, bytesRead);
                downloadedSize += bytesRead;

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUpdateTime >= 100 || bytesRead == -1) {
                    updateDownloadProgress(downloadedSize, startTime, currentTime);
                    lastUpdateTime = currentTime;
                }
            }

            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress", dataStorage.getInt("DownloadFile_downloadProgressStop"));
                dataStorage.put("Progress_status", downloadFileStatus + ": загрузка.. 100%");
                updateMessage(downloadFileStatus + ": загрузка.. 100%");
            }

            if (isRunning) {
                completeFileDownload(tempFile.getAbsolutePath(), to, folder, expires, fileName);
                Log.i(TAG, "SMB файл загружен: " + downloadedSize + " байт");
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка загрузки из SMB", e);
            handleDownloadError(10, "Ошибка загрузки из SMB: " + e.getMessage());
        } finally {
            closeStream(inputStream);
            closeStream(outputStream);
            closeSmbResources(smbFile, share, session, smbConnection, smbClient);
        }
    }

    /**
     * Парсинг SMB URL.
     */
    private SmbUrl parseSmbUrl(String smbUrl) {
        try {
            Pattern pattern = Pattern.compile("smb://(?:([^:]+):([^@]+)@)?([^/]+)/([^/]+)/(.+)");
            Matcher matcher = pattern.matcher(smbUrl);

            if (matcher.matches()) {
                SmbUrl result = new SmbUrl();
                result.username = matcher.group(1) != null ? matcher.group(1) : "";
                result.password = matcher.group(2) != null ? matcher.group(2) : "";
                result.server = matcher.group(3);
                result.shareName = matcher.group(4);
                result.filePath = "/" + matcher.group(5);
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка парсинга SMB URL", e);
        }
        return null;
    }

    /**
     * Маскирование пароля в URL для логов.
     */
    private String maskPasswordInUrl(String url) {
        return url.replaceAll("smb://[^:]+:[^@]+@", "smb://***:***@");
    }

    /**
     * Закрытие SMB ресурсов.
     */
    private void closeSmbResources(com.hierynomus.smbj.share.File smbFile, DiskShare share, Session session,
                                   Connection connection, SMBClient smbClient) {
        if (smbFile != null) {
            try { smbFile.close(); } catch (Exception e) { Log.w(TAG, "Ошибка закрытия SMB файла", e); }
        }
        if (share != null) {
            try { share.close(); } catch (Exception e) { Log.w(TAG, "Ошибка закрытия SMB шары", e); }
        }
        if (session != null) {
            try { session.close(); } catch (Exception e) { Log.w(TAG, "Ошибка закрытия SMB сессии", e); }
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception e) { Log.w(TAG, "Ошибка закрытия SMB соединения", e); }
        }
        if (smbClient != null) {
            try { smbClient.close(); } catch (Exception e) { Log.w(TAG, "Ошибка закрытия SMB клиента", e); }
        }
    }

    /**
     * Проверка Google Drive ссылки.
     */
    private boolean isGoogleDriveUrl(String url) {
        return url != null && url.contains(GOOGLE_DRIVE_HOST);
    }

    /**
     * Обработка Google Drive ссылки.
     */
    private String processGoogleDriveLink(String from) throws IOException {
        String downloadUrl = from.replace("drive=", "https://drive.google.com/uc?export=download&id=");
        HttpURLConnection Connection = null;
        int progressStep = 0;
        int downloadProgressStart = 0;

        try {
            String downloadFileStatus = dataStorage.getString("DownloadFile_status");

            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress_status", downloadFileStatus + ": запрос");
                updateMessage(downloadFileStatus + ": запрос");
                downloadProgressStart = dataStorage.getInt("DownloadFile_downloadProgressStart");
                int downloadProgressStop = dataStorage.getInt("DownloadFile_downloadProgressStop");
                int progressRange = downloadProgressStop - downloadProgressStart;
                progressStep = progressRange / 5;
                if (progressStep < 1) progressStep = 1;
            }

            URL url = new URL(downloadUrl);
            Connection = (HttpURLConnection) url.openConnection();
            Connection.setRequestMethod("GET");
            Connection.setConnectTimeout(15000);
            Connection.setReadTimeout(30000);
            Connection.setInstanceFollowRedirects(false);
            Connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            Connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            Connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");

            int responseCode = Connection.getResponseCode();
            String location = Connection.getHeaderField("Location");

            if (responseCode != HttpURLConnection.HTTP_SEE_OTHER || location == null) {
                throw new IOException("Некорректный ответ от Google Drive");
            }

            downloadFileStatus = dataStorage.getString("DownloadFile_status");
            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress_status", downloadFileStatus + ": редирект");
                updateMessage(downloadFileStatus + ": редирект");
                dataStorage.put("DownloadFile_downloadProgressStart", downloadProgressStart + progressStep);
                dataStorage.put("Progress", downloadProgressStart + progressStep);
            }

            url = new URL(location);
            Connection = (HttpURLConnection) url.openConnection();
            responseCode = Connection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Некорректный второй ответ от Google Drive");
            }

            if (Connection.getHeaderField("Content-Type").startsWith("text/html")) {
                downloadFileStatus = dataStorage.getString("DownloadFile_status");
                if (!downloadFileStatus.isEmpty()) {
                    dataStorage.put("Progress_status", downloadFileStatus + ": подтверждение");
                    updateMessage(downloadFileStatus + ": подтверждение");
                    dataStorage.put("DownloadFile_downloadProgressStart", downloadProgressStart + 2 * progressStep);
                    dataStorage.put("Progress", downloadProgressStart + 2 * progressStep);
                }

                url = new URL(location);
                Connection = (HttpURLConnection) url.openConnection();
                responseCode = Connection.getResponseCode();

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Ошибка получения страницы подтверждения: " + responseCode);
                }

                String htmlContent = readHtmlResponse(Connection);
                String uuid = extractHiddenFieldValue(htmlContent, "uuid");

                if (uuid == null) {
                    Map<String, String> allHiddenFields = extractAllHiddenFields(htmlContent);
                    uuid = allHiddenFields.get("uuid");
                    if (uuid == null) {
                        throw new IOException("Не удалось найти параметры подтверждения");
                    }
                }

                downloadFileStatus = dataStorage.getString("DownloadFile_status");
                if (!downloadFileStatus.isEmpty()) {
                    dataStorage.put("Progress_status", downloadFileStatus + ": загрузка..");
                    updateMessage(downloadFileStatus + ": загрузка..");
                    dataStorage.put("DownloadFile_downloadProgressStart", downloadProgressStart + 3 * progressStep);
                    dataStorage.put("Progress", downloadProgressStart + 3 * progressStep);
                }

                return location + "&confirm=t" + "&uuid=" + uuid;
            } else {
                return location;
            }
        } finally {
            if (Connection != null) {
                Connection.disconnect();
            }
        }
    }

    /**
     * Извлечение значения скрытого поля из HTML.
     */
    private String extractHiddenFieldValue(String html, String fieldName) {
        try {
            String pattern = "name\\s*=\\s*[\"']" +
                    Pattern.quote(fieldName) +
                    "[\"'][^>]*value\\s*=\\s*[\"']([^\"']*)[\"']";

            Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = regex.matcher(html);

            if (matcher.find()) {
                return matcher.group(1);
            }

            String altPattern = "value\\s*=\\s*[\"']([^\"']*)[\"'][^>]*name\\s*=\\s*[\"']" +
                    Pattern.quote(fieldName) + "[\"']";

            Pattern altRegex = Pattern.compile(altPattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher altMatcher = altRegex.matcher(html);

            if (altMatcher.find()) {
                return altMatcher.group(1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка извлечения поля " + fieldName, e);
        }
        return null;
    }

    /**
     * Извлечение всех скрытых полей из HTML.
     */
    private Map<String, String> extractAllHiddenFields(String html) {
        Map<String, String> fields = new HashMap<>();
        try {
            Pattern pattern = Pattern.compile(
                    "<input[^>]*type\\s*=\\s*[\"']hidden[\"'][^>]*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher matcher = pattern.matcher(html);

            while (matcher.find()) {
                String inputTag = matcher.group();
                String name = extractAttributeFromTag(inputTag, "name");
                String value = extractAttributeFromTag(inputTag, "value");
                if (name != null && value != null) {
                    fields.put(name, value);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка извлечения скрытых полей", e);
        }
        return fields;
    }

    /**
     * Извлечение атрибута из HTML тега.
     */
    private String extractAttributeFromTag(String tag, String attrName) {
        try {
            String[] patterns = {
                    attrName + "=\"([^\"]*)\"",
                    attrName + "='([^']*)'",
                    attrName + "=([^\\s>'\"]+)"
            };

            for (String pattern : patterns) {
                Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                Matcher matcher = regex.matcher(tag);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка извлечения атрибута " + attrName, e);
        }
        return null;
    }

    /**
     * Чтение HTML ответа.
     */
    private String readHtmlResponse(HttpURLConnection conn) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(conn.getInputStream(), 8192);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        int totalBytes = 0;

        try {
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                if (totalBytes > 1024 * 1024) {
                    break;
                }
            }
            return bos.toString("UTF-8");
        } finally {
            try { bis.close(); } catch (IOException e) { /* игнорируем */ }
            try { bos.close(); } catch (IOException e) { /* игнорируем */ }
        }
    }

    /**
     * Загрузка файла с отслеживанием прогресса.
     */
    private void downloadFileWithProgress(String from, int to, String folder, long expires, String fileName) {
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        long startTime = System.currentTimeMillis();

        try {
            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long downloadedSize = 0;
            long lastUpdateTime = startTime;

            String downloadFileStatus = dataStorage.getString("DownloadFile_status");
            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress_status", downloadFileStatus + ": загрузка");
                updateMessage(downloadFileStatus + ": загрузка");
            }

            while ((bytesRead = inputStream.read(buffer)) != -1 && isRunning) {
                outputStream.write(buffer, 0, bytesRead);
                downloadedSize += bytesRead;

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUpdateTime >= 100 || bytesRead == -1) {
                    updateDownloadProgress(downloadedSize, startTime, currentTime);
                    lastUpdateTime = currentTime;
                }
            }

            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress", dataStorage.getInt("DownloadFile_downloadProgressStop"));
                dataStorage.put("Progress_status", downloadFileStatus + ": загрузка..100%");
                updateMessage(downloadFileStatus + ": загрузка..100%");
            }

            if (isRunning) {
                completeFileDownload(tempFile.getAbsolutePath(), to, folder, expires, fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка в процессе загрузки", e);
            handleDownloadError(6, "Ошибка передачи данных: " + e.getMessage());
        } finally {
            closeStream(inputStream);
            closeStream(outputStream);
        }
    }

    /**
     * Загрузка из локального источника.
     */
    private void downloadFromLocalSource(String from, int to, String folder, long expires, String fileName) {
        try {
            JSONObject saveResult = fileStorage.saveFile(from, to, folder, expires, fileName);

            if (saveResult.optBoolean("success", false)) {
                String targetPath = saveResult.getString("path");
                dataStorage.put("DownloadFile_targetPath", targetPath);
                dataStorage.put("DownloadFile_stop", System.currentTimeMillis());
                dataStorage.put("DownloadFile_progress", 100);

                String downloadFileStatus = dataStorage.getString("DownloadFile_status");
                if (!downloadFileStatus.isEmpty()) {
                    dataStorage.put("Progress", dataStorage.getInt("DownloadFile_downloadProgressStop"));
                    dataStorage.put("Progress_status", downloadFileStatus + ": загружено");
                    updateMessage(downloadFileStatus + ": загружено");
                }

                Log.i(TAG, "Локальный файл обработан: " + targetPath);
            } else {
                String errorMessage = saveResult.optString("message", "Неизвестная ошибка сохранения");
                handleDownloadError(13, "Ошибка сохранения локального файла: " + errorMessage);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки локального файла", e);
            handleDownloadError(14, "Ошибка обработки локального файла: " + e.getMessage());
        }
    }

    /**
     * Обновление прогресса загрузки.
     */
    private void updateDownloadProgress(long downloadedSize, long startTime, long currentTime) {
        try {
            long totalSize = dataStorage.getLong("DownloadFile_totalSize");

            int progress = 0;
            if (totalSize > 0) {
                progress = (int) ((downloadedSize * 100) / totalSize);
                progress = Math.min(progress, 100);
            }

            long elapsedTime = currentTime - startTime;
            long speed = 0;
            if (elapsedTime > 0) {
                speed = (downloadedSize * 1000) / elapsedTime;
            }

            long remainingTime = 0;
            if (speed > 0 && totalSize > 0) {
                long remainingBytes = totalSize - downloadedSize;
                remainingTime = (remainingBytes * 1000) / speed;
            }

            dataStorage.put("DownloadFile_downloadedSize", downloadedSize);
            dataStorage.put("DownloadFile_progress", progress);
            dataStorage.put("DownloadFile_speed", speed);
            dataStorage.put("DownloadFile_elapsedTime", elapsedTime);
            dataStorage.put("DownloadFile_remainingTime", remainingTime);

            String downloadFileStatus = dataStorage.getString("DownloadFile_status");
            if (!downloadFileStatus.isEmpty()) {
                int downloadProgressStart = dataStorage.getInt("DownloadFile_downloadProgressStart");
                int downloadProgressStop = dataStorage.getInt("DownloadFile_downloadProgressStop");
                int overallProgress = downloadProgressStart +
                        (int) (progress * (downloadProgressStop - downloadProgressStart)) / 100;

                dataStorage.put("Progress", overallProgress);
                dataStorage.put("Progress_status", downloadFileStatus + ": загрузка.." + progress + "%");
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обновления прогресса", e);
        }
    }

    /**
     * Завершение успешной загрузки.
     */
    private void completeFileDownload(String from, int to, String folder, long expires, String fileName) {
        try {
            String downloadFileStatus = dataStorage.getString("DownloadFile_status");
            if (!downloadFileStatus.isEmpty()) {
                dataStorage.put("Progress_status", downloadFileStatus + ": сохранение");
                updateMessage(downloadFileStatus + ": сохранение");
            }

            JSONObject saveResult = fileStorage.saveFile(from, to, folder, expires, fileName);

            if (saveResult.optBoolean("success", false)) {
                String targetPath = saveResult.getString("path");
                dataStorage.put("DownloadFile_targetPath", targetPath);
                dataStorage.put("DownloadFile_stop", System.currentTimeMillis());
                dataStorage.put("DownloadFile_progress", 100);

                if (!downloadFileStatus.isEmpty()) {
                    dataStorage.put("Progress", dataStorage.getInt("DownloadFile_downloadProgressStop"));
                    dataStorage.put("Progress_status", downloadFileStatus + ": завершено");
                    updateMessage(downloadFileStatus + ": завершено");
                }

                Log.i(TAG, "Файл загружен и сохранен: " + targetPath);
            } else {
                String errorMessage = saveResult.optString("message", "Неизвестная ошибка сохранения");
                handleDownloadError(7, "Ошибка сохранения файла: " + errorMessage);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка завершения загрузки", e);
            handleDownloadError(8, "Ошибка завершения загрузки: " + e.getMessage());
        }
    }

    /**
     * Обработка ошибок загрузки.
     */
    private void handleDownloadError(int errorCode, String errorText) {
        dataStorage.put("DownloadFile_errorCode", errorCode);
        dataStorage.put("DownloadFile_errorText", errorText);
        dataStorage.put("DownloadFile_stop", System.currentTimeMillis());

        String downloadFileStatus = dataStorage.getString("DownloadFile_status");
        if (!downloadFileStatus.isEmpty()) {
            dataStorage.put("Progress", dataStorage.getInt("DownloadFile_downloadProgressStop"));
            dataStorage.put("Progress_status", downloadFileStatus + ": завершено");
            updateMessage(downloadFileStatus + ": завершено");
        }

        Log.e(TAG, "Ошибка загрузки [" + errorCode + "]: " + errorText);
    }

    /**
     * Очистка ресурсов.
     */
    private void cleanupResources() {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Ошибка при закрытии HTTP соединения", e);
            } finally {
                connection = null;
            }
        }

        if (tempFile != null && tempFile.exists()) {
            try {
                if (tempFile.delete()) {
                    Log.d(TAG, "Временный файл удален: " + tempFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Не удалось удалить временный файл: " + tempFile.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.w(TAG, "Ошибка при удалении временного файла", e);
            } finally {
                tempFile = null;
            }
        }
    }

    /**
     * Безопасное закрытие потока.
     */
    private void closeStream(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception e) {
                Log.w(TAG, "Ошибка при закрытии потока", e);
            }
        }
    }

    /**
     * Форматирование размера файла.
     */
    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format(Locale.getDefault(), "%.1f %s",
                size / Math.pow(1024, digitGroups),
                units[digitGroups]);
    }

    /** Логирование выполнения в сообщении
     *
     */
    private void updateMessage(String message) {

        dataStorage.put("MessageText", dataStorage.getString("MessageText") + "\n"  + message);
        dataStorage.put("MessageCloseIn", System.currentTimeMillis() + 60000L);

    }
}