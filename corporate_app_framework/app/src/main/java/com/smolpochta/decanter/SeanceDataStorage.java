/**
 * SeanceDataStorage - Потокобезопасное хранилище данных сеанса
 *
 * Copyright (c) 2025 Алексей smolpochta
 * Email: smolpochta@gmail.com
 *
 * Лицензия: Разрешено свободное использование, модификация и распространение
 * при условии сохранения данного уведомления об авторских правах.
 *
 * ПРЕДОСТАВЛЯЕТСЯ "КАК ЕСТЬ", БЕЗ КАКИХ-ЛИБО ГАРАНТИЙ, ЯВНЫХ ИЛИ ПОДРАЗУМЕВАЕМЫХ,
 * ВКЛЮЧАЯ, НО НЕ ОГРАНИЧИВАЯСЬ ГАРАНТИЯМИ ТОВАРНОЙ ПРИГОДНОСТИ, СООТВЕТСТВИЯ
 * ПО ЕГО КОНКРЕТНОМУ НАЗНАЧЕНИЮ И НЕНАРУШЕНИЯ ПРАВ.
 */

package com.smolpochta.decanter;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Потокобезопасное хранилище данных сеанса с поддержкой сериализации в файл
 *
 * ОСНОВНЫЕ ВОЗМОЖНОСТИ:
 * 1. Паттерн Singleton - глобальный доступ из любого места приложения
 * 2. Потокобезопасность - использование ConcurrentHashMap и synchronized блоков
 * 3. Сериализация - сохранение/загрузка состояния в JSON файл
 * 4. Типизированные коллекции - поддержка Map, List, Set с сохранением типа
 * 5. Валидация данных - проверка целостности при загрузке
 *
 * ОСОБЕННОСТИ БЕЗОПАСНОСТИ:
 * - Использование Application Context для избежания утечек памяти
 * - Ограничение глубины вложенности JSON (макс. 50 уровней)
 * - Ограничение количества ключей (макс. 10,000)
 * - Проверка целостности типизированных коллекций
 * - Защита от циклических ссылок при сериализации
 *
 * ИСПОЛЬЗОВАНИЕ:
 * 1. Инициализация: SeanceDataStorage.getInstance(getApplicationContext())
 * 2. Сохранение: storage.saveState("MainActivity")
 * 3. Загрузка: storage.loadState("MainActivity")
 * 4. Работа с данными: storage.put("key", value); String val = storage.getString("key");
 */
public final class SeanceDataStorage {

    //region ==================== КОНСТАНТЫ И СТАТИЧЕСКИЕ ПОЛЯ ====================

    /** Тег для логгирования через Android Log */
    private static final String TAG = "SeanceDataStorage";

    /** Префикс для имен файлов состояния */
    private static final String FILE_PREFIX = "SeanceDataStorage_";

    /** Расширение файлов состояния */
    private static final String FILE_EXTENSION = ".json";

    /** Маркер типа в JSON для сохранения информации о типе коллекции */
    private static final String TYPE_MARKER = "__type";

    /** Тип: java.util.List */
    private static final String LIST_TYPE = "java.util.List";

    /** Тип: java.util.Set */
    private static final String SET_TYPE = "java.util.Set";

    /** Тип: java.util.Map */
    private static final String MAP_TYPE = "java.util.Map";

    /** Максимальная глубина вложенности JSON (защита от переполнения стека) */
    private static final int MAX_JSON_DEPTH = 50;

    /** Максимальное количество ключей в JSON (защита от перегрузки памяти) */
    private static final int MAX_JSON_KEYS = 10000;

    /** Максимальный размер коллекции (защита от перегрузки памяти) */
    private static final int MAX_COLLECTION_SIZE = 10000;

    //endregion

    //region ==================== SINGLETON РЕАЛИЗАЦИЯ ====================

    /**
     * Класс-холдер для реализации паттерна Singleton с ленивой инициализацией
     * Гарантирует потокобезопасность без использования synchronized в getInstance()
     * Паттерн Initialization-on-demand holder
     */
    private static class Holder {
        /** Единственный экземпляр хранилища */
        static final SeanceDataStorage INSTANCE = new SeanceDataStorage();
    }

