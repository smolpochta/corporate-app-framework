/**
 * FileStorageManager - Менеджер файлового хранилища для управления кэшированием и рабочими файлами
 *
 * Автор: Алексей (smolpochta@gmail.com) @ 2025

 *
 * Основные возможности:
 * - Автоматическое кэширование файлов с временем жизни
 * - Загрузка файлов из интернета и локальной файловой системы
 * - Создание текстовых файлов с кодировкой
 * - Автоматическая очистка просроченных файлов
 * - Валидация входных параметров и кодировок
 *
 * Хранилища:
 * - STORAGE_CACHE: временные файлы (context.getCacheDir())
 * - STORAGE_WORKING: рабочие файлы (context.getFilesDir())
 *
 * Формат имен файлов: %t(ддММггггЧЧммсс)_оригинальное_имя.расширение
 * Пример: %t(24102024143000)_image.jpg
 *
 * Особенности безопасности:
 * - Проверка канонических путей для предотвращения Path Traversal
 * - Валидация URL перед загрузкой
 * - Ограничение максимального размера загружаемых файлов
 * - Проверка поддерживаемых кодировок
 * - Ограничение глубины рекурсии при обходе директорий
 */

package com.smolpochta.decanter;

import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Менеджер файлового хранилища для управления кэшированием и рабочими файлами
 */
public class FileStorageManager {

    // region Константы и статические поля

    /** Единственный экземпляр менеджера (Singleton pattern) */
    private static FileStorageManager instance;

    /** Контекст приложения для доступа к файловой системе */
    private Context context;

    /**
     * Конфигурационные константы для настройки поведения менеджера
     * Вынесены в отдельный вложенный класс для лучшей организации кода
     */
    private static class Config {
        /** Максимальный размер загружаемого файла (30 МБ) */
        static final long MAX_DOWNLOAD_SIZE = 30 * 1024 * 1024;

        /** Таймаут подключения к сети в миллисекундах */
        static final int CONNECT_TIMEOUT_MS = 15000;

        /** Таймаут чтения данных в миллисекундах */
        static final int READ_TIMEOUT_MS = 30000;

        /** Размер буфера для операций ввода-вывода (8 КБ) */
        static final int BUFFER_SIZE = 8192;

        /** Максимальная глубина рекурсии при обходе директорий */
        static final int MAX_DIRECTORY_DEPTH = 10;

        /** Максимальный размер для SMB файлов */
        static final long MAX_SMB_SIZE = 50 * 1024 * 1024;

        /** Максимальное количество попыток создания временного файла */
        static final int MAX_TEMP_FILE_RETRIES = 3;

        /** Расширение временных файлов */
        static final String TEMP_FILE_EXTENSION = ".tmp";
    }

