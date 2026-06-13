/**
 * Copyright (c) 2025 Алексей <smolpochta@gmail.com>
 * Все права защищены.
 *
 * Модуль для выполнения операций на сервере
 * Реализована функция авторизации на сервере через Google Apps Script (точка доступа, javascript, данные в закрытом spreadsheets),
 * но можно быстро переделать на локальную версию: простую (сетевая папка, файл настроек доступа csv, дистрибутивы обновления, scripts перенести в клиент) или сложную (веб-сервер, flask и т.д.)
 * Обеспечивает взаимодействие с сервером для проверки версии приложения,
 * глобального доступа и индивидуальных прав пользователей.
 */

package com.smolpochta.decanter;

import android.content.Context;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkCapabilities;
import android.os.Build;

public class CheckServerData {
    private static final String TAG = "CheckServerData";
    private final Context context;
    private final SeanceDataStorage dataStorage;
    private final FileStorageManager fileStorage;

    // Коды ошибок для авторизации
    private static final class AuthErrorCodes {
        static final int NETWORK_UNAVAILABLE = 777;
        static final int VERSION_MISMATCH = 1;
        static final int SERVER_UNAVAILABLE = 2;
        static final int GLOBAL_ACCESS_DENIED = 3;
        static final int CLIENT_NOT_FOUND = 4;
        static final int CLIENT_ACCESS_DENIED = 5;
        static final int UNEXPECTED_FORMAT = 6;
        static final int UNEXPECTED = 7;
    }

    /**
     * Конструктор основного класса для инициализации зависимостей.
     *
     * @param context      Android контекст приложения
     * @param dataStorage  хранилище данных для сохранения результатов авторизации
     * @param fileStorage  менеджер файлов для работы с файловой системой
     */
    public CheckServerData(Context context, SeanceDataStorage dataStorage, FileStorageManager fileStorage) {
        this.context = context.getApplicationContext();
        this.dataStorage = dataStorage;
        this.fileStorage = fileStorage;
    }

    /**
     * Быстрый метод для выполнения авторизации на сервере.
     * Создает экземпляр внутреннего класса Authorization и запускает процесс.
     */
    public void performAuthorization() {
        Authorization auth = new Authorization(context, dataStorage);
        auth.execute();
    }

    /**
     * Внутренний класс для обработки авторизации на сервере.
     * Выполняет проверку глобального доступа, соответствия версии и индивидуального доступа.
     */
    private class Authorization {
        private static final String TAG = "CheckServerData.Authorization";
        private volatile boolean isRunning = false;
        private Context context;
        private SeanceDataStorage dataStorage;

        /**
         * Конструктор класса Authorization.
         * Инициализирует зависимости для работы с контекстом и хранилищами.
         */
        public Authorization(Context context, SeanceDataStorage dataStorage) {
            this.context = context.getApplicationContext();
            this.dataStorage = dataStorage;
        }

        /**
         * Основной метод выполнения авторизации.
         * Запускает процесс проверки доступа на сервере с защитой от повторного запуска.
         */
        public void execute() {
            Log.d(TAG, "Вызван Authorization.execute(), isRunning: " + isRunning);

            // Защита от повторного запуска
            if (isRunning) {
                Log.w(TAG, "Авторизация уже выполняется - пропускаем");
                return;
            }

            if (!isNetworkAvailable()) {
                dataStorage.put("AccessSuccess_errorCode", AuthErrorCodes.NETWORK_UNAVAILABLE);
                dataStorage.put("AccessSuccess_errorText", "Сеть недоступна");
                dataStorage.put("DownloadFile_stop", System.currentTimeMillis());
                Log.w(TAG, "Сеть недоступна");
                return;
            }

            isRunning = true;
            long startTime = System.currentTimeMillis();

            try {
                Log.i(TAG, "Начало авторизации на сервере");

                // Устанавливаем начальные значения
                dataStorage.put("AccessSuccess_start", startTime);
                dataStorage.put("Progress_status", "Обмен с сервером начат");

                // Основная логика авторизации
                performServerAuthorization();

                Log.i(TAG, "Авторизация завершена успешно");

            } catch (Exception e) {
                Log.e(TAG, "Непредвиденная ошибка в Authorization", e);
                handleServerError(AuthErrorCodes.UNEXPECTED, "Непредвиденная ошибка: " + e.getMessage());
            } finally {
                isRunning = false;
                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Завершение авторизации, длительность: " + duration + "мс");
            }
        }