    /**
     * Получение экземпляра хранилища без контекста
     * ВНИМАНИЕ: Перед сохранением/загрузкой файлов необходимо инициализировать контекст
     *
     * @return единственный экземпляр SeanceDataStorage
     */
    public static SeanceDataStorage getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Получение экземпляра хранилища с инициализацией контекста
     * Используется Application Context для избежания утечек памяти
     *
     * @param context контекст приложения (Activity, Service, Application)
     * @return экземпляр хранилища с инициализированным контекстом
     */
    public static SeanceDataStorage getInstance(Context context) {
        SeanceDataStorage instance = getInstance();
        // Используем Application Context для избежания утечек памяти
        instance.initializeWithContext(context.getApplicationContext());
        return instance;
    }

    /**
     * Получение экземпляра хранилища с загрузкой состояния из файла
     * Комбинированная операция: получение экземпляра + загрузка состояния
     *
     * @param key ключ для идентификации файла состояния (обычно имя Activity)
     * @param context контекст приложения
     * @return экземпляр хранилища с загруженным состоянием
     */
    public static SeanceDataStorage getInstanceFile(String key, Context context) {
        SeanceDataStorage instance = getInstance(context);
        instance.loadState(key);
        return instance;
    }

    /**
     * Получение экземпляра хранилища с загрузкой состояния из файла
     * (использует уже установленный контекст)
     *
     * @param key ключ для идентификации файла состояния
     * @return экземпляр хранилища с загруженным состоянием
     * @throws IllegalStateException если контекст не был инициализирован
     */
    public static SeanceDataStorage getInstanceFile(String key) {
        SeanceDataStorage instance = getInstance();
        if (instance.appContext == null) {
            throw new IllegalStateException(
                    "Контекст не инициализирован. " +
                            "Сначала вызовите getInstance(Context) или initializeWithContext(Context)."
            );
        }
        instance.loadState(key);
        return instance;
    }

    //endregion

    //region ==================== ПОЛЯ КЛАССА ====================

    /** Основное потокобезопасное хранилище данных */
    private final ConcurrentHashMap<String, Object> storage;

    /** Application Context для доступа к файловой системе приложения */
    private Context appContext;

    /** Set для отслеживания уже посещенных объектов (защита от циклических ссылок) */
    private transient ThreadLocal<Set<Object>> visitedObjects =
            ThreadLocal.withInitial(HashSet::new);

    //endregion

    //region ==================== КОНСТРУКТОР И ИНИЦИАЛИЗАЦИЯ ====================

    /**
     * Приватный конструктор (Singleton)
     * Инициализирует хранилище и устанавливает значения по умолчанию
     */
    private SeanceDataStorage() {
        storage = new ConcurrentHashMap<>();
        // Значения по умолчанию будут установлены при инициализации контекста
    }

    /**
     * Инициализация хранилища контекстом приложения
     * Вызывается автоматически при использовании getInstance(Context)
     *
     * @param context Application Context приложения
     */
    public void initializeWithContext(Context context) {
        if (this.appContext == null) {
            this.appContext = context.getApplicationContext(); // Используем Application Context
            initializeDefaultValues();
            logInfo("Хранилище инициализировано с Application Context");
        }
    }

    /**
     * Инициализация значений по умолчанию для всех модулей приложения
     * Вызывается один раз при первой инициализации контекста
     */
    private void initializeDefaultValues() {
        Map<String, Object> DEFAULT_VALUES = new HashMap<>();

        // Настройки интерфейса по умолчанию
        DEFAULT_VALUES.put("mainBackgroundColor"    , "#EBEBEB");
        DEFAULT_VALUES.put("mainFontColor"          , "#4A4A4A");
        DEFAULT_VALUES.put("mainElementColor"       , "#4A4A4A");
        DEFAULT_VALUES.put("MessageBackgroundColor" , "#dfffe6");
        DEFAULT_VALUES.put("ErrorBackgroundColor"   , "#ffd4d4");

        // Добавляем префикс "default." ко всем ключам
        for (Map.Entry<String, Object> entry : DEFAULT_VALUES.entrySet()) {
            String defaultKey = "default." + entry.getKey();
            storage.putIfAbsent(defaultKey, entry.getValue());
        }

        logInfo("Значения по умолчанию инициализированы");
    }