    /**
     * ThreadLocal для SimpleDateFormat обеспечивает потокобезопасность
     * Каждый поток получает свою копию форматера даты для формата временных меток
     */
    private final ThreadLocal<SimpleDateFormat> dateFormatLocal =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("ddMMyyyyHHmmss", Locale.getDefault()));

    /**
     * ThreadLocal для SimpleDateFormat в формате отображения
     * Используется для читаемого представления дат в результатах
     */
    private final ThreadLocal<SimpleDateFormat> displayDateFormatLocal =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()));

    /** Тип хранилища: кэш (временные файлы) */
    public static final int STORAGE_CACHE = 0;

    /** Тип хранилища: рабочие файлы (постоянные) */
    public static final int STORAGE_WORKING = 1;

    /** Источник файла: существующий файл из хранилища */
    private static final String SOURCE_EXISTING = "existing";

    /** Источник файла: вновь созданный файл */
    private static final String SOURCE_CREATED = "created";

    /** Источник файла: ошибка при обработке */
    private static final String SOURCE_ERROR = "error";

    /** Источник файла: исключение при выполнении */
    private static final String SOURCE_EXCEPTION = "exception";

    /** Признак того, что указанный адрес ресурсный (res/raw) */
    private static final String RESOURCE_SCHEME = "resource://";

    /**
     * Паттерн для проверки URL-адресов
     * Определяет протоколы: http, https, ftp
     */
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?|ftp)://.*", Pattern.CASE_INSENSITIVE);

    /**
     * Паттерн для извлечения временной метки из имен файлов
     * Формат: %t(14_цифр)_остальное_имя
     */
    private static final Pattern FILENAME_PATTERN = Pattern.compile("%t\\((\\d{14})\\)_.*");

    /**
     * Паттерн для проверки недопустимых символов в именах файлов
     * Запрещены: < > : " | ? *
     */
    private static final Pattern INVALID_CHARS_PATTERN = Pattern.compile("[<>:\"|?*]");

    /** Префикс сетевого пути для скачивания файлов */
    private static final String SMB_SCHEME = "smb://";

    /**
     * Паттерн для обнаружения Path Traversal атак в SMB путях
     * Ищет последовательности "../", "..\", "..", "~" и подобные
     */
    private static final Pattern PATH_TRAVERSAL_PATTERN =
            Pattern.compile("(\\.\\.(?:[\\\\/]|$)|~|//|[\\\\/]{2,})", Pattern.CASE_INSENSITIVE);

    /**
     * Мапа блокировок для синхронизации доступа к директориям
     * Ключ: абсолютный путь к директории
     * Значение: ReentrantLock для синхронизации операций
     */
    private final ConcurrentHashMap<String, ReentrantLock> directoryLocks = new ConcurrentHashMap<>();

    // endregion

    // region Конструктор и Singleton методы

    /**
     * Приватный конструктор для реализации Singleton
     * @param context контекст приложения
     */
    private FileStorageManager(Context context) {
        // Сохраняем контекст приложения для избежания утечек памяти
        this.context = context.getApplicationContext();
    }

    /**
     * Получение единственного экземпляра менеджера (Singleton)
     * @param context контекст приложения
     * @return единственный экземпляр FileStorageManager
     */
    public static synchronized FileStorageManager getInstance(Context context) {
        if (instance == null) {
            instance = new FileStorageManager(context);
        } else if (instance.context == null) {
            instance = new FileStorageManager(context);
        }
        return instance;
    }

    /**
     * Получение блокировки для конкретной директории
     * Создает новую блокировку при необходимости (thread-safe)
     *
     * @param directoryPath абсолютный путь к директории
     * @return ReentrantLock для синхронизации операций
     */
    private ReentrantLock getDirectoryLock(String directoryPath) {
        return directoryLocks.computeIfAbsent(directoryPath, k -> new ReentrantLock());
    }

    /**
     * Безопасное выполнение операции с блокировкой директории
     * Гарантирует, что операции в одной директории не выполняются параллельно
     *
     * @param directory директория для блокировки
     * @param operation выполняемая операция
     * @param <T> тип возвращаемого значения
     * @return результат операции
     * @throws Exception если операция завершилась с ошибкой
     */
    private <T> T executeWithDirectoryLock(File directory, DirectoryOperation<T> operation) throws Exception {
        ReentrantLock lock = getDirectoryLock(directory.getAbsolutePath());

        // Получаем блокировку с таймаутом (5 секунд) для предотвращения deadlock
        if (lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
            try {
                return operation.execute();
            } finally {
                lock.unlock();
            }
        } else {
            throw new IllegalStateException("Не удалось получить блокировку для директории: " +
                    directory.getAbsolutePath() + " (таймаут 5 секунд)");
        }
    }

    /**
     * Функциональный интерфейс для операций под блокировкой
     */
    @FunctionalInterface
    private interface DirectoryOperation<T> {
        T execute() throws Exception;
    }

    // endregion

    // region Основные публичные методы

    /**
     * Получение существующего файла или создание нового из источника
     *
     * Важное изменение: операция выполняется под блокировкой директории
     * для предотвращения race condition при параллельном доступе
     *
     * Алгоритм работы:
     * 1. Извлекаем оригинальное имя файла из источника
     * 2. Пытаемся найти файл в хранилище
     * 3. Если файл найден и не просрочен - возвращаем его
     * 4. Если файл не найден - создаем из источника
     *
     * @param from источник файла (URL или локальный путь)
     * @param to тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param folder подпапка в хранилище
     * @param timeInMillis время истечения в миллисекундах (с 1970)
     * @param customFileName необязательное кастомное имя файла для поиска/создания
     * @return JSONObject с результатом операции:
     *   - success: boolean - успех операции
     *   - path: string - путь к файлу (при успехе)
     *   - source: string - источник файла
     *   - expires: long - время истечения
     *   - message: string - сообщение об ошибке (при неудаче)
     */
    public JSONObject getOrCreateFile(String from, int to, String folder, long timeInMillis, String customFileName) {
        // Валидация параметров
        JSONObject validationResult = validateParameters(from, to, folder, timeInMillis);
        if (validationResult != null) {
            return validationResult;
        }

        try {
            // Получаем безопасную директорию
            File storageDir = getStorageDirectory(to, folder);

            // Выполняем операцию под блокировкой директории
            return executeWithDirectoryLock(storageDir, () -> {
                // Извлекаем оригинальное имя файла для поиска в хранилище
                String fileName;
                if (customFileName != null && !customFileName.trim().isEmpty()) {
                    fileName = customFileName;
                } else {
                    fileName = extractFileName(from);
                }

                // Пытаемся найти существующий файл
                JSONObject getResult = getFileInternal(to, fileName, folder);

                if (getResult.getBoolean("success")) {
                    // Файл найден - возвращаем успех с источником "existing"
                    return createSuccessResult(
                            getResult.getString("path"),
                            getResult.getLong("expires"),
                            SOURCE_EXISTING
                    );
                } else {
                    // Файл не найден - создаем из источника
                    JSONObject saveResult = saveFileInternal(from, to, folder, timeInMillis, customFileName);
                    if (saveResult.getBoolean("success")) {
                        return createSuccessResult(
                                saveResult.getString("path"),
                                saveResult.getLong("expires"),
                                SOURCE_CREATED
                        );
                    } else {
                        // Ошибка при создании файла
                        return createErrorResult(saveResult.getString("message"), SOURCE_ERROR);
                    }
                }
            });

        } catch (Exception e) {
            // Логируем исключение вместо игнорирования
            logException("getOrCreateFile", e);
            return createErrorResult("Exception in getOrCreateFile: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Универсальная функция для работы с файлами настроек в формате JSON
     *
     * @param to тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param fileName имя файла настроек
     * @param folder подпапка
     * @param param_in JSONObject с параметрами для добавления/обновления (может быть null)
     * @param param_out JSONObject с ключами для удаления (может быть null)
     * @return JSONObject с актуальными настройками после всех операций
     */
    public JSONObject getParams(int to, String fileName, String folder,
                                JSONObject param_in, JSONObject param_out) {

        JSONObject resultJson = new JSONObject();
        File settingsFile = null;
        boolean hasChanges = false;

        try {
            // 1. Получаем директорию и файл настроек
            File storageDir = getStorageDirectory(to, folder);

            // Выполняем операцию под блокировкой директории
            resultJson = executeWithDirectoryLock(storageDir, () -> {
                JSONObject localResult = new JSONObject();

                if (!storageDir.exists() && !storageDir.mkdirs()) {
                    // Не удалось создать директорию - возвращаем пустой JSONObject
                    return localResult;
                }

                File localSettingsFile = new File(storageDir, fileName);

                // 2. Читаем существующий файл если он есть
                if (localSettingsFile.exists() && localSettingsFile.isFile() && localSettingsFile.length() > 0) {
                    try (FileInputStream fis = new FileInputStream(localSettingsFile);
                         InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
                         BufferedReader reader = new BufferedReader(isr)) {

                        StringBuilder content = new StringBuilder();
                        char[] buffer = new char[Config.BUFFER_SIZE];
                        int charsRead;

                        while ((charsRead = reader.read(buffer)) != -1) {
                            content.append(buffer, 0, charsRead);
                        }

                        if (content.length() > 0) {
                            localResult = new JSONObject(content.toString());
                        }
                    } catch (Exception e) {
                        // Если не удалось прочитать или распарсить - начинаем с пустого JSONObject
                        logWarning("Failed to read settings file: " + localSettingsFile.getAbsolutePath() +
                                ", starting with empty JSON");
                        localResult = new JSONObject();
                    }
                }

                boolean localHasChanges = false;

                // 3. Обрабатываем param_in (добавление/обновление параметров)
                if (param_in != null) {
                    Iterator<String> keys = param_in.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = param_in.get(key);
                        // Проверяем, было ли изменение значения
                        if (!localResult.has(key) || !localResult.get(key).equals(value)) {
                            localResult.put(key, value);
                            localHasChanges = true;
                        }
                    }
                }

                // 4. Обрабатываем param_out (удаление параметров)
                if (param_out != null) {
                    Iterator<String> keys = param_out.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (localResult.has(key)) {
                            localResult.remove(key);
                            localHasChanges = true;
                        }
                    }
                }

                // 5. Проверяем результат и сохраняем/удаляем файл
                if (localResult.length() == 0) {
                    // Файл пуст - удаляем если существует
                    if (localSettingsFile.exists() && !localSettingsFile.delete()) {
                        logWarning("Failed to delete empty settings file: " + localSettingsFile.getAbsolutePath());
                    }
                } else if (localHasChanges) {
                    // Есть изменения и файл не пуст - сохраняем транзакционно
                    saveTextFileAtomically(localSettingsFile, localResult.toString(2), "UTF-8");
                }

                return localResult;
            });

        } catch (Exception e) {
            logException("getParams", e);
            // В случае любой ошибки возвращаем то, что успели собрать
        }

        return resultJson;
    }

    /**
     * Поиск файла в указанном хранилище
     *
     * @param to тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param fileName имя файла для поиска (без временной метки)
     * @param folder подпапка для поиска
     * @return JSONObject с результатом:
     *   - success: boolean - найден ли файл
     *   - path: string - полный путь к файлу (при успехе)
     *   - expires: long - время истечения (при успехе)
     *   - message: string - сообщение об ошибке (при неудаче)
     */
    public JSONObject getFile(int to, String fileName, String folder) {
        // Валидация параметров
        if (fileName == null || fileName.trim().isEmpty() || fileName.length() == 0) {
            return createErrorResult("File name cannot be null or empty", SOURCE_ERROR);
        }

        try {
            File storageDir = getStorageDirectory(to, folder);
            return executeWithDirectoryLock(storageDir, () -> getFileInternal(to, fileName, folder));
        } catch (Exception e) {
            logException("getFile", e);
            return createErrorResult("Exception: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Внутренний метод поиска файла (без блокировки)
     */
    private JSONObject getFileInternal(int to, String fileName, String folder) {
        try {
            // Получаем директорию хранилища с проверкой безопасности
            File storageDir = getStorageDirectory(to, folder);

            if (!storageDir.exists() || !storageDir.isDirectory()) {
                return createErrorResult("Directory not exists: " + storageDir.getAbsolutePath(), SOURCE_ERROR);
            }

            // Оптимизированный поиск с использованием FilenameFilter
            // Ищем файлы, заканчивающиеся на указанное имя (учитываем временную метку)
            File[] foundFiles = storageDir.listFiles((dir, name) ->
                    name.endsWith(fileName) && new File(dir, name).isFile()
            );

            if (foundFiles != null && foundFiles.length > 0) {
                // Берем первый найденный файл (самый новый по времени создания)
                File foundFile = foundFiles[0];
                String absolutePath = foundFile.getAbsolutePath();
                return createSuccessResult(
                        absolutePath,
                        parseExpirationTime(extractFileName(absolutePath)),
                        null
                );
            } else {
                return createErrorResult("File not found: " + fileName + " in folder: " + folder, SOURCE_ERROR);
            }

        } catch (Exception e) {
            // Логируем исключение
            logException("getFileInternal", e);
            return createErrorResult("Exception: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Чтение содержимого файла в виде текстовой строки
     *
     * @param to тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param fileName имя файла для чтения (без временной метки)
     * @param folder подпапка для поиска
     * @param encoding кодировка файла (по умолчанию "utf-8")
     * @return JSONObject с результатом операции:
     *   - success: boolean - успех операции
     *   - text: string - содержимое файла как строка (при успехе)
     *   - message: string - сообщение об ошибке (при неудаче)
     */
    public JSONObject getFileAsText(int to, String fileName, String folder, String encoding) {
        // Валидация параметров
        if (fileName == null || fileName.trim().isEmpty()) {
            return createErrorResult("File name cannot be null or empty", SOURCE_ERROR);
        }

        try {
            // Устанавливаем кодировку по умолчанию
            if (encoding == null || encoding.trim().isEmpty()) {
                encoding = "utf-8";
            }

            // Проверяем поддерживается ли указанная кодировка
            if (!isValidEncoding(encoding)) {
                return createErrorResult("Unsupported encoding: " + encoding, SOURCE_ERROR);
            }

            File storageDir = getStorageDirectory(to, folder);

            // Создаем финальную копию переменной для использования в лямбда-выражении
            final String finalEncoding = encoding;

            return executeWithDirectoryLock(storageDir, () -> {
                // Сначала находим файл через существующий метод getFileInternal
                JSONObject getResult = getFileInternal(to, fileName, folder);

                if (!getResult.getBoolean("success")) {
                    // Возвращаем ошибку из getFileInternal
                    return getResult;
                }

                String filePath = getResult.getString("path");
                File file = new File(filePath);

                // Проверяем существование файла
                if (!file.exists() || !file.isFile()) {
                    return createErrorResult("File not found: " + filePath, SOURCE_ERROR);
                }

                // Проверяем размер файла перед чтением (ограничение 10 МБ для текстовых файлов)
                long fileSize = file.length();
                if (fileSize > 10 * 1024 * 1024) { // 10 МБ
                    return createErrorResult("File too large for text reading: " + fileSize + " bytes", SOURCE_ERROR);
                }

                // Читаем содержимое файла с финальной кодировкой
                String fileContent = readFileContent(file, finalEncoding);

                if (fileContent != null) {
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("text", fileContent);
                    return result;
                } else {
                    return createErrorResult("Error reading file content: " + filePath, SOURCE_ERROR);
                }
            });

        } catch (Exception e) {
            // Логируем исключение
            logException("getFileAsText", e);
            return createErrorResult("Exception in getFileAsText: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Чтение текстового файла из папки assets с указанной кодировкой
     *
     * @param fileName имя файла в assets
     * @param encoding кодировка файла (по умолчанию "utf-8")
     * @return JSONObject с результатом операции
     */
    public JSONObject getAssetFileAsText(String fileName, String encoding) {
        // Валидация параметров
        if (fileName == null || fileName.trim().isEmpty()) {
            return createErrorResult("File name cannot be null or empty", SOURCE_ERROR);
        }

        // Устанавливаем кодировку по умолчанию
        if (encoding == null || encoding.trim().isEmpty()) {
            encoding = "utf-8";
        }

        // Проверяем поддерживается ли указанная кодировка
        if (!isValidEncoding(encoding)) {
            return createErrorResult("Unsupported encoding: " + encoding, SOURCE_ERROR);
        }

        InputStream inputStream = null;
        try {
            // Открываем файл из assets
            inputStream = context.getAssets().open(fileName);

            // Читаем содержимое файла
            String fileContent = readStreamContent(inputStream, encoding);

            if (fileContent != null) {
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("text", fileContent);
                return result;
            } else {
                return createErrorResult("Error reading asset file content: " + fileName, SOURCE_ERROR);
            }

        } catch (FileNotFoundException e) {
            return createErrorResult("Asset file not found: " + fileName, SOURCE_ERROR);
        } catch (IOException e) {
            return createErrorResult("Error accessing asset file: " + fileName, SOURCE_ERROR);
        } catch (Exception e) {
            logException("getAssetFileAsText", e);
            return createErrorResult("Exception in getAssetFileAsText: " + e.getMessage(), SOURCE_EXCEPTION);
        } finally {
            // Всегда закрываем поток
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // Игнорируем ошибки при закрытии
                }
            }
        }
    }

    /**
     * Сохранение файла из источника в указанное хранилища
     *
     * Важное изменение: операция выполняется атомарно с использованием временного файла
     *
     * @param from источник файла (URL или локальный путь)
     * @param to тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param folder подпапка для сохранения
     * @param timeInMillis время истечения в миллисекундах
     * @param customFileName если необходимо фиксированно указать имя файла, в который сохраняем
     * @return JSONObject с результатом операции
     */
    public JSONObject saveFile(String from, int to, String folder, long timeInMillis, String customFileName) {
        // Валидация параметров
        JSONObject validationResult = validateParameters(from, to, folder, timeInMillis);
        if (validationResult != null) {
            return validationResult;
        }

        try {
            File storageDir = getStorageDirectory(to, folder);
            return executeWithDirectoryLock(storageDir, () ->
                    saveFileInternal(from, to, folder, timeInMillis, customFileName));
        } catch (Exception e) {
            logException("saveFile", e);
            return createErrorResult("Exception: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Внутренний метод сохранения файла (с атомарной операцией)
     */
    private JSONObject saveFileInternal(String from, int to, String folder, long timeInMillis, String customFileName) {
        try {
            // Получаем безопасную директорию для сохранения
            File storageDir = getStorageDirectory(to, folder);
            if (!storageDir.exists() && !storageDir.mkdirs()) {
                return createErrorResult("Cannot create directory: " + storageDir.getAbsolutePath(), SOURCE_ERROR);
            }

            // Проверяем доступное место перед загрузкой
            if (!hasEnoughSpace(storageDir, Config.MAX_DOWNLOAD_SIZE)) {
                return createErrorResult("Insufficient storage space", SOURCE_ERROR);
            }

            String fileName;
            if (customFileName != null && !customFileName.trim().isEmpty()) {
                fileName = customFileName;
                // Дополнительная валидация кастомного имени файла
                if (!isValidFileName(fileName)) {
                    return createErrorResult("Invalid custom file name: " + fileName, SOURCE_ERROR);
                }
            } else {
                fileName = extractFileName(from);
            }

            // Генерируем имя файла с временной меткой
            String timeStampedFileName = generateFileName(fileName, timeInMillis);
            File destinationFile = new File(storageDir, timeStampedFileName);

            // Удаляем старые версии файла
            deleteOldFileVersions(storageDir, fileName);

            // Обрабатываем источник с атомарностью
            boolean success = processFileSourceAtomically(from, destinationFile);

            if (success) {
                String absolutePath = destinationFile.getAbsolutePath();
                return createSuccessResult(
                        absolutePath,
                        parseExpirationTime(extractFileName(absolutePath)),
                        null
                );
            } else {
                return createErrorResult("Error processing file: " + from, SOURCE_ERROR);
            }

        } catch (Exception e) {
            // Логируем исключение
            logException("saveFileInternal", e);
            return createErrorResult("Exception: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Создание текстового файла из строки с указанной кодировкой
     *
     * @param storageType тип хранилища
     * @param folder подпапка
     * @param fileName имя файла
     * @param timeInMillis время истечения
     * @param text текст для сохранения
     * @param format кодировка файла (по умолчанию "utf-8")
     * @return JSONObject с результатом операции
     */
    public JSONObject createFile(int storageType, String folder, String fileName,
                                 long timeInMillis, String text, String format) {
        // Валидация параметров
        if (fileName == null || fileName.trim().isEmpty()) {
            return createErrorResult("File name cannot be null or empty", SOURCE_ERROR);
        }
        if (text == null) {
            return createErrorResult("Text cannot be null", SOURCE_ERROR);
        }
        // Дополнительная валидация имени файла на недопустимые символы
        if (!isValidFileName(fileName)) {
            return createErrorResult("Invalid file name: " + fileName, SOURCE_ERROR);
        }

        try {
            // Создаем финальные копии переменных для использования в лямбда-выражении
            final String finalFileName = fileName;
            final String finalText = text;
            final String finalFormat = (format == null || format.trim().isEmpty()) ? "utf-8" : format;

            // Проверяем поддерживается ли указанная кодировка
            if (!isValidEncoding(finalFormat)) {
                return createErrorResult("Unsupported encoding: " + finalFormat, SOURCE_ERROR);
            }

            File storageDir = getStorageDirectory(storageType, folder);
            return executeWithDirectoryLock(storageDir, () -> {
                // Создаем безопасную директорию
                if (!storageDir.exists() && !storageDir.mkdirs()) {
                    return createErrorResult("Cannot create directory: " + storageDir.getAbsolutePath(), SOURCE_ERROR);
                }

                // Генерируем имя с временной меткой
                String timeStampedFileName = generateFileName(finalFileName, timeInMillis);
                File newFile = new File(storageDir, timeStampedFileName);

                // Удаляем старые версии файла
                deleteOldFileVersions(storageDir, finalFileName);

                // Сохраняем текст в файл атомарно
                boolean saveSuccess = saveTextFileAtomically(newFile, finalText, finalFormat);
                if (saveSuccess) {
                    String absolutePath = newFile.getAbsolutePath();
                    return createSuccessResult(
                            absolutePath,
                            parseExpirationTime(extractFileName(absolutePath)),
                            null
                    );
                } else {
                    return createErrorResult("Error saving text to file: " + newFile.getAbsolutePath(), SOURCE_ERROR);
                }
            });

        } catch (Exception e) {
            // Логируем исключение
            logException("createFile", e);
            return createErrorResult("Exception in createFile: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Удаление файла из указанного хранилища
     *
     * Алгоритм работы:
     * 1. Валидация входных параметров
     * 2. Получение безопасной директории хранилища
     * 3. Поиск файлов с указанным именем (с учетом временной метки)
     * 4. Удаление всех найденных файлов
     *
     * @param storageType тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param folder подпапка в хранилище
     * @param fileName имя файла для удаления (без временной метки)
     * @return JSONObject с результатом операции:
     *   - success: boolean - успех операции
     *   - deletedCount: int - количество удаленных файлов (при успехе)
     *   - message: string - сообщение об ошибке или результате (при неудаче)
     */
    public JSONObject deleteFile(int storageType, String folder, String fileName) {
        // Валидация параметров
        if (fileName == null || fileName.trim().isEmpty()) {
            return createErrorResult("File name cannot be null or empty", SOURCE_ERROR);
        }

        if (storageType != STORAGE_CACHE && storageType != STORAGE_WORKING) {
            return createErrorResult("Invalid storage type. Use STORAGE_CACHE or STORAGE_WORKING", SOURCE_ERROR);
        }

        if (folder != null && folder.contains("..")) {
            return createErrorResult("Invalid folder path", SOURCE_ERROR);
        }

        try {
            File storageDir = getStorageDirectory(storageType, folder);
            return executeWithDirectoryLock(storageDir, () -> {
                if (!storageDir.exists() || !storageDir.isDirectory()) {
                    // Директория не существует - считаем что файла нет (успех)
                    return createDeleteSuccessResult(0, "Directory does not exist - no files to delete");
                }

                // Ищем все файлы, заканчивающиеся на указанное имя (учитываем временную метку)
                File[] filesToDelete = storageDir.listFiles((dir, name) ->
                        name.endsWith(fileName) && new File(dir, name).isFile()
                );

                if (filesToDelete == null || filesToDelete.length == 0) {
                    // Файл не найден - считаем операцию успешной
                    return createDeleteSuccessResult(0, "File not found - nothing to delete");
                }

                // Удаляем все найденные файлы
                int successfullyDeleted = 0;
                List<String> failedDeletions = new ArrayList<>();

                for (File file : filesToDelete) {
                    if (file.delete()) {
                        successfullyDeleted++;
                    } else {
                        failedDeletions.add(file.getName());
                    }
                }

                if (failedDeletions.isEmpty()) {
                    // Все файлы успешно удалены
                    return createDeleteSuccessResult(successfullyDeleted,
                            "Successfully deleted " + successfullyDeleted + " file(s)");
                } else {
                    // Некоторые файлы не удалось удалить
                    String errorMessage = "Partially deleted: " + successfullyDeleted +
                            " successful, " + failedDeletions.size() + " failed. Failed files: " +
                            String.join(", ", failedDeletions);
                    return createErrorResult(errorMessage, SOURCE_ERROR);
                }
            });

        } catch (Exception e) {
            // Логируем исключение
            logException("deleteFile", e);
            return createErrorResult("Exception in deleteFile: " + e.getMessage(), SOURCE_EXCEPTION);
        }
    }

    /**
     * Получение актуальных данных о файлах и очистка просроченных
     * Оптимизированная версия с логированием и быстрой проверкой формата
     */
    public JSONArray actualData() {
        long startTime = System.currentTimeMillis();
        logWarning("START actualData() - scanning for expired files");

        JSONArray resultArray = new JSONArray();
        long currentTime = System.currentTimeMillis();

        // Статистика
        int totalFilesProcessed = 0;
        int ourFormatFiles = 0;
        int expiredFilesDeleted = 0;
        int validFilesFound = 0;

        try {
            // Обрабатываем оба типа хранилищ
            for (int storageType : new int[]{STORAGE_CACHE, STORAGE_WORKING}) {
                File storageDir = getStorageDirectory(storageType, "");
                if (!storageDir.exists()) {
                    logWarning("Storage directory does not exist: " + storageDir.getAbsolutePath());
                    continue;
                }

                logWarning("Processing storage: " +
                        (storageType == STORAGE_CACHE ? "CACHE" : "WORKING") +
                        " at " + storageDir.getAbsolutePath());

                // Начинаем обход с глубины 0 (с защитой от бесконечной рекурсии)
                int[] stats = processDirectoryWithStats(storageDir, currentTime, resultArray, 0);
                totalFilesProcessed += stats[0];
                ourFormatFiles += stats[1];
                expiredFilesDeleted += stats[2];
                validFilesFound += stats[3];
            }
        } catch (Exception e) {
            // Логируем исключение
            logException("actualData", e);
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Рассчитываем файлы не нашего формата
        int nonFormatFiles = totalFilesProcessed - ourFormatFiles;

        logWarning("COMPLETED actualData() - " +
                "Duration: " + duration + "ms, " +
                "Total files: " + totalFilesProcessed + ", " +
                "Our format: " + ourFormatFiles + " (" +
                "Expired: " + expiredFilesDeleted + ", " +
                "Valid: " + validFilesFound + "), " +
                "Non-format: " + nonFormatFiles);

        if (duration > 5000) {
            logWarning("WARNING: actualData() took " + duration + "ms (>5s) - consider optimization");
        }

        return resultArray;
    }

    /**
     * Создает временный файл с расширением .tmp в указанном хранилище
     *
     * Особенности:
     * - Файл создается физически на диске
     * - Имя файла гарантированно уникально в пределах директории
     * - Файл не имеет временной метки (не участвует в автоматической очистке по сроку)
     * - Расширение всегда .tmp
     * - Для автоматического удаления при перезагрузке/очистке кэша используйте STORAGE_CACHE
     *
     * @param storageType тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param folder подпапка для создания файла (может быть null)
     * @param fileName базовое имя файла без расширения (может быть null - сгенерируется автоматически)
     * @return созданный временный файл (File) или null в случае ошибки
     *
     * @throws SecurityException если нет разрешений на запись в файловую систему
     * @throws IllegalStateException если недостаточно места на устройстве
     */
    public File getTempFile(int storageType, String folder, String fileName) {
        // Валидация типа хранилища
        if (storageType != STORAGE_CACHE && storageType != STORAGE_WORKING) {
            logWarning("Invalid storage type in getTempFile: " + storageType);
            return null;
        }

        File tempFile = null;

        try {
            // Получаем безопасную директорию для создания файла
            File storageDir = getStorageDirectory(storageType, folder);

            return executeWithDirectoryLock(storageDir, () -> {
                // Создаем директорию если не существует
                if (!storageDir.exists() && !storageDir.mkdirs()) {
                    logWarning("Cannot create directory for temp file: " + storageDir.getAbsolutePath());
                    return null;
                }

                // Проверяем доступное место (минимум 1 МБ)
                if (!hasEnoughSpace(storageDir, 1024 * 1024)) {
                    logWarning("Insufficient space for temp file in: " + storageDir.getAbsolutePath());
                    return null;
                }

                // Генерируем имя файла если не указано
                String baseName = (fileName != null && !fileName.trim().isEmpty()) ?
                        fileName.trim() : generateUniqueTempName();

                // Вычисляем время истечения (текущее время + 24 часа)
                long expirationTime = System.currentTimeMillis() + 24 * 3600 * 1000L;

                // Генерируем имя файла с временной меткой в формате %t(ддММггггЧЧммсс)_имя.tmp
                String timeStampedFileName = generateFileName(baseName, expirationTime);

                // Создаем временный файл
                File temp = createTempFileWithRetry(storageDir, timeStampedFileName, ".tmp", 3);

                if (temp != null) {
                    logWarning("Successfully created temp file: " + temp.getAbsolutePath());
                    return temp;
                } else {
                    logWarning("Failed to create temp file after retries");
                    return null;
                }
            });

        } catch (SecurityException e) {
            logException("Security exception in getTempFile", e);
            throw e; // Пробрасываем выше - это критическая ошибка прав
        } catch (Exception e) {
            logException("Unexpected error in getTempFile", e);
            // В случае любой другой ошибки пытаемся очистить
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    logWarning("Failed to cleanup temp file after error: " + tempFile.getAbsolutePath());
                }
            }
            return null;
        }
    }

    /**
     * Очистка ресурсов и сброс синглтона
     */
    public static synchronized void release() {
        if (instance != null) {
            instance.cleanupResources();
            instance = null;
        }
    }

    // endregion

    // region Вспомогательные приватные методы

    /**
     * Очистка внутренних ресурсов
     */
    private void cleanupResources() {
        // Очистка всех блокировок
        for (ReentrantLock lock : directoryLocks.values()) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                // Игнорируем исключения при разблокировке
            }
        }
        directoryLocks.clear();

        // Обнуляем контекст
        context = null;
    }

    /**
     * Генерирует уникальное имя для временного файла на основе временной метки и случайного числа
     * Формат: temp_ддММггггЧЧммсс_случайноечисло
     *
     * @return уникальное имя файла без расширения
     */
    private String generateUniqueTempName() {
        String timestamp = dateFormatLocal.get().format(new Date());
        int random = new Random().nextInt(10000); // случайное число 0-9999
        return "temp_" + timestamp + "_" + random;
    }

    /**
     * Пытается создать временный файл с указанными параметрами с повторными попытками
     *
     * @param directory директория для создания
     * @param prefix префикс имени файла
     * @param suffix суффикс (расширение) файла
     * @param maxRetries максимальное количество попыток
     * @return созданный File объект или null при неудаче
     */
    private File createTempFileWithRetry(File directory, String prefix, String suffix, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Создаем файл с проверкой существования
                File tempFile = new File(directory, prefix + suffix);

                // Если файл с таким именем существует, добавляем суффикс
                int counter = 1;
                while (tempFile.exists() && counter <= 1000) {
                    tempFile = new File(directory, prefix + "_" + counter + suffix);
                    counter++;
                }

                if (tempFile.createNewFile()) {
                    return tempFile;
                } else if (attempt < maxRetries) {
                    logWarning("Failed to create temp file, attempt " + attempt + " of " + maxRetries);
                    // Ждем перед повторной попыткой
                    Thread.sleep(100);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // восстанавливаем флаг прерывания
                logWarning("Thread interrupted during temp file creation");
                return null;
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    logWarning("Exception creating temp file, attempt " + attempt + ": " + e.getMessage());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    logException("Failed to create temp file after " + maxRetries + " attempts", e);
                }
            }
        }
        return null;
    }

    /**
     * Создание успешного JSON ответа
     * @param path путь к файлу
     * @param expires время истечения
     * @param source источник файла
     * @return JSONObject с результатом успешной операции
     */
    private JSONObject createSuccessResult(String path, long expires, String source) {
        JSONObject result = new JSONObject();
        try {
            result.put("success", true);
            result.put("path", path);
            result.put("expires", expires);
            if (source != null) {
                result.put("source", source);
            }
        } catch (Exception e) {
            logException("createSuccessResult", e);
        }
        return result;
    }

    /**
     * Создание ошибочного JSON ответа
     * @param message сообщение об ошибке
     * @param source источник ошибки
     * @return JSONObject с результатом ошибочной операции
     */
    private JSONObject createErrorResult(String message, String source) {
        JSONObject result = new JSONObject();
        try {
            result.put("success", false);
            result.put("message", message);
            if (source != null) {
                result.put("source", source);
            }
        } catch (Exception e) {
            logException("createErrorResult", e);
        }
        return result;
    }

    /**
     * Создание успешного JSON ответа для операции удаления
     * @param deletedCount количество удаленных файлов
     * @param message дополнительное сообщение
     * @return JSONObject с результатом успешной операции удаления
     */
    private JSONObject createDeleteSuccessResult(int deletedCount, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("success", true);
            result.put("deletedCount", deletedCount);
            if (message != null) {
                result.put("message", message);
            }
        } catch (Exception e) {
            logException("createDeleteSuccessResult", e);
        }
        return result;
    }

    /**
     * Валидация основных параметров методов
     * @param from источник файла
     * @param to тип хранилища
     * @param folder папка назначения
     * @param timeInMillis время истечения
     * @return JSONObject с ошибкой или null если параметры валидны
     */
    private JSONObject validateParameters(String from, int to, String folder, long timeInMillis) {
        if (from == null || from.trim().isEmpty()) {
            return createErrorResult("Source path cannot be null or empty", SOURCE_ERROR);
        }

        if (to != STORAGE_CACHE && to != STORAGE_WORKING) {
            return createErrorResult("Invalid storage type. Use STORAGE_CACHE or STORAGE_WORKING", SOURCE_ERROR);
        }

        if (folder != null && folder.contains("..")) {
            return createErrorResult("Invalid folder path", SOURCE_ERROR);
        }

        return null; // Все параметры валидны
    }

    /**
     * Проверка валидности URL с дополнительной валидацией протокола
     * @param urlString строка URL для проверки
     * @return true если URL валиден и поддерживается
     */
    public boolean isValidUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol();
            // Разрешаем только HTTP, HTTPS и FTP протоколы
            return "http".equals(protocol) || "https".equals(protocol) || "ftp".equals(protocol);
        } catch (Exception e) {
            logWarning("Invalid URL: " + urlString + " - " + e.getMessage());
            return false;
        }
    }

    /** Проверка того, что адрес является ресурсным */
    public boolean isResource(String path) {
        return path != null && path.startsWith(RESOURCE_SCHEME);
    }

    /** Проверка того, что адрес является сетевым локальным */
    public boolean isSmbSource(String path) {
        return path != null && path.startsWith(SMB_SCHEME);
    }

    /**
     * Проверка валидности имени файла
     * @param fileName имя файла для проверки
     * @return true если имя файла валидно
     */
    private boolean isValidFileName(String fileName) {
        if (fileName == null || fileName.length() > 255 || fileName.trim().isEmpty()) {
            return false;
        }
        // Проверяем на наличие недопустимых символов и запрещенных имен
        return !INVALID_CHARS_PATTERN.matcher(fileName).find() &&
                !fileName.equals(".") && !fileName.equals("..");
    }

    /**
     * Проверка поддерживается ли указанная кодировка
     * @param encoding название кодировки
     * @return true если кодировка поддерживается
     */
    private boolean isValidEncoding(String encoding) {
        try {
            return Charset.isSupported(encoding);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Проверка достаточности свободного места для операции
     * @param destination целевая директория
     * @param expectedSize ожидаемый размер данных
     * @return true если места достаточно (с запасом 100%)
     */
    private boolean hasEnoughSpace(File destination, long expectedSize) {
        long availableSpace = destination.getFreeSpace();
        // Требуем в 2 раза больше места для надежности
        return availableSpace > expectedSize * 2;
    }

    /**
     * Оптимизированное сохранение текста в файл с указанной кодировкой
     *
     * @param file целевой файл
     * @param text текст для сохранения
     * @param encoding кодировка файла
     * @return true если успешно, false при ошибке
     */
    private boolean saveTextToFile(File file, String text, String encoding) {
        if (text == null) {
            logWarning("Attempt to save null text to file: " + file.getAbsolutePath());
            return false;
        }

        if (text.length() > 10 * 1024 * 1024) { // 10 МБ
            logWarning("Text too large for saveTextToFile: " + text.length() + " characters");
            return false;
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), encoding), 8192)) {

            // Для больших текстов используем блочную запись
            int textLength = text.length();
            int chunkSize = 65536; // 64KB

            if (textLength <= chunkSize) {
                writer.write(text);
            } else {
                int position = 0;
                while (position < textLength) {
                    int end = Math.min(position + chunkSize, textLength);
                    writer.write(text, position, end - position);
                    position = end;
                }
            }

            writer.flush();
            return true;

        } catch (Exception e) {
            // Логируем исключение
            logException("saveTextToFile", e);
            return false;
        }
    }

    /**
     * Атомарное сохранение текста в файл с использованием временного файла
     * Гарантирует, что целевой файл либо будет полностью записан, либо не изменится
     *
     * @param targetFile целевой файл
     * @param text текст для сохранения
     * @param encoding кодировка
     * @return true если успешно
     */
    private boolean saveTextFileAtomically(File targetFile, String text, String encoding) {
        File tempFile = null;
        try {
            // Создаем временный файл в той же директории
            tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");

            // Записываем во временный файл
            if (!saveTextToFile(tempFile, text, encoding)) {
                return false;
            }

            // Атомарно переименовываем временный файл в целевой
            // На большинстве файловых систем это атомарная операция
            return tempFile.renameTo(targetFile);

        } catch (Exception e) {
            logException("saveTextFileAtomically", e);
            return false;
        } finally {
            // В случае ошибки или если временный файл остался, удаляем его
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    logWarning("Failed to delete temporary file: " + tempFile.getAbsolutePath());
                }
            }
        }
    }

    /**
     * Атомарная обработка источника файла
     * Сначала создает временный файл, затем атомарно переименовывает его
     *
     * @param from источник файла
     * @param destinationFile целевой файл
     * @return true если успешно
     */
    private boolean processFileSourceAtomically(String from, File destinationFile) {
        File tempFile = null;
        try {
            // Создаем временный файл в той же директории
            tempFile = new File(destinationFile.getParentFile(), destinationFile.getName() + ".tmp");

            // Обрабатываем источник во временный файл
            if (!processFileSource(from, tempFile)) {
                return false;
            }

            // Атомарно переименовываем временный файл в целевой
            return tempFile.renameTo(destinationFile);

        } catch (Exception e) {
            logException("processFileSourceAtomically", e);
            return false;
        } finally {
            // В случае ошибки или если временный файл остался, удаляем его
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    logWarning("Failed to delete temporary file: " + tempFile.getAbsolutePath());
                }
            }
        }
    }

    /**
     * Удаление старых версий файла с тем же именем
     *
     * @param storageDir директория хранилища
     * @param fileName базовое имя файла (без временной метки)
     */
    private void deleteOldFileVersions(File storageDir, String fileName) {
        try {
            if (!storageDir.exists() || !storageDir.isDirectory()) return;

            // Находим все файлы с тем же базовым именем
            File[] oldFiles = storageDir.listFiles((dir, name) ->
                    name.endsWith(fileName) && new File(dir, name).isFile()
            );

            // Удаляем найденные старые версии
            if (oldFiles != null) {
                for (File oldFile : oldFiles) {
                    if (!oldFile.delete()) {
                        logWarning("Failed to delete old file: " + oldFile.getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) {
            // Логируем исключение
            logException("deleteOldFileVersions", e);
        }
    }

    /**
     * Безопасное получение директории хранилища
     * Защита от Path Traversal атак через проверку канонических путей
     *
     * @param storageType тип хранилища (STORAGE_CACHE или STORAGE_WORKING)
     * @param folder запрошенная папка
     * @return безопасный File объект директории
     */
    private File getStorageDirectory(int storageType, String folder) {
        // Определяем базовую директорию в зависимости от типа хранилища
        File baseDir = (storageType == STORAGE_CACHE) ?
                context.getCacheDir() : context.getFilesDir();

        // Если папка не указана - возвращаем базовую директорию
        if (folder == null || folder.trim().isEmpty()) {
            return baseDir;
        }

        // Создаем объект целевой директории
        File targetDir = new File(baseDir, folder);

        try {
            // Получаем канонические пути для безопасного сравнения
            String canonicalBasePath = baseDir.getCanonicalPath();
            String canonicalTargetPath = targetDir.getCanonicalPath();

            // Проверяем, что целевой путь находится внутри базового пути
            if (!canonicalTargetPath.startsWith(canonicalBasePath)) {
                // Обнаружена попытка Path Traversal - возвращаем базовую директорию
                logSecurityWarning("Path traversal attempt detected", folder, canonicalTargetPath);
                return baseDir;
            }

            return targetDir;

        } catch (IOException e) {
            // При ошибке получения канонического пути возвращаем базовую директорию
            logException("getStorageDirectory security check", e);
            return baseDir;
        }
    }

    /**
     * Вспомогательный метод для оптимизированного чтения содержимого из InputStream в строку
     * Использует StringWriter для более эффективной конкатенации строк
     */
    private String readStreamContent(InputStream inputStream, String encoding) {
        try (InputStreamReader isr = new InputStreamReader(inputStream, encoding);
             BufferedReader reader = new BufferedReader(isr, 8192)) {

            // Используем StringWriter для более эффективной работы со строками
            StringWriter writer = new StringWriter(8192);
            char[] buffer = new char[8192];
            int charsRead;

            while ((charsRead = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, charsRead);
            }

            return writer.toString();

        } catch (Exception e) {
            logException("readStreamContent", e);
            return null;
        }
    }

    /**
     * Генерация имени файла с временной меткой истечения
     *
     * @param sourcePath исходный путь/URL для извлечения имени файла
     * @param timeInMillis время истечения в миллисекундах
     * @return имя файла в формате: %t(ддММггггЧЧммсс)_оригинальное_имя
     */
    private String generateFileName(String sourcePath, long timeInMillis) {
        String originalFileName = extractFileName(sourcePath);

        // Форматируем время истечения в строку
        String expirationDate = dateFormatLocal.get().format(new Date(timeInMillis));

        return "%t(" + expirationDate + ")_" + originalFileName;
    }

    /**
     * Извлечение имени файла из пути или URL
     * Поддерживает сетевые пути smb, url и ресурсы
     *
     * Алгоритм работы:
     * 1. Проверка на сетевые пути Windows - извлечение из последней части
     * 2. Обработка URL - удаление параметров и якорей
     * 3. Обработка ресурсов - извлечение чистого имени
     * 4. Стандартная обработка локальных путей
     *
     * @param path путь к файлу, URL или сетевой путь
     * @return имя файла или "unknown_file" если извлечение невозможно
     */
    public String extractFileName(String path) {
        if (path == null || path.isEmpty()) return "unknown_file";

        // smb
        if (isSmbSource(path)) {
            try {
                SmbUrl parsedUrl = parseSmbUrl(path);
                if (parsedUrl != null && !parsedUrl.filePath.isEmpty()) {
                    // Извлекаем имя файла из пути
                    String filePath = parsedUrl.filePath;
                    int lastSlash = filePath.lastIndexOf('/');
                    return (lastSlash != -1 && lastSlash < filePath.length() - 1) ?
                            filePath.substring(lastSlash + 1) : filePath;
                }
            } catch (Exception e) {
                logWarning("Error parsing SMB URL in extractFileName: " + path);
            }
            return "unknown_file";
        } else if (isUrl(path)) { // URL
            path = path.split("[?#]")[0];
        } else if (isResource(path)) {
            String resourceName = path.substring(RESOURCE_SCHEME.length());
            String cleanName = extractRawResourceName(resourceName);

            if (resourceName.contains(".")) {
                int lastDot = resourceName.lastIndexOf('.');
                String extension = resourceName.substring(lastDot);
                return cleanName + extension;
            }
            return cleanName;
        }

        // Стандартная обработка для локальных путей и URL
        int lastSlash = path.lastIndexOf('/');
        int lastBackslash = path.lastIndexOf('\\');
        int separatorIndex = Math.max(lastSlash, lastBackslash);

        return (separatorIndex != -1 && separatorIndex < path.length() - 1) ?
                path.substring(separatorIndex + 1) : path;
    }

    /**
     * Проверка, является ли строка URL-адресом
     *
     * @param path строка для проверки
     * @return true если строка соответствует паттерну URL
     */
    public boolean isUrl(String path) {
        return URL_PATTERN.matcher(path).matches();
    }

    /**
     * Обработка источника файла (URL или локальный путь)
     *
     * @param from источник файла
     * @param destinationFile целевой файл
     * @return true если обработка успешна
     */
    private boolean processFileSource(String from, File destinationFile) {
        if (isResource(from)) {
            return copyResource(from, destinationFile);
        } else if (isSmbSource(from)) {
            return downloadFromSmb(from, destinationFile);
        } else if (isUrl(from)) {
            if (!isValidUrl(from)) {
                return false;
            }
            return downloadFromUrl(from, destinationFile);
        } else {
            return copyLocalFile(from, destinationFile);
        }
    }

    /**
     * Надежная загрузка файлов из URL
     *
     * @param urlString URL для загрузки
     * @param destinationFile целевой файл
     * @return true если загрузка успешна
     */
    private boolean downloadFromUrl(String urlString, File destinationFile) {
        File tempFile = null;
        HttpURLConnection connection = null;
        InputStream inputStream = null;

        try {
            // Создаем временный файл в кэше приложения
            tempFile = File.createTempFile("download_", ".tmp", context.getCacheDir());

            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Config.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Config.READ_TIMEOUT_MS);

            // Проверяем размер файла перед загрузкой
            long contentLength = connection.getContentLengthLong();
            if (contentLength > Config.MAX_DOWNLOAD_SIZE) {
                logWarning("File too large: " + contentLength + " bytes, max allowed: " + Config.MAX_DOWNLOAD_SIZE);
                return false;
            }

            // Загружаем данные во временный файл
            inputStream = connection.getInputStream();
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {

                byte[] buffer = new byte[Config.BUFFER_SIZE];
                int bytesRead;
                long totalBytes = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    totalBytes += bytesRead;
                    // Дополнительная проверка во время загрузки
                    if (totalBytes > Config.MAX_DOWNLOAD_SIZE) {
                        logWarning("File exceeds size limit during download: " + totalBytes + " bytes");
                        return false;
                    }
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            return copyLocalFile(tempFile.getAbsolutePath(), destinationFile);

        } catch (Exception e) {
            // Логируем исключение
            logException("downloadFromUrl", e);
            Thread.currentThread().interrupt(); // Восстановление флага
            return false;
        } finally {
            // Всегда закрываем соединение и удаляем временный файл
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // Игнорируем ошибки при закрытии
                }
            }
            if (connection != null) connection.disconnect();
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    logWarning("Failed to delete temp file: " + tempFile.getAbsolutePath());
                }
            }
        }
    }

    /**
     * Надежная загрузка файлов из SMB/CIFS сетевых путей с использованием smbj
     * с поддержкой анонимного доступа и различных форматов аутентификации
     *
     * Важное изменение: Добавлена защита от Path Traversal атак
     */
    private boolean downloadFromSmb(String smbUrl, File destinationFile) {

        SMBClient smbClient = null;
        Connection connection = null;
        Session session = null;
        DiskShare share = null;
        com.hierynomus.smbj.share.File smbFile = null;

        try {
            // Парсим SMB URL с поддержкой различных форматов
            SmbUrl parsedUrl = parseSmbUrl(smbUrl);

            if (parsedUrl == null) {
                logWarning("Invalid SMB URL format: " + maskPasswordInUrl(smbUrl));
                return false;
            }

            // ВАЖНО: Проверка на Path Traversal в SMB пути
            if (isPathTraversalAttempt(parsedUrl.filePath)) {
                logSecurityWarning("Path traversal attempt detected in SMB path",
                        parsedUrl.filePath, smbUrl);
                return false;
            }

            smbClient = new SMBClient();

            logWarning("Connecting to SMB: " + parsedUrl.server + ":" + parsedUrl.port +
                    ", Share: " + parsedUrl.shareName + ", Path: " + parsedUrl.filePath);

            // Подключаемся к серверу
            connection = smbClient.connect(parsedUrl.server, parsedUrl.port);

            // Создаем контекст аутентификации с учетом разных сценариев
            AuthenticationContext authContext;

            if (parsedUrl.username == null || parsedUrl.username.isEmpty()) {
                // Сценарий 1: Анонимный доступ (без учетных данных)
                logWarning("Using anonymous authentication for SMB connection");
                authContext = AuthenticationContext.anonymous();
            } else {
                // Сценарий 2: Доступ с учетными данными
                // Обрабатываем домен, если он указан
                String domain = parsedUrl.domain != null ? parsedUrl.domain : "";
                char[] password = parsedUrl.password != null ?
                        parsedUrl.password.toCharArray() : "".toCharArray();

                logWarning("Using authentication with user: " + parsedUrl.username +
                        ", domain: " + (domain.isEmpty() ? "<none>" : domain));

                authContext = new AuthenticationContext(
                        parsedUrl.username,
                        password,
                        domain
                );
            }

            // Аутентифицируемся
            session = connection.authenticate(authContext);

            // Подключаемся к шаре
            share = (DiskShare) session.connectShare(parsedUrl.shareName);

            // Проверяем существование файла или директории
            String smbPath = parsedUrl.filePath;
            if (smbPath == null || smbPath.isEmpty()) {
                logWarning("No file path specified in SMB URL, using empty path");
                smbPath = "";
            }

            // Нормализуем путь для SMB (заменяем прямые слеши на обратные для Windows)
            String normalizedPath = smbPath.replace('/', '\\');
            if (!normalizedPath.isEmpty() && !normalizedPath.startsWith("\\")) {
                normalizedPath = "\\" + normalizedPath;
            }

            logWarning("Checking SMB path: " + normalizedPath);

            try {
                // Проверяем существование пути (файла или директории)
                if (!normalizedPath.isEmpty() && !share.fileExists(normalizedPath)) {
                    logWarning("SMB path does not exist: " + normalizedPath);
                    return false;
                }

                // Если путь пустой, проверяем доступность шары
                if (normalizedPath.isEmpty()) {
                    // Пробуем прочитать корень шары для проверки доступности
                    try {
                        share.list("\\");
                    } catch (Exception e) {
                        logWarning("SMB share is not accessible: " + e.getMessage());
                        return false;
                    }
                }
            } catch (Exception e) {
                logWarning("Error checking SMB path existence: " + e.getMessage());
                return false;
            }

            // Если путь пустой, мы не можем скачать (это директория)
            if (normalizedPath.isEmpty()) {
                logWarning("Cannot download - SMB path points to a directory, not a file");
                return false;
            }

            // ИСПРАВЛЕНИЕ: Правильное открытие файла с 6 параметрами для SMBJ 0.11.5
            smbFile = share.openFile(
                    normalizedPath,
                    java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), // AccessMask
                    java.util.EnumSet.noneOf(com.hierynomus.msfscc.FileAttributes.class), // FileAttributes
                    java.util.EnumSet.of(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ), // ShareAccess
                    com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, // CreateDisposition
                    java.util.EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions.class) // CreateOptions
            );

            logWarning("SMB file opened successfully, starting download...");

            // Скачиваем файл с проверкой размера во время загрузки
            try (InputStream inputStream = smbFile.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(destinationFile)) {

                byte[] buffer = new byte[Config.BUFFER_SIZE];
                int bytesRead;
                long totalBytes = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    totalBytes += bytesRead;

                    // Проверяем размер во время загрузки
                    if (totalBytes > Config.MAX_SMB_SIZE) {
                        logWarning("SMB file exceeds size limit: " + totalBytes + " bytes");
                        return false;
                    }

                    outputStream.write(buffer, 0, bytesRead);

                    // Логируем прогресс каждые 5 МБ
                    if (totalBytes % (5 * 1024 * 1024) == 0) {
                        logWarning("Download progress: " + (totalBytes / (1024 * 1024)) + " MB");
                    }
                }

                logWarning("Successfully downloaded SMB file: " + totalBytes + " bytes (" +
                        (totalBytes / (1024 * 1024)) + " MB)");
                return true;

            } catch (Exception e) {
                logException("Error during SMB file download", e);
                return false;
            }

        } catch (Exception e) {
            logException("downloadFromSmb failed", e);
            return false;
        } finally {
            // Закрываем ресурсы в правильном порядке
            if (smbFile != null) {
                try {
                    smbFile.close();
                } catch (Exception e) {
                    logWarning("Error closing SMB file: " + e.getMessage());
                }
            }
            if (share != null) {
                try {
                    share.close();
                } catch (Exception e) {
                    logWarning("Error closing SMB share: " + e.getMessage());
                }
            }
            if (session != null) {
                try {
                    session.close();
                } catch (Exception e) {
                    logWarning("Error closing SMB session: " + e.getMessage());
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    logWarning("Error closing SMB connection: " + e.getMessage());
                }
            }

            if (smbClient != null) {
                try { smbClient.close(); } catch (Exception e) {
                    logWarning("Error closing SMB client: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Проверка на попытку Path Traversal в пути
     * Ищет опасные последовательности в пути
     *
     * @param filePath путь для проверки
     * @return true если обнаружена попытка Path Traversal
     */
    private boolean isPathTraversalAttempt(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }

        // Проверяем наличие опасных последовательностей
        if (PATH_TRAVERSAL_PATTERN.matcher(filePath).find()) {
            return true;
        }

        // Дополнительные проверки
        String normalized = filePath.replace('\\', '/');

        // Проверяем на абсолютные пути
        if (normalized.startsWith("/") || normalized.startsWith("\\")) {
            return true;
        }

        // Проверяем на ссылки на родительские директории
        String[] parts = normalized.split("/");
        int depth = 0;
        for (String part : parts) {
            if (part.equals("..")) {
                depth--;
                if (depth < 0) {
                    return true; // Выход за пределы корневой директории
                }
            } else if (!part.equals(".") && !part.isEmpty()) {
                depth++;
            }
        }

        return false;
    }

    /**
     * Парсит SMB URL в компоненты
     * Поддерживаемые форматы:
     * 1. smb://username:password@server/share/path
     * 2. smb://username@server/share/path (пароль пустой)
     * 3. smb://server/share/path (без авторизации)
     * 4. smb://domain;username:password@server/share/path (с доменом)
     * 5. smb://192.168.1.1:445/share/path (IP с портом)
     * 6. smb://PC-lite/Share/Folder/file.txt (имя компьютера)
     */
    private SmbUrl parseSmbUrl(String smbUrl) {
        if (smbUrl == null || smbUrl.length() > 2048) {
            logWarning("SMB URL too long or null");
            return null;
        }

        try {
            SmbUrl result = new SmbUrl();

            // Нормализуем URL: удаляем пробелы в начале/конце, приводим к нижнему регистру протокол
            smbUrl = smbUrl.trim();
            if (!smbUrl.toLowerCase().startsWith("smb://")) {
                logWarning("Invalid SMB URL scheme: " + smbUrl);
                return null;
            }

            // Убираем smb://
            String withoutScheme = smbUrl.substring(6);
            if (withoutScheme.isEmpty()) {
                logWarning("SMB URL is empty after scheme");
                return null;
            }

            // Разделяем на authority (логин/сервер) и path
            int slashIndex = withoutScheme.indexOf('/');
            if (slashIndex == -1) {
                logWarning("No share found in SMB URL");
                return null;
            }

            String authority = withoutScheme.substring(0, slashIndex);
            String fullPath = withoutScheme.substring(slashIndex + 1);

            // Парсим authority (может содержать логин:пароль@сервер)
            parseAuthority(authority, result);

            // Парсим путь (share и путь к файлу)
            parsePath(fullPath, result);

            // Валидация обязательных полей
            if (result.server == null || result.server.isEmpty() ||
                    result.shareName == null || result.shareName.isEmpty()) {
                logWarning("Missing required SMB components: server=" + result.server +
                        ", share=" + result.shareName);
                return null;
            }

            logWarning("Parsed SMB URL - Server: " + result.server +
                    ", Port: " + result.port +
                    ", Domain: " + (result.domain != null ? result.domain : "<none>") +
                    ", User: " + (result.username != null ? result.username : "<anonymous>") +
                    ", Share: " + result.shareName +
                    ", Path: " + result.filePath);

            return result;

        } catch (Exception e) {
            logException("parseSmbUrl failed for: " + maskPasswordInUrl(smbUrl), e);
            return null;
        }
    }

    /**
     * Парсит authority часть SMB URL
     * Форматы:
     * - username:password@server:port
     * - username@server:port
     * - server:port
     * - domain;username:password@server:port
     */
    private void parseAuthority(String authority, SmbUrl result) {
        if (authority == null || authority.isEmpty()) {
            return;
        }

        // Ищем @ для разделения учетных данных и сервера
        int atIndex = authority.lastIndexOf('@');

        if (atIndex != -1) {
            // Есть учетные данные
            String credentials = authority.substring(0, atIndex);
            String serverPart = authority.substring(atIndex + 1);

            parseCredentials(credentials, result);
            parseServerAndPort(serverPart, result);
        } else {
            // Нет учетных данных
            parseServerAndPort(authority, result);
        }
    }

    /**
     * Парсит учетные данные
     * Форматы:
     * - username:password
     * - username (без пароля)
     * - domain;username:password
     * - domain;username
     */
    private void parseCredentials(String credentials, SmbUrl result) {
        if (credentials == null || credentials.isEmpty()) {
            result.username = "";
            result.password = "";
            return;
        }

        // Проверяем наличие домена (разделитель ; или \)
        int domainSeparator = credentials.indexOf(';');
        if (domainSeparator == -1) {
            domainSeparator = credentials.indexOf('\\');
        }

        if (domainSeparator != -1) {
            // Есть домен
            result.domain = credentials.substring(0, domainSeparator);
            credentials = credentials.substring(domainSeparator + 1);
        }

        // Парсим username и password
        int colonIndex = credentials.indexOf(':');
        if (colonIndex != -1) {
            result.username = credentials.substring(0, colonIndex);
            result.password = credentials.substring(colonIndex + 1);
        } else {
            // Только username, без пароля
            result.username = credentials;
            result.password = "";
        }

        // Декодируем URL-encoded символы (если есть)
        if (result.username != null) {
            result.username = decodeUrlComponent(result.username);
        }
        if (result.password != null) {
            result.password = decodeUrlComponent(result.password);
        }
        if (result.domain != null) {
            result.domain = decodeUrlComponent(result.domain);
        }
    }

    /**
     * Парсит сервер и порт
     * Форматы:
     * - server
     * - server:port
     * - [IPv6]:port
     */
    private void parseServerAndPort(String serverPart, SmbUrl result) {
        if (serverPart == null || serverPart.isEmpty()) {
            return;
        }

        // Обработка IPv6 адресов в квадратных скобках
        if (serverPart.startsWith("[")) {
            int closeBracket = serverPart.indexOf(']');
            if (closeBracket != -1) {
                result.server = serverPart.substring(1, closeBracket);
                String afterBracket = serverPart.substring(closeBracket + 1);
                if (afterBracket.startsWith(":")) {
                    parsePort(afterBracket.substring(1), result);
                }
            } else {
                result.server = serverPart;
            }
        } else {
            // Обычный IPv4 или имя хоста
            int colonIndex = serverPart.indexOf(':');
            if (colonIndex != -1) {
                result.server = serverPart.substring(0, colonIndex);
                parsePort(serverPart.substring(colonIndex + 1), result);
            } else {
                result.server = serverPart;
                result.port = 445; // Порт по умолчанию
            }
        }

        // Декодируем имя сервера
        if (result.server != null) {
            result.server = decodeUrlComponent(result.server);
        }
    }

    /**
     * Парсит порт
     */
    private void parsePort(String portStr, SmbUrl result) {
        if (portStr == null || portStr.isEmpty()) {
            result.port = 445;
            return;
        }

        try {
            result.port = Integer.parseInt(portStr);
            if (result.port <= 0 || result.port > 65535) {
                logWarning("Invalid port number: " + portStr);
                result.port = 445;
            }
        } catch (NumberFormatException e) {
            logWarning("Invalid port format: " + portStr);
            result.port = 445;
        }
    }

    /**
     * Парсит путь: общий ресурс и путь к файлу
     * Форматы:
     * - share
     * - share/path/to/file.txt
     * - share/folder/subfolder/
     */
    private void parsePath(String fullPath, SmbUrl result) {
        if (fullPath == null || fullPath.isEmpty()) {
            result.shareName = "";
            result.filePath = "";
            return;
        }

        // Декодируем путь
        fullPath = decodeUrlComponent(fullPath);

        // ВАЖНО: Проверяем на Path Traversal ДО дальнейшей обработки
        if (isPathTraversalAttempt(fullPath)) {
            logSecurityWarning("Path traversal attempt in SMB path", fullPath, "blocked");
            result.shareName = "";
            result.filePath = "";
            return;
        }

        // Ищем первый слеш для разделения share и пути к файлу
        int slashIndex = fullPath.indexOf('/');

        if (slashIndex == -1) {
            // Нет пути к файлу, только share
            result.shareName = fullPath;
            result.filePath = "";
        } else {
            // Разделяем на share и путь
            result.shareName = fullPath.substring(0, slashIndex);
            result.filePath = fullPath.substring(slashIndex + 1);

            // Нормализуем путь к файлу (убираем начальные/конечные слеши)
            result.filePath = normalizePath(result.filePath);
        }

        // Декодируем имя общего ресурса
        if (result.shareName != null) {
            result.shareName = decodeUrlComponent(result.shareName);
        }
    }

    /**
     * Декодирует URL-encoded строку
     */
    private String decodeUrlComponent(String component) {
        if (component == null) return null;

        try {
            // ВАЖНО: Сначала проверяем на опасные последовательности
            if (component.contains("%2e%2e") || component.contains("%2E%2E") ||
                    component.contains("%2e.") || component.contains("%2E.") ||
                    component.contains(".%2e") || component.contains(".%2E")) {
                logSecurityWarning("Encoded path traversal attempt", component, "blocked");
                return component; // Не декодируем опасные строки
            }

            // Заменяем пробелы (могут быть закодированы как %20 или как +)
            component = component.replace("+", " ");

            // Декодируем процентное кодирование
            String decoded = URLDecoder.decode(component, "UTF-8");

            // Восстанавливаем обратные слеши в путях Windows
            decoded = decoded.replace("%5C", "\\");
            decoded = decoded.replace("%2F", "/");

            return decoded;
        } catch (UnsupportedEncodingException e) {
            logWarning("Failed to decode URL component: " + component);
            return component;
        }
    }

    /**
     * Нормализует путь к файлу:
     * - Убирает начальные и конечные слеши
     * - Заменяет несколько слешей подряд на один
     * - Корректно обрабатывает пути Windows с обратными слешами
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        // Заменяем обратные слеши на прямые для единообразия
        path = path.replace('\\', '/');

        // Убираем начальные и конечные слеши
        path = path.replaceAll("^/+", "");
        path = path.replaceAll("/+$", "");

        // Заменяем несколько слешей подряд на один
        path = path.replaceAll("/+", "/");

        return path;
    }

    /**
     * Вспомогательный класс для хранения компонентов SMB URL
     */
    private static class SmbUrl {
        String server;        // Имя сервера или IP
        int port = 445;       // Порт (по умолчанию 445)
        String domain;        // Домен (опционально)
        String username;      // Имя пользователя (опционально)
        String password;      // Пароль (опционально)
        String shareName;     // Имя общего ресурса (обязательно)
        String filePath;      // Путь к файлу внутри ресурса (опционально)
    }

    /**
     * Маскирует пароль в URL для безопасного логирования
     */
    private String maskPasswordInUrl(String smbUrl) {
        if (smbUrl == null) return null;

        try {
            return smbUrl.replaceAll("smb://([^:]+):[^@]+@", "smb://$1:***@");
        } catch (Exception e) {
            return smbUrl;
        }
    }

    /**
     * Копирование локального файла
     *
     * @param sourcePath путь к исходному файлу
     * @param destinationFile целевой файл
     * @return true если копирование успешно
     */
    private boolean copyLocalFile(String sourcePath, File destinationFile) {
        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            logWarning("Source file does not exist: " + sourcePath);
            return false;
        }

        try (FileInputStream in = new FileInputStream(sourceFile);
             FileOutputStream out = new FileOutputStream(destinationFile)) {

            byte[] buffer = new byte[Config.BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return true;

        } catch (Exception e) {
            // Логируем исключение
            logException("copyLocalFile", e);
            return false;
        }
    }

    /**
     * Копирование ресурса из res/raw
     */
    private boolean copyResource(String resourcePath, File destinationFile) {
        try {
            // Извлекаем имя ресурса (убираем scheme "resource://")
            String resourceName = resourcePath.substring(RESOURCE_SCHEME.length());

            // Убираем путь и расширение, оставляем только имя ресурса
            // "res/raw/slide.mp3" -> "slide"
            // "slide.mp3" -> "slide"
            // "slide" -> "slide"
            String cleanResourceName = extractRawResourceName(resourceName);

            // Получаем ID ресурса
            int resId = context.getResources().getIdentifier(
                    cleanResourceName, "raw", context.getPackageName()
            );

            if (resId == 0) {
                logWarning("Resource not found: " + cleanResourceName +
                        " (searched in res/raw/)");
                return false;
            }

            try (InputStream in = context.getResources().openRawResource(resId);
                 FileOutputStream out = new FileOutputStream(destinationFile)) {

                byte[] buffer = new byte[Config.BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                return true;

            } catch (Exception e) {
                logException("copyResource", e);
                return false;
            }

        } catch (Exception e) {
            logException("copyResource", e);
            return false;
        }
    }

    /**
     * Извлекает чистое имя ресурса из пути
     * Примеры:
     *   "res/raw/slide.mp3" -> "slide"
     *   "slide.mp3" -> "slide"
     *   "sound/slide" -> "slide"
     */
    private String extractRawResourceName(String resourcePath) {
        // Убираем пути и расширения
        String name = resourcePath;

        // Убираем путь если есть
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash != -1) {
            name = name.substring(lastSlash + 1);
        }

        // Убираем расширение если есть
        int lastDot = name.lastIndexOf('.');
        if (lastDot != -1) {
            name = name.substring(0, lastDot);
        }

        return name;
    }

    /**
     * Рекурсивная обработка директории для поиска и очистки файлов
     * С защитой от бесконечной рекурсии через ограничение глубины
     *
     * @param directory директория для обработки
     * @param currentTime текущее время для проверки истечения
     * @param resultArray массив для добавления информации об актуальных файлов
     * @param currentDepth текущая глубина рекурсии
     */
    private void processDirectory(File directory, long currentTime, JSONArray resultArray, int currentDepth) {
        // Проверяем существование директории и не превышена ли максимальная глубина
        if (!directory.exists() || !directory.isDirectory() || currentDepth > Config.MAX_DIRECTORY_DEPTH) {
            logWarning("Max directory depth exceeded or directory not found: " +
                    directory.getAbsolutePath() + ", depth: " + currentDepth);
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // Рекурсивно обрабатываем поддиректории с увеличением счетчика глубины
                processDirectory(file, currentTime, resultArray, currentDepth + 1);
            } else {
                // Обрабатываем файлы
                processFile(file, currentTime, resultArray);
            }
        }
    }

    /**
     * Рекурсивная обработка директории для поиска и очистки файлов со статистикой
     * Возвращает массив [totalFiles, ourFormatFiles, expiredDeleted, validFound]
     *
     * Важное изменение: Добавлена защита от бесконечной рекурсии
     */
    private int[] processDirectoryWithStats(File directory, long currentTime,
                                            JSONArray resultArray, int currentDepth) {
        int[] stats = new int[4]; // [0]total, [1]ourFormat, [2]deleted, [3]valid
        stats[0] = 0; // Всего файлов (включая не наш формат)
        stats[1] = 0; // Файлов нашего формата
        stats[2] = 0; // Удалено просроченных
        stats[3] = 0; // Найдено валидных

        // Проверяем существование директории и не превышена ли максимальная глубина
        if (!directory.exists() || !directory.isDirectory() || currentDepth > Config.MAX_DIRECTORY_DEPTH) {
            logWarning("Max directory depth exceeded or directory not found: " +
                    directory.getAbsolutePath() + ", depth: " + currentDepth);
            return stats;
        }

        File[] files = directory.listFiles();
        if (files == null) return stats;

        for (File file : files) {
            if (file.isDirectory()) {
                // Рекурсивно обрабатываем поддиректории с защитой от бесконечной рекурсии
                int[] subStats = processDirectoryWithStats(file, currentTime, resultArray, currentDepth + 1);
                for (int i = 0; i < stats.length; i++) {
                    stats[i] += subStats[i];
                }
            } else {
                // Обрабатываем файлы
                stats[0]++; // увеличиваем счетчик всех файлов

                int fileStatus = processFileWithStats(file, currentTime, resultArray);
                switch (fileStatus) {
                    case 1: // актуальный файл нашего формата
                        stats[1]++; // наш формат
                        stats[3]++; // валидный
                        break;
                    case 2: // просроченный файл нашего формата
                        stats[1]++; // наш формат
                        stats[2]++; // удален
                        break;
                    // case 0: файл не нашего формата - не считаем
                    default:
                        break;
                }
            }
        }

        return stats;
    }

    /**
     * Обработка отдельного файла: проверка срока и добавление в результат
     *
     * @param file файл для обработки
     * @param currentTime текущее время
     * @param resultArray массив результатов
     */
    private void processFile(File file, long currentTime, JSONArray resultArray) {
        try {
            // Быстрая пред-проверка перед парсингом
            String fileName = file.getName();
            if (!hasValidFormat(fileName)) {
                return; // Не наш формат, не обрабатываем
            }

            Long expirationTime = parseExpirationTime(file.getName());
            if (expirationTime != null) {
                if (currentTime > expirationTime) {
                    // Файл просрочен - удаляем
                    if (!file.delete()) {
                        logWarning("Failed to delete expired file: " + file.getAbsolutePath());
                    }
                } else {
                    // Файл актуален - добавляем в результат
                    JSONObject fileInfo = new JSONObject();
                    fileInfo.put("FileName", file.getAbsolutePath());
                    fileInfo.put("Expires", formatExpirationTime(expirationTime));
                    resultArray.put(fileInfo);
                }
            }
        } catch (Exception e) {
            // Логируем исключение
            logException("processFile", e);
        }
    }

    /**
     * Обработка отдельного файла со статистикой
     * @return массив [статус, время истечения или null]
     *   статус: 0 - не наш формат (игнорировать), 1 - актуален, 2 - просрочен
     */
    private int processFileWithStats(File file, long currentTime, JSONArray resultArray) {
        try {
            // Быстрая пред-проверка перед парсингом
            String fileName = file.getName();
            if (!hasValidFormat(fileName)) {
                return 0; // Не наш формат, не обрабатываем
            }

            Long expirationTime = parseExpirationTime(fileName);
            if (expirationTime != null) {
                if (currentTime > expirationTime) {
                    // Файл просрочен - удаляем
                    if (!file.delete()) {
                        logWarning("Failed to delete expired file: " + file.getAbsolutePath());
                    }
                    return 2; // удален
                } else {
                    // Файл актуален - добавляем в результат
                    JSONObject fileInfo = new JSONObject();
                    fileInfo.put("FileName", file.getAbsolutePath());
                    fileInfo.put("Expires", formatExpirationTime(expirationTime));
                    resultArray.put(fileInfo);
                    return 1; // актуален
                }
            }
        } catch (Exception e) {
            // Логируем исключение
            logException("processFileWithStats", e);
        }
        return 0; // не наш формат или ошибка
    }

    /**
     * Оптимизированное чтение содержимого файла в строку
     * Использует буферизацию и предварительное определение размера
     */
    private String readFileContent(File file, String encoding) {
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, encoding);
             BufferedReader reader = new BufferedReader(isr, 8192)) { // Увеличиваем размер буфера

            // Предварительно определяем размер StringBuilder
            long fileSize = file.length();
            int estimatedSize = fileSize > 0 && fileSize < Integer.MAX_VALUE ?
                    (int) fileSize : 8192;

            StringBuilder content = new StringBuilder(estimatedSize);
            char[] buffer = new char[8192]; // Увеличиваем размер буфера с 8192 (8KB) до 16384 (16KB)
            int charsRead;

            while ((charsRead = reader.read(buffer)) != -1) {
                content.append(buffer, 0, charsRead);
            }

            return content.toString();

        } catch (Exception e) {
            // Логируем исключение
            logException("readFileContent", e);
            return null;
        }
    }

    /**
     * Извлечение времени истечения из имени файла
     *
     * @param fileName имя файла для парсинга
     * @return время истечения в миллисекундах или null если не удалось распарсить
     */
    private Long parseExpirationTime(String fileName) {
        try {
            // Быстрая пред-проверка формата
            if (!hasValidFormat(fileName)) {
                return null;
            }

            Matcher matcher = FILENAME_PATTERN.matcher(fileName);
            if (matcher.matches()) {
                String dateString = matcher.group(1);
                return dateFormatLocal.get().parse(dateString).getTime();
            }
        } catch (Exception e) {
            // Не удалось распарсить дату - файл не нашего формата
            logWarning("Cannot parse expiration time from filename: " + fileName);
        }
        return null;
    }

    /**
     * Быстрая пред-проверка имени файла на соответствие формату
     * Проверяет основные признаки перед использованием регулярного выражения
     */
    private boolean hasValidFormat(String fileName) {
        if (fileName == null || fileName.length() < 20) {
            return false;
        }

        // Быстрые проверки:
        // 1. Начинается с %t(
        if (!fileName.startsWith("%t(")) {
            return false;
        }

        // 2. После %t( идут 14 цифр
        if (fileName.length() < 17) {
            return false;
        }

        for (int i = 3; i < 17; i++) {
            char c = fileName.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        // 3. После 14 цифр идет закрывающая скобка и подчеркивание
        if (fileName.charAt(17) != ')' || fileName.charAt(18) != '_') {
            return false;
        }

        return true;
    }

    /**
     * Форматирование времени истечения для читаемого отображения
     *
     * @param timeInMillis время в миллисекундах
     * @return отформатированная строка даты
     */
    private String formatExpirationTime(long timeInMillis) {
        return displayDateFormatLocal.get().format(new Date(timeInMillis));
    }

    // endregion

    // region Методы логирования

    /**
     * Логирование исключений с указанием метода
     *
     * @param methodName имя метода где произошло исключение
     * @param exception исключение для логирования
     */
    private void logException(String methodName, Exception exception) {
        android.util.Log.e("FileStorageManager", "Error in " + methodName, exception);
    }

    /**
     * Логирование предупреждений
     *
     * @param message сообщение предупреждения
     */
    private void logWarning(String message) {
        android.util.Log.w("FileStorageManager", message);
    }

    /**
     * Логирование предупреждений безопасности
     *
     * @param message основное сообщение
     * @param input входные данные вызвавшие предупреждение
     * @param resolvedPath разрешенный путь
     */
    private void logSecurityWarning(String message, String input, String resolvedPath) {
        android.util.Log.w("FileStorageManager", "SECURITY: " + message +
                " Input: " + input + " -> " + resolvedPath);
    }

    // endregion
}