        /**
         * Проверка доступности сети на устройстве.
         * Использует разные методы для разных версий Android.
         *
         * @return true если сеть доступна, false в противном случае
         */
        private boolean isNetworkAvailable() {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager == null) {
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Для Android 6.0+
                android.net.Network network = connectivityManager.getActiveNetwork();
                if (network == null) return false;

                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                return capabilities != null &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            } else {
                // Для старых версий Android
                NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
                return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
            }
        }

        /**
         * Основная логика авторизации на сервере.
         * Определяет режим работы (отладка или реальный сервер) и выполняет проверку.
         */
        private void performServerAuthorization() {
            Log.d(TAG, "Выполняем авторизацию на сервере");

            // Режим отладки - заглушка для тестирования
            boolean debugMode = false;
            if (debugMode) {
                Log.w(TAG, "Режим отладки активирован - используем заглушку");
                handleDebugMode();
            } else {
                Log.d(TAG, "Выполняем реальную проверку сервера");
                handleRealServerCheck();
            }

            dataStorage.put("Progress_status", "Обмен с сервером завершен");
            dataStorage.put("AccessSuccess_stop", System.currentTimeMillis());
            Log.d(TAG, "Обмен с сервером завершен");
        }

        /**
         * Отправка запроса на сервер для проверки доступа.
         * Выполняет двухэтапный запрос с обработкой редиректа Google Apps Script.
         *
         * @param urlString URL для отправки запроса
         * @return JSON-объект с результатом запроса
         */
        private JSONObject requestServerAccess(String urlString) {
            Log.d(TAG, "Отправка запроса на сервер: " + maskUrlForLogging(urlString));

            HttpURLConnection connection = null;
            boolean result = false;
            String text = "";
            String data = "";

            try {
                // Первый запрос - получаем редирект
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);

                // Устанавливаем заголовки как в браузере
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36");
                connection.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
                connection.setRequestProperty("Accept-Language",
                        "en,cs;q=0.9,de;q=0.8,zh-CN;q=0.7,zh;q=0.6,ru;q=0.5,es;q=0.4,tr;q=0.3");

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Код ответа сервера: " + responseCode);

                // Запрос отправлен
                dataStorage.put("AccessSuccess_sent", true);
                Log.d(TAG, "Запрос успешно отправлен");

                // Проверяем редирект (302)
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_SEE_OTHER) {

                    dataStorage.put("Progress_status", "Сервер обработал запрос");
                    Log.d(TAG, "Сервер выполнил редирект");

                    // Получаем URL редиректа из заголовка Location
                    String redirectUrl = connection.getHeaderField("Location");
                    Log.d(TAG, "URL редиректа: " + maskUrlForLogging(redirectUrl));

                    connection.disconnect();

                    if (redirectUrl != null && redirectUrl.startsWith("https://script.googleusercontent.com")) {
                        Log.d(TAG, "Валидный редирект - выполняем второй запрос");

                        // Второй запрос - получаем данные
                        URL redirectURL = new URL(redirectUrl);
                        connection = (HttpURLConnection) redirectURL.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout(15000);
                        connection.setReadTimeout(15000);

                        // Устанавливаем те же заголовки
                        connection.setRequestProperty("User-Agent",
                                "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36");
                        connection.setRequestProperty("Accept",
                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");

                        responseCode = connection.getResponseCode();
                        Log.d(TAG, "Код ответа от редиректа: " + responseCode);

                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            // Используем чтение блоками
                            BufferedInputStream bis = new BufferedInputStream(connection.getInputStream(), 8192);
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int bytesRead;

                            while ((bytesRead = bis.read(buffer)) != -1) {
                                bos.write(buffer, 0, bytesRead);
                            }

                            data = bos.toString("UTF-8");
                            bis.close();
                            bos.close();

                            result = true;

                            dataStorage.put("AccessSuccess_received", true);
                            dataStorage.put("Progress_status", "Ответ сервера получен");

                            Log.d(TAG, "Данные от сервера успешно получены, размер: " + data.length() + " символов");

                        } else {
                            text = "HTTP " + responseCode + " in redirect";
                            Log.w(TAG, "Ошибка редиректа: " + text);
                        }
                    } else {
                        text = "Invalid redirect URL: " + (redirectUrl != null ? redirectUrl : "null");
                        Log.w(TAG, text);
                    }
                } else {
                    text = "Expected redirect, got HTTP " + responseCode;
                    Log.w(TAG, text);
                }

            } catch (Exception e) {
                text = "Exception: " + e.getMessage();
                Log.e(TAG, "Ошибка подключения к серверу", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                    Log.d(TAG, "Соединение с сервером закрыто");
                }
            }

            // Формируем ответ
            JSONObject jsonResult = new JSONObject();
            try {
                jsonResult.put("result", result);
                jsonResult.put("text", text);
                jsonResult.put("data", data);

                Log.d(TAG, "Результат запроса: " + (result ? "УСПЕХ" : "НЕУДАЧА") +
                        ", текст: " + (text.isEmpty() ? "нет" : text.substring(0, Math.min(text.length(), 100))));

            } catch (JSONException e) {
                try {
                    jsonResult.put("result", false);
                    jsonResult.put("text", "JSON Error: " + e.getMessage());
                    jsonResult.put("data", "");
                    Log.e(TAG, "Ошибка формирования JSON ответа", e);
                } catch (JSONException e2) {
                    Log.e(TAG, "Критическая ошибка JSON", e2);
                }
            }

            return jsonResult;
        }

        /**
         * Маскирует URL для безопасного логирования.
         * Убирает чувствительные параметры, такие как clientId.
         *
         * @param url исходный URL для маскирования
         * @return замаскированный URL
         */
        private String maskUrlForLogging(String url) {
            if (url == null) return "null";
            if (url.contains("clientId=")) {
                return url.replaceAll("clientId=[^&]*", "clientId=***");
            }
            return url;
        }

        /**
         * Обработка режима отладки.
         * Устанавливает демо-данные для тестирования без подключения к серверу.
         */
        private void handleDebugMode() {
            Log.i(TAG, "Режим отладки - устанавливаем демо-данные");

            dataStorage.put("AccessSuccess", true);
            dataStorage.put("AccessSuccess_demo", true);
            dataStorage.put("AccessSuccess_sent", dataStorage.getLong("AccessSuccess_start"));
            dataStorage.put("AccessSuccess_received", dataStorage.getLong("AccessSuccess_start"));
            dataStorage.put("AccessSuccess_userName", "Отладка");
            dataStorage.put("AccessSuccess_userFullName", "дорогой Разработчик");
            dataStorage.put("AccessSuccess_objectCode", "000000001");
            dataStorage.put("AccessSuccess_objectName", "ООО Прогресо");

            Log.d(TAG, "Демо-данные установлены: пользователь=Гость, объект=ООО Прогресо");
        }

        /**
         * Реальная проверка на сервере.
         * Формирует запрос к серверу и обрабатывает ответ.
         */
        private void handleRealServerCheck() {
            String clientId = dataStorage.getString("AccessSuccess_clientId");
            int currentVersionCode = AppVersionUtils.getCode(context);

            Log.d(TAG, "Параметры авторизации: clientId=" + clientId + ", versionCode=" + currentVersionCode);

            dataStorage.put("Progress_status", "Отправляем запрос на сервер");
            Log.d(TAG, "Отправляем запрос авторизации");

            String endPoint = "https://script.google.com/macros/s/AKfycbxReiAd-grJsKW1v7POa8V7Aq6WO_RPyK60qmEiVucCUTDEajKd56OhjLCA-jxTo07nbg/exec?clientId=" + clientId + "&ver=" + currentVersionCode;

            JSONObject response = requestServerAccess(endPoint);

            if (!response.optBoolean("result", false)) {
                Log.e(TAG, "Не удалось получить ответ от сервера");
                handleServerError(AuthErrorCodes.SERVER_UNAVAILABLE, "Не удалось получить ответ от сервера");
            } else {
                Log.d(TAG, "Ответ от сервера получен - обрабатываем");
                processServerResponse(response);
            }
        }

        /**
         * Обработка успешного ответа от сервера.
         * Анализирует полученные данные и определяет дальнейшие действия.
         *
         * @param response JSON-объект с ответом сервера
         */
        private void processServerResponse(JSONObject response) {
            try {
                JSONObject responseData = new JSONObject(response.getString("data"));
                Log.d(TAG, "Данные от сервера прочитаны, success=" + responseData.optBoolean("success"));

                if (!responseData.getBoolean("success")) {
                    Log.e(TAG, "Доступ закрыт глобально");
                    handleServerError(AuthErrorCodes.GLOBAL_ACCESS_DENIED, "Доступ закрыт глобально");
                } else if (!responseData.getString("currentVersion").isEmpty()) {
                    Log.w(TAG, "Необходимо обновление версии");
                    handleVersionMismatch(responseData);
                } else {
                    Log.d(TAG, "Глобальный доступ разрешен - проверяем клиента");
                    handleClientAccessCheck(responseData);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Ошибка формата ответа сервера", e);
                handleServerError(AuthErrorCodes.UNEXPECTED_FORMAT, "Ошибка формата ответа сервера");
            }
        }

        /**
         * Обработка несоответствия версии приложения.
         * Сохраняет информацию о требуемой версии в хранилище.
         *
         * @param responseData JSON-объект с данными о версии
         * @throws JSONException при ошибках парсинга JSON
         */
        private void handleVersionMismatch(JSONObject responseData) throws JSONException {
            String requiredVersion = responseData.getString("currentVersion");
            String distribution = responseData.getString("currentDistribution");

            dataStorage.put("AccessSuccess_errorText",
                    "Необходимо обновить программу на версию " + requiredVersion);
            dataStorage.put("AccessSuccess_errorCode", AuthErrorCodes.VERSION_MISMATCH);
            dataStorage.put("AccessSuccess_currentVersion", requiredVersion);
            dataStorage.put("AccessSuccess_currentDistribution", distribution);

            Log.w(TAG, "Требуется обновление: версия " + requiredVersion +
                    ", дистрибутив: " + (distribution.isEmpty() ? "не указан" : distribution));
        }

        /**
         * Проверка доступа для конкретного клиента.
         * Анализирует данные пользователя из ответа сервера.
         *
         * @param responseData JSON-объект с данными клиента
         * @throws JSONException при ошибках парсинга JSON
         */
        private void handleClientAccessCheck(JSONObject responseData) throws JSONException {
            boolean demo = responseData.getBoolean("demo");
            if (demo) {
                Log.i(TAG, "Активирован демо-режим");
                dataStorage.put("AccessSuccess_demo", true);
            }

            dataStorage.put("AccessSuccess_welcome", responseData.getString("welcome"));
            dataStorage.put("AccessSuccess_welcomeText", responseData.getString("welcomeText"));

            if (responseData.getString("data").isEmpty()) {
                Log.e(TAG, "Информация о клиенте не найдена");
                handleServerError(AuthErrorCodes.CLIENT_NOT_FOUND, "Информация о клиенте не найдена");
            } else {
                JSONObject userData = new JSONObject(responseData.getString("data"));
                Log.d(TAG, "Данные клиента: success=" + userData.optBoolean("success"));

                if (userData.getBoolean("success")) {
                    Log.i(TAG, "Доступ для клиента разрешен");
                    handleSuccessfullAccess(userData);
                } else {
                    Log.e(TAG, "Доступ для клиента запрещен");
                    handleServerError(AuthErrorCodes.CLIENT_ACCESS_DENIED, "Доступ для клиента запрещен");
                }
            }
        }

        /**
         * Обработка успешного доступа пользователя.
         * Сохраняет данные пользователя и объекта в хранилище.
         *
         * @param userData JSON-объект с данными пользователя
         * @throws JSONException при ошибках парсинга JSON
         */
        private void handleSuccessfullAccess(JSONObject userData) throws JSONException {
            String userName = userData.getString("name");
            String userFullName = userData.getString("fullName");
            String objectCode = userData.getString("object");
            String objectName = userData.getString("object_name");

            dataStorage.put("AccessSuccess_userName", userName);
            dataStorage.put("AccessSuccess_userFullName", userFullName);
            dataStorage.put("AccessSuccess_objectCode", objectCode);
            dataStorage.put("AccessSuccess_objectName", objectName);

            // Обновление дополнительных параметров из сервера
            if (userData.has("refresh")) {
                JSONObject refresh = userData.getJSONObject("refresh");
                java.util.Iterator<String> keys = refresh.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = refresh.get(key);
                    dataStorage.put(key, value);
                }
            }

            Log.i(TAG, "Авторизация успешна: " +
                    "\n   Пользователь: " + userName +
                    "\n   Объект: " + objectName + " (" + objectCode + ")");
        }

        /**
         * Обработка ошибки соединения с сервером.
         * Сохраняет информацию об ошибке в хранилище данных.
         *
         * @param errorCode код ошибки
         * @param errorText текстовое описание ошибки
         */
        private void handleServerError(int errorCode, String errorText) {
            dataStorage.put("AccessSuccess_errorText", errorText);
            dataStorage.put("AccessSuccess_errorCode", errorCode);
            dataStorage.put("Progress_status", "Ошибка соединения с сервером");

            Log.e(TAG, "Ошибка авторизации [" + errorCode + "]: " + errorText);
        }
    }
}