    /**
     * Проверка инициализации контекста
     * Выбрасывает исключение если контекст не был инициализирован
     *
     * @throws IllegalStateException если контекст не инициализирован
     */
    private void validateContext() {
        if (appContext == null) {
            throw new IllegalStateException(
                    "Контекст не инициализирован. " +
                            "Вызовите getInstance(Context) или initializeWithContext(Context) перед использованием."
            );
        }
    }

    //endregion

    //region ==================== УПРАВЛЕНИЕ СОСТОЯНИЯМИ ====================

    /**
     * Сохранение текущего состояния хранилища в файл
     *
     * @param key ключ для идентификации файла (обычно имя Activity или компонента)
     * @return true если сохранение успешно, false при ошибке
     */
    public synchronized boolean saveState(String key) {
        validateContext();

        try {
            String filename = getFilename(key);
            Path filePath = Paths.get(filename);

            // Создаем директорию если не существует
            Files.createDirectories(filePath.getParent());

            // Сериализуем данные в JSON
            JSONObject json = mapToJsonObject(storage);

            // Записываем в файл с красивым форматированием
            Files.write(filePath, json.toString(2).getBytes());

            logInfo("Состояние сохранено в файл: " + filename);
            return true;

        } catch (Exception e) {
            logError("saveState", "Ошибка сохранения состояния для ключа: " + key, e);
            return false;
        } finally {
        visitedObjects.remove();
    }
    }

    /**
     * Загрузка состояния хранилища из файла
     * Атомарно заменяет все данные в хранилище
     *
     * @param key ключ для идентификации файла
     * @return true если загрузка успешна, false при ошибке
     */
    public synchronized boolean loadState(String key) {
        validateContext();

        try {
            String filename = getFilename(key);
            Path filePath = Paths.get(filename);

            // Проверяем существование файла
            if (!Files.exists(filePath)) {
                logInfo("Файл состояния не найден: " + filename);
                return false;
            }

            // Читаем содержимое файла
            String jsonContent = new String(Files.readAllBytes(filePath));
            JSONObject json = new JSONObject(jsonContent);

            // Валидируем JSON перед загрузкой
            if (!validateLoadedJson(json)) {
                logError("loadState", "Невалидный JSON формат для ключа: " + key, null);
                return false;
            }

            // Десериализуем JSON в ConcurrentHashMap
            ConcurrentHashMap<String, Object> loadedData = jsonObjectToMap(json);

            // Атомарная замена данных в хранилище
            synchronized (storage) {
                storage.clear();
                storage.putAll(loadedData);
            }

            // Восстанавливаем значения по умолчанию (если их нет в загруженных данных)
            initializeDefaultValues();

            // Удаляем файл из которого восстановили
            clearState(key);

            logInfo("Состояние загружено из файла: " + filename);
            return true;

        } catch (Exception e) {
            logError("loadState", "Ошибка загрузки состояния для ключа: " + key, e);
            return false;
        }
    }

    /**
     * Удаление файла состояния
     *
     * @param key ключ файла для удаления
     * @return true если удаление успешно, false при ошибке
     */
    public boolean clearState(String key) {
        validateContext();

        try {
            String filename = getFilename(key);
            Path filePath = Paths.get(filename);

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                logInfo("Файл состояния удален: " + filename);
            }
            return true;

        } catch (Exception e) {
            logError("clearState", "Ошибка удаления файла состояния для ключа: " + key, e);
            return false;
        }
    }

    /**
     * Генерация имени файла для сохранения/загрузки состояния
     * Использует внутреннюю директорию приложения
     *
     * @param key ключ для идентификации файла
     * @return абсолютный путь к файлу
     * @throws IllegalStateException если контекст не инициализирован
     */
    private String getFilename(String key) {
        validateContext();

        File filesDir = appContext.getFilesDir();
        if (filesDir == null) {
            throw new IllegalStateException("Не удалось получить доступ к директории файлов приложения");
        }

        return filesDir.getAbsolutePath() + File.separator +
                FILE_PREFIX + key + FILE_EXTENSION;
    }

    /** Уничтожение копии класса
     */
    public synchronized void destroy() {
        storage.clear();
        appContext = null;
        visitedObjects.remove(); // очищаем ThreadLocal
        logInfo("Хранилище полностью очищено и подготовлено к GC");
    }

    //endregion

    //region ==================== JSON СЕРИАЛИЗАЦИЯ И ДЕСЕРИАЛИЗАЦИЯ ====================

    /**
     * Преобразование Map в JSONObject с поддержкой сложных типов
     *
     * @param map исходная Map для сериализации
     * @return JSONObject с сериализованными данными
     */
    private JSONObject mapToJsonObject(Map<String, Object> map) {
        // Очищаем set посещенных объектов для текущего потока
        visitedObjects.get().clear();
        return mapToJsonObject(map, visitedObjects.get());
    }

    /**
     * Рекурсивное преобразование Map в JSONObject с защитой от циклических ссылок
     *
     * @param map исходная Map
     * @param visited Set уже посещенных объектов (для защиты от циклических ссылок)
     * @return JSONObject
     */
    private JSONObject mapToJsonObject(Map<String, Object> map, Set<Object> visited) {
        JSONObject json = new JSONObject();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            try {
                // Пропускаем null значения
                if (value == null) {
                    safeJsonPut(json, key, JSONObject.NULL);
                    continue;
                }

                // Проверка на циклическую ссылку
                if (visited.contains(value)) {
                    logWarning("Обнаружена циклическая ссылка для ключа: " + key + ", пропускаем");
                    continue;
                }

                // Добавляем объект в посещенные
                visited.add(value);

                // Обработка разных типов данных
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedMap = (Map<String, Object>) value;

                    // Проверяем размер коллекции
                    if (!validateCollectionSize(nestedMap)) {
                        logWarning("Map слишком большой для ключа: " + key + ", пропускаем");
                        continue;
                    }

                    try {
                        JSONObject mapWithType = new JSONObject();
                        safeJsonPut(mapWithType, TYPE_MARKER, MAP_TYPE);
                        safeJsonPut(mapWithType, "value", mapToJsonObject(nestedMap, visited));
                        safeJsonPut(json, key, mapWithType);
                    } catch (Exception e) {
                        logError("mapToJsonObject", "Ошибка сериализации Map для ключа: " + key, e);
                        continue;
                    }

                } else if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) value;

                    // Проверяем размер коллекции
                    if (!validateCollectionSize(list)) {
                        logWarning("List слишком большой для ключа: " + key + ", пропускаем");
                        continue;
                    }

                    try {
                        JSONObject listWithType = new JSONObject();
                        safeJsonPut(listWithType, TYPE_MARKER, LIST_TYPE);
                        safeJsonPut(listWithType, "value", listToJsonArray(list, visited));
                        safeJsonPut(json, key, listWithType);
                    } catch (Exception e) {
                        logError("mapToJsonObject", "Ошибка сериализации List для ключа: " + key, e);
                        continue;
                    }

                } else if (value instanceof Set) {
                    @SuppressWarnings("unchecked")
                    Set<Object> set = (Set<Object>) value;

                    // Проверяем размер коллекции
                    if (!validateCollectionSize(set)) {
                        logWarning("Set слишком большой для ключа: " + key + ", пропускаем");
                        continue;
                    }

                    try {
                        JSONObject setWithType = new JSONObject();
                        safeJsonPut(setWithType, TYPE_MARKER, SET_TYPE);
                        safeJsonPut(setWithType, "value", collectionToJsonArray(set, visited));
                        safeJsonPut(json, key, setWithType);
                    } catch (Exception e) {
                        logError("mapToJsonObject", "Ошибка сериализации Set для ключа: " + key, e);
                        continue;
                    }

                } else {
                    // Простые типы (String, Number, Boolean)
                    safeJsonPut(json, key, value);
                }

                // Удаляем объект из посещенных после обработки
                visited.remove(value);

            } catch (Exception e) {
                logError("mapToJsonObject", "Ошибка сериализации ключа: " + key, e);
            }
        }

        return json;
    }

    /**
     * Безопасное добавление значения в JSONObject
     */
    private void safeJsonPut(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception e) {
            logError("safeJsonPut", "Ошибка добавления ключа: " + key + " со значением: " + value, e);
        }
    }

    /**
     * Преобразование List в JSONArray
     */
    private JSONArray listToJsonArray(List<Object> list, Set<Object> visited) {
        JSONArray array = new JSONArray();
        for (Object item : list) {
            try {
                array.put(convertObjectToJsonValue(item, visited));
            } catch (Exception e) {
                logError("listToJsonArray", "Ошибка сериализации элемента списка", e);
            }
        }
        return array;
    }

    /**
     * Преобразование Collection в JSONArray
     */
    private JSONArray collectionToJsonArray(Collection<Object> collection, Set<Object> visited) {
        JSONArray array = new JSONArray();
        for (Object item : collection) {
            try {
                array.put(convertObjectToJsonValue(item, visited));
            } catch (Exception e) {
                logError("collectionToJsonArray", "Ошибка сериализации элемента коллекции", e);
            }
        }
        return array;
    }

    /**
     * Преобразование объекта в значение для JSON
     */
    private Object convertObjectToJsonValue(Object item, Set<Object> visited) {
        if (item == null) {
            return JSONObject.NULL;
        }

        // Проверка на циклическую ссылку
        if (visited.contains(item)) {
            logWarning("Обнаружена циклическая ссылка при сериализации, возвращаем null");
            return JSONObject.NULL;
        }

        visited.add(item);
        Object result;

        if (item instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMap = (Map<String, Object>) item;

            if (!validateCollectionSize(nestedMap)) {
                logWarning("Map слишком большой при сериализации, возвращаем null");
                result = JSONObject.NULL;
            } else {

                JSONObject mapWithType = new JSONObject();
                try {
                    mapWithType.putOpt(TYPE_MARKER, MAP_TYPE);
                    mapWithType.putOpt("value", mapToJsonObject(nestedMap, visited));
                } catch (Exception e) {
                    logError("convertObjectToJsonValue", "Ошибка сериализации Map", e);
                    return JSONObject.NULL;
                }
                result = mapWithType;
            }

        } else if (item instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> nestedList = (List<Object>) item;

            if (!validateCollectionSize(nestedList)) {
                logWarning("List слишком большой при сериализации, возвращаем null");
                result = JSONObject.NULL;
            } else {
                JSONObject listWithType = new JSONObject();
                try {
                    listWithType.putOpt(TYPE_MARKER, LIST_TYPE);
                    listWithType.putOpt("value", listToJsonArray(nestedList, visited));
                } catch (Exception e) {
                    logError("convertObjectToJsonValue", "Ошибка сериализации List", e);
                    return JSONObject.NULL;
                }
                result = listWithType;
            }

        } else if (item instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<Object> nestedSet = (Set<Object>) item;

            if (!validateCollectionSize(nestedSet)) {
                logWarning("Set слишком большой при сериализации, возвращаем null");
                result = JSONObject.NULL;
            } else {
                JSONObject setWithType = new JSONObject();
                try {
                    setWithType.putOpt(TYPE_MARKER, SET_TYPE);
                    setWithType.putOpt("value", collectionToJsonArray(nestedSet, visited));
                } catch (Exception e) {
                    logError("convertObjectToJsonValue", "Ошибка сериализации Set", e);
                    return JSONObject.NULL;
                }
                result = setWithType;
            }

        } else {
            // Простые типы
            result = item;
        }

        visited.remove(item);
        return result;
    }

    /**
     * Преобразование JSONObject в ConcurrentHashMap
     */
    private ConcurrentHashMap<String, Object> jsonObjectToMap(JSONObject json) {
        ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();
        Iterator<String> keys = json.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object value = json.get(key);
                if (value != null) {
                    map.put(key, convertJsonValueToObject(value));
                }
            } catch (Exception e) {
                logError("jsonObjectToMap", "Ошибка десериализации ключа: " + key, e);
            }
        }

        return map;
    }

    /**
     * Преобразование JSON значения в объект Java
     */
    private Object convertJsonValueToObject(Object value) {
        if (value == JSONObject.NULL) {
            return null;
        }

        if (value instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) value;

            // Проверяем, является ли это типизированной коллекцией
            if (jsonObject.has(TYPE_MARKER)) {
                String type = jsonObject.optString(TYPE_MARKER);
                Object collectionValue = jsonObject.opt("value");

                switch (type) {
                    case MAP_TYPE:
                        if (collectionValue instanceof JSONObject) {
                            return jsonObjectToMap((JSONObject) collectionValue);
                        }
                        break;
                    case LIST_TYPE:
                        if (collectionValue instanceof JSONArray) {
                            return jsonArrayToList((JSONArray) collectionValue);
                        }
                        break;
                    case SET_TYPE:
                        if (collectionValue instanceof JSONArray) {
                            return jsonArrayToSet((JSONArray) collectionValue);
                        }
                        break;
                    default:
                        logWarning("Неизвестный тип коллекции: " + type);
                }
            }

            // Обычный JSONObject
            return jsonObjectToMap(jsonObject);

        } else if (value instanceof JSONArray) {
            return jsonArrayToList((JSONArray) value);
        } else {
            // Простые типы (String, Number, Boolean)
            return value;
        }


    }

    /**
     * Преобразование JSONArray в List
     */
    private List<Object> jsonArrayToList(JSONArray array) {
        List<Object> list = new ArrayList<>();
        int length = array.length();

        for (int i = 0; i < length; i++) {
            try {
                Object item = array.get(i);
                if (item != null) {
                    list.add(convertJsonValueToObject(item));
                }
            } catch (Exception e) {
                logError("jsonArrayToList", "Ошибка десериализации элемента массива", e);
            }
        }

        return list;
    }

    /**
     * Преобразование JSONArray в Set
     */
    private Set<Object> jsonArrayToSet(JSONArray array) {
        Set<Object> set = new HashSet<>();
        int length = array.length();

        for (int i = 0; i < length; i++) {
            try {
                Object item = array.get(i);
                if (item != null) {
                    set.add(convertJsonValueToObject(item));
                }
            } catch (Exception e) {
                logError("jsonArrayToSet", "Ошибка десериализации элемента множества", e);
            }
        }

        return set;
    }

    //endregion

    //region ==================== ВАЛИДАЦИЯ JSON ====================

    /**
     * Валидация загруженного JSON перед десериализацией
     *
     * @param json JSONObject для валидации
     * @return true если JSON валиден, false если есть проблемы
     */
    private boolean validateLoadedJson(JSONObject json) {
        try {
            // Проверка глубины вложенности
            if (!validateJsonDepth(json, 0)) {
                logError("validateLoadedJson", "Превышена максимальная глубина вложенности JSON", null);
                return false;
            }

            // Проверка количества ключей
            if (countJsonKeys(json) > MAX_JSON_KEYS) {
                logError("validateLoadedJson", "Превышено максимальное количество ключей в JSON", null);
                return false;
            }

            // Проверка типизированных коллекций
            if (!validateTypedCollections(json)) {
                logError("validateLoadedJson", "Ошибка валидации типизированных коллекций", null);
                return false;
            }

            return true;

        } catch (Exception e) {
            logError("validateLoadedJson", "Ошибка валидации JSON", e);
            return false;
        }
    }

    /**
     * Рекурсивная проверка глубины вложенности JSON
     */
    private boolean validateJsonDepth(JSONObject json, int currentDepth) {
        if (currentDepth > MAX_JSON_DEPTH) {
            return false;
        }

        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object value = json.get(key);

                if (value instanceof JSONObject) {
                    if (!validateJsonDepth((JSONObject) value, currentDepth + 1)) {
                        return false;
                    }
                } else if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    int length = array.length();

                    for (int i = 0; i < length; i++) {
                        Object item = array.get(i);
                        if (item instanceof JSONObject) {
                            if (!validateJsonDepth((JSONObject) item, currentDepth + 1)) {
                                return false;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logError("validateJsonDepth", "Ошибка проверки глубины для ключа: " + key, e);
                return false;
            }
        }

        return true;
    }

    /**
     * Подсчет общего количества ключей в JSON
     */
    private int countJsonKeys(JSONObject json) {
        int count = json.length();
        Iterator<String> keys = json.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object value = json.get(key);

                if (value instanceof JSONObject) {
                    count += countJsonKeys((JSONObject) value);
                } else if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    int length = array.length();

                    for (int i = 0; i < length; i++) {
                        Object item = array.get(i);
                        if (item instanceof JSONObject) {
                            count += countJsonKeys((JSONObject) item);
                        }
                    }
                }
            } catch (Exception e) {
                logError("countJsonKeys", "Ошибка подсчета ключей", e);
            }
        }

        return count;
    }

    /**
     * Валидация типизированных коллекций в JSON
     */
    private boolean validateTypedCollections(JSONObject json) {
        Iterator<String> keys = json.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object value = json.get(key);

                if (value instanceof JSONObject) {
                    JSONObject obj = (JSONObject) value;

                    if (obj.has(TYPE_MARKER)) {
                        String type = obj.optString(TYPE_MARKER);

                        // Проверяем наличие значения
                        if (!obj.has("value")) {
                            logError("validateTypedCollections",
                                    "Типизированная коллекция без значения: " + key, null);
                            return false;
                        }

                        // Проверяем известный тип
                        if (!type.equals(MAP_TYPE) && !type.equals(LIST_TYPE) && !type.equals(SET_TYPE)) {
                            logError("validateTypedCollections",
                                    "Неизвестный тип коллекции: " + type, null);
                            return false;
                        }
                    }

                    // Рекурсивная проверка вложенных объектов
                    if (!validateTypedCollections(obj)) {
                        return false;
                    }
                }
            } catch (Exception e) {
                logError("validateTypedCollections", "Ошибка валидации для ключа: " + key, e);
                return false;
            }
        }

        return true;
    }

    /**
     * Проверка размера коллекции
     */
    private boolean validateCollectionSize(Object collection) {
        if (collection instanceof Collection) {
            return ((Collection<?>) collection).size() <= MAX_COLLECTION_SIZE;
        }
        if (collection instanceof Map) {
            return ((Map<?, ?>) collection).size() <= MAX_COLLECTION_SIZE;
        }
        return true;
    }

    //endregion

    //region ==================== БАЗОВЫЕ ОПЕРАЦИИ С ДАННЫМИ ====================

    /**
     * Сохранение значения в хранилище
     *
     * @param key ключ для сохранения
     * @param value значение (null для удаления)
     */
    public void put(String key, Object value) {
        if (value == null) {
            storage.remove(key);
            logDebug("Удален ключ: " + key);
        } else {
            storage.put(key, value);
            logDebug("Сохранен ключ: " + key + " = " + value);
        }
    }

    /**
     * Получение значения из хранилища
     *
     * @param key ключ для получения
     * @return значение или null если ключ не существует
     */
    public Object get(String key) {
        return storage.get(key);
    }

    /**
     * Удаление значения из хранилища
     *
     * @param key ключ для удаления
     */
    public void remove(String key) {
        storage.remove(key);
        logDebug("Удален ключ: " + key);
    }

    /**
     * Проверка существования ключа
     *
     * @param key ключ для проверки
     * @return true если ключ существует
     */
    public boolean containsKey(String key) {
        return storage.containsKey(key);
    }

    /**
     * Очистка всего хранилища
     */
    public void clear() {
        storage.clear();
        logInfo("Хранилище полностью очищено");
    }

    //endregion

    //region ==================== ТИПИЗИРОВАННЫЕ ГЕТТЕРЫ ====================

    /**
     * Получение строкового значения
     *
     * @param key ключ
     * @return строковое значение или пустая строка если ключ не существует
     */
    public String getString(String key) {
        Object value = storage.get(key);
        return value != null ? value.toString() : "";
    }

    /**
     * Получение целочисленного значения
     *
     * @param key ключ
     * @return целочисленное значение или 0 если ключ не существует
     */
    public int getInt(String key) {
        Object value = storage.get(key);

        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        return 0;
    }

    /**
     * Получение длинного целочисленного значения
     *
     * @param key ключ
     * @return длинное целочисленное значение или 0L если ключ не существует
     */
    public long getLong(String key) {
        Object value = storage.get(key);

        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        return 0L;
    }

    /**
     * Получение булевого значения
     *
     * @param key ключ
     * @return булево значение или false если ключ не существует
     */
    public boolean getBoolean(String key) {
        Object value = storage.get(key);

        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }

        return false;
    }

    /**
     * Получение значения с плавающей точкой
     *
     * @param key ключ
     * @return значение с плавающей точкой или 0.0f если ключ не существует
     */
    public float getFloat(String key) {
        Object value = storage.get(key);

        if (value instanceof Float) {
            return (Float) value;
        } else if (value instanceof Double) {
            return ((Double) value).floatValue();
        } else if (value instanceof Number) {
            return ((Number) value).floatValue();
        } else if (value instanceof String) {
            try {
                return Float.parseFloat((String) value);
            } catch (NumberFormatException e) {
                return 0.0f;
            }
        }

        return 0.0f;
    }

    /**
     * Получение значения с двойной точностью
     *
     * @param key ключ
     * @return значение с двойной точностью или 0.0 если ключ не существует
     */
    public double getDouble(String key) {
        Object value = storage.get(key);

        if (value instanceof Double) {
            return (Double) value;
        } else if (value instanceof Float) {
            return ((Float) value).doubleValue();
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        return 0.0;
    }

    //endregion

    //region ==================== ОПЕРАЦИИ С КОЛЛЕКЦИЯМИ ====================

    /**
     * Получение неизменяемой копии всех данных
     *
     * @return неизменяемая Map всех данных
     */
    public Map<String, Object> getAll() {
        return Collections.unmodifiableMap(new HashMap<>(storage));
    }

    /**
     * Получение неизменяемого набора ключей
     *
     * @return неизменяемый Set ключей
     */
    public Set<String> keySet() {
        return Collections.unmodifiableSet(storage.keySet());
    }

    /**
     * Добавление всех пар ключ-значение из Map
     *
     * @param map Map для добавления
     */
    public void putAll(Map<String, ?> map) {
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            this.put(entry.getKey(), entry.getValue());
        }
        logDebug("Добавлено " + map.size() + " записей из Map");
    }

    /**
     * Удаление нескольких ключей
     *
     * @param keys коллекция ключей для удаления
     * @return количество удаленных ключей
     */
    public int removeAll(Collection<String> keys) {
        int count = 0;
        for (String key : keys) {
            if (storage.remove(key) != null) {
                count++;
            }
        }
        logDebug("Удалено " + count + " ключей из " + keys.size());
        return count;
    }

    /**
     * Получение ключей по префиксу
     *
     * @param prefix префикс для поиска
     * @return Set ключей начинающихся с префикса
     */
    public Set<String> getKeysByPrefix(String prefix) {
        return storage.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .collect(Collectors.toSet());
    }

    /**
     * Получение отфильтрованных данных
     *
     * @param keyFilter предикат для фильтрации ключей
     * @return Map отфильтрованных данных
     */
    public Map<String, Object> getFiltered(Predicate<String> keyFilter) {
        return storage.entrySet().stream()
                .filter(entry -> keyFilter.test(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    //endregion

    //region ==================== ЛОГГИРОВАНИЕ ====================

    /**
     * Логирование информационных сообщений
     */
    private void logInfo(String message) {
        Log.i(TAG, message);
    }

    /**
     * Логирование отладочных сообщений
     */
    private void logDebug(String message) {
        Log.d(TAG, message);
    }

    /**
     * Логирование предупреждений
     */
    private void logWarning(String message) {
        Log.w(TAG, message);
    }

    /**
     * Логирование ошибок
     */
    private void logError(String method, String message, Exception e) {
        if (e != null) {
            Log.e(TAG, method + ": " + message, e);
        } else {
            Log.e(TAG, method + ": " + message);
        }
    }

    //endregion
}