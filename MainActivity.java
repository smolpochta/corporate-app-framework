package com.smolpochta.decanter;

//region Импорты

// Жизненный цикл и контекст
import android.content.Intent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;

// Элементы пользовательского интерфейса
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

// Анимации и графика
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

// Обработка жестов и касаний
import android.view.GestureDetector;
import android.view.MotionEvent;

// AndroidX
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

// Работа с файлами и сетью
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;

// Регулярные выражения
import java.util.regex.Pattern;
import java.util.regex.Matcher;

// Видеоплеер
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

// Работа с SVG
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

// Обработка JSON
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

// Установка обновлений
import androidx.core.content.FileProvider;
import android.net.Uri;

//endregion

public class MainActivity extends AppCompatActivity {

    //region Переменные

    // Системные менеджеры и основные зависимости
    private BackgroundTaskManager backgroundTaskManager;               // Менеджер фоновых заданий
    private static final String TAG = MainActivity.class.getSimpleName(); // Наименование активности
    private FileStorageManager fileStorage;                            // Менеджер файлового хранилища
    private SeanceDataStorage dataStorage;                             // Хранилище данных сеанса
    private VibrationManager vibrationManager;                         // Менеджер вибрации
    private Handler uiHandler;                                         // Обработчик UI потоков

    // Контейнеры основных зон интерфейса
    private ConstraintLayout menuZone;                                 // Зона меню (нижняя часть)
    private ConstraintLayout activeZone;                               // Активная зона (центральная часть)
    private ConstraintLayout headerZone;                               // Зона шапки (верхняя часть)

    // Варианты отображения зон (массивы)
    private ConstraintLayout[] menuOptions;                            // Варианты меню [menuOption1, menuOption2]
    private ConstraintLayout[] activeOptions;                          // Варианты активной зоны [activeOption1-5]
    private ConstraintLayout[] headerOptions;                          // Варианты шапки [headerOption1-2]

    // Варианты шапки (верхней зоны)
    private ConstraintLayout headerOption1;                            // Вариант 1: пустая шапка (загрузка)
    private ConstraintLayout headerOption2;                            // Вариант 2: рабочая шапка с информацией

    // Варианты активной зоны (центральной части)
    private ConstraintLayout activeOption1;                            // Вариант 1: заставка и заглушка
    private ConstraintLayout activeOption2;                            // Вариант 2: экран приветствия
    private ConstraintLayout activeOption3;                            // Вариант 3: меню "Магазин"
    private ConstraintLayout activeOption4;                            // Вариант 4: меню "Склад"
    private ConstraintLayout activeOption5;                            // Вариант 5: меню "Информация"

    // Варианты меню (нижней зоны)
    private ConstraintLayout menuOption1;                              // Вариант 1: информация о клиенте
    private ConstraintLayout menuOption2;                              // Вариант 2: основное меню с иконками

    // Элементы пользователя в шапке
    private ImageView userIcon;                                        // Иконка пользователя
    private ImageView decanterIcon;                                    // Иконка декатера (для жеста оттяжки)
    private TextView userText;                                         // Имя пользователя

    // Элементы погоды
    private ConstraintLayout weatherContainer;                         // Контейнер погоды (для обработки жестов)
    private ImageView weatherTop;                                      // Верхняя иконка погоды
    private ImageView weatherBottom;                                   // Нижняя иконка погоды
    private TextView weatherGrad;                                      // Текущая температура
    private TextView weatherNoData;                                    // Текст "нет данных" при отсутствии погоды

    // Элементы заставки
    private PlayerView playerView;                                     // Плеер для видео-заставки
    private ImageView animateImageView;                                // Изображение-заглушка если видео не работает
    private ExoPlayer player;                                          // Экземпляр видеоплеера

    // Элементы основного меню (menuOption2)
    private ConstraintLayout menuStore;                                // Контейнер пункта "Магазин"
    private ImageView menuStoreIcon;                                   // Иконка магазина
    private TextView menuStoreText;                                    // Текст "Магазин"

    // Пункт "Склад"
    private ConstraintLayout menuWarehouse;                            // Контейнер пункта "Склад"
    private ImageView menuWarehouseIcon;                               // Иконка склада
    private TextView menuWarehouseText;                                // Текст "Склад"

    // Пункт "Информация"
    private ConstraintLayout menuInfo;                                 // Контейнер пункта "Информация"
    private ImageView menuInfoIcon;                                    // Иконка информации
    private TextView menuInfoText;                                     // Текст "Информация"

    private View activeIndicator;                                      // Индикатор активного пункта меню

    // Прогресс и загрузка
    private ProgressBar progressBar;                                   // Полоса прогресса загрузки
    private TextView progressStatusTextView;                           // Текст статуса загрузки

    // Система сообщений и уведомлений
    private ConstraintLayout messageContainer;                         // Контейнер сообщений
    private TextView messageTextView;                                  // Текст сообщения/ошибки
    private LinearLayout messageCloseLayout;                           // Layout с таймером закрытия
    private TextView messageCloseText;                                 // Текст "Приложение закроется через"
    private TextView messageCloseSeconds;                              // Секунды до закрытия приложения

    // Вьюхи меню (группировка для удобства)
    private Interface.MainMenu.MenuViews menuViews;                    // Контейнер для всех View элементов меню

    // Управляющие менеджеры интерфейса
    private Interface.Messages messagesManager;                        // Менеджер сообщений и ошибок
    private Interface.Progress progressManager;                        // Менеджер прогресса загрузки
    private Interface.Splash splashManager;                            // Менеджер заставки и анимации
    private Interface.Styling stylingManager;                          // Менеджер стилей и оформления
    private Interface.Gestures gesturesManager;                        // Менеджер жестов и касаний
    private UIUpdater uiUpdater;                                       // Обновление интерфейса
    private InitializationProcessor initializationProcessor;           // Подготовка к работе

    /**
     * Реестр команд приложения - центральный управляющий элемент системы команд.
     * Использует паттерн синглтон, предоставляет доступ ко всем зарегистрированным командам.
     * Отвечает за:
     * - Регистрацию и хранение команд
     * - Управление жизненным циклом команд
     * - Координацию выполнения команд
     * - Применение стилей к элементам команд
     */
    private CommandSystem.CommandRegistry commandRegistry;

    /**
     * Контекст выполнения команд - содержит все необходимые зависимости для работы команд.
     * Передается каждой команде при выполнении, обеспечивая доступ к:
     * - Контексту приложения
     * - Хранилищу данных сеанса
     * - Менеджеру файлового хранилища
     * - Менеджеру фоновых задач
     * - Менеджеру вибрации
     * - Колбэку для взаимодействия с UI
     */
    private CommandSystem.CommandContext commandContext;

    /**
     * Главный колбэк для системы команд - мост между CommandSystem и MainActivity.
     * Реализует интерфейс CommandCallback, обеспечивая:
     * - Выполнение команд через реестр
     * - Обработку запросов вибрации
     * - Показ сообщений и ошибок
     * - Проверку разрешений на действие
     * <p>
     * Этот колбэк передается в CommandContext и используется всеми командами
     * для взаимодействия с пользовательским интерфейсом MainActivity.
     */
    private final CommandSystem.CommandCallback mainCommandCallback = new CommandSystem.CommandCallback() {
        @Override
        public void onCommandExecuted(String commandId) {
            // Делегируем выполнение команды реестру команд
            if (commandRegistry != null) {
                commandRegistry.executeCommand(commandId);
            }
        }

        @Override
        public void onVibrationRequested(String patternType) {
            // Обрабатываем запрос вибрации от команды
            uiUpdater.vibrate(patternType);
        }

        @Override
        public void onShowMessage(String message, Integer interval) {
            // Показываем информационное сообщение от команды
            if (messagesManager != null) {
                messagesManager.showMessage(message, interval);
            }
        }

        @Override
        public void onShowError(String error, Integer interval, Boolean appClose) {
            // Показываем сообщение об ошибке от команды
            if (messagesManager != null) {
                messagesManager.showError(error, interval, appClose);
            }
        }

        @Override
        public boolean isActionPermitted() {
            // Проверяем, разрешено ли выполнение действий в текущий момент
            // Используется командами для валидации перед началом выполнения
            return uiUpdater.ActionIsPermitted();
        }
    };

    //endregion

    /**
     * Обертка для управления различными фоновыми процедурами
     */
    public static class BackgroundFunction {
        private static final String TAG = "BackgroundFunction";

        // Имплементируем переменные
        private static volatile Context appContext;
        private static volatile FileStorageManager fileStorage;
        private static volatile SeanceDataStorage dataStorage;
        private static volatile BackgroundTaskManager backgroundTaskManager;

        /** Проверка инициализации компонентов
         */
        private static boolean isInitialized() {
            return appContext != null && fileStorage != null &&
                    dataStorage != null && backgroundTaskManager != null;
        }

        /**
         * Инициализация обертки
         */
        public static synchronized void initialize(Context context,
                                                   SeanceDataStorage storage,
                                                   FileStorageManager file,
                                                   BackgroundTaskManager manager) {

            if (isInitialized()) {
                Log.w(TAG, "Повторная инициализация, сброс состояния");
                cleanup();
            }

            // Валидация параметров
            if (file == null) {
                throw new IllegalArgumentException("FileStorageManager не может быть null");
            }
            if (storage == null) {
                throw new IllegalArgumentException("SeanceDataStorage не может быть null");
            }
            if (manager == null) {
                throw new IllegalArgumentException("BackgroundTaskManager не может быть null");
            }
            if (context == null) {
                throw new IllegalArgumentException("Context не может быть null");
            }

            appContext = context.getApplicationContext();
            dataStorage = storage;
            fileStorage = file;
            backgroundTaskManager = manager;

            Log.i(TAG, "DownloadFileTaskWrapper инициализирован");
        }

        /**
         * Запланировать загрузку файла в фоне (основной вызов)
         */
        public static Boolean scheduleFileDownload(String fromUrl, int storageType, String folder,
                                                   long expires, String fileName, String statusText,
                                                   int progressStart, int progressStop) {

            if (!isInitialized()) {
                Log.e(TAG, "Невозможно запланировать загрузку: обертка не инициализирована");
                return null;
            }

            if (fromUrl == null || fromUrl.isEmpty()) {
                Log.e(TAG, "URL источника пустой или null");
                return null;
            }

            if (folder == null) folder = "";
            if (fileName == null) fileName = "";

            try {
                // Установка параметров загрузки
                dataStorage.put("DownloadFile_start", 0L);
                dataStorage.put("DownloadFile_stop", 0L);
                dataStorage.put("DownloadFile_from", fromUrl);
                dataStorage.put("DownloadFile_to", storageType);
                dataStorage.put("DownloadFile_folder", folder);
                dataStorage.put("DownloadFile_expires", expires);
                dataStorage.put("DownloadFile_fileName", fileName);
                dataStorage.put("DownloadFile_targetPath", "");
                dataStorage.put("DownloadFile_totalSize", 0L);
                dataStorage.put("DownloadFile_downloadedSize", 0L);
                dataStorage.put("DownloadFile_status", statusText);
                dataStorage.put("DownloadFile_downloadProgressStart", progressStart);
                dataStorage.put("DownloadFile_downloadProgressStop", progressStop);
                dataStorage.put("DownloadFile_downloadProgress", 0);
                dataStorage.put("DownloadFile_speed", 0L);
                dataStorage.put("DownloadFile_elapsedTime", 0L);
                dataStorage.put("DownloadFile_remainingTime", 0L);
                dataStorage.put("DownloadFile_errorCode", 0);
                dataStorage.put("DownloadFile_errorText", "");

                // Добавление задачи в менеджер
                backgroundTaskManager.addBackgroundTask("downloadFileTask", null, 0);

                Log.i(TAG, "Загрузка запланирована");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Ошибка планирования загрузки", e);
                return null;
            }
        }

        /**
         * Регистрация фоновых функций
         */
        public static void registerBackgroundFunctions(BackgroundTaskManager TaskManager) {

            // Инициализация соединения на сервере
            TaskManager.registerFunction("checkServerDataTask", BackgroundFunction::ServerAuthorization);
            // Обновление данных о погоде
            TaskManager.registerFunction("updateWeatherTask", BackgroundFunction::Weather);
            // Загрузка данных об акциях
            TaskManager.registerFunction("downloadSlideTask", BackgroundFunction::DownloadSlideTask);
            // Удаление старых файлов из каталога приложения
            TaskManager.registerFunction("cleanupFileStorageTask", BackgroundFunction::CleanupFileStorageTask);
            // Загрузка данных о команде
            TaskManager.registerFunction("downloadTeamTask", BackgroundFunction::DownloadTeamTask);
            // Загрузка файла в фоне
            TaskManager.registerFunction("downloadFileTask", BackgroundFunction::DownloadFileTask);
        }

        /** Запуск различных функций
         */

        /**
         * Непосредственно сам запуск загрузки файла в фоне
         */
        public static void DownloadFileTask() {
            Log.d(TAG, "Запуск DownloadFileTask");

            if (!isInitialized()) {
                String error = "BackgroundFunction не инициализирован";
                Log.e(TAG, error);
                throw new IllegalStateException(error);
            }

            try {
                DownloadFileTask downloadFileTask = new DownloadFileTask(appContext, dataStorage, fileStorage);
                downloadFileTask.execute();
                Log.d(TAG, "DownloadFileTask выполнен");
            } catch (Exception e) {
                String errorMessage = "Ошибка выполнения DownloadFileTask: " + e.getMessage();
                Log.e(TAG, errorMessage, e);

                if (dataStorage != null) {
                    dataStorage.put("DownloadFile_errorCode", 999);
                    dataStorage.put("DownloadFile_errorText", errorMessage);
                    dataStorage.put("DownloadFile_stop", System.currentTimeMillis());
                }
            }
        }

        /**
         * Выполнение авторизации на сервере
         */
        public static void ServerAuthorization() {

            Log.d(TAG, "Запуск checkServerData.performAuthorization");

            if (!isInitialized()) {
                String error = "BackgroundFunction не инициализирован";
                Log.e(TAG, error);
                throw new IllegalStateException(error);
            }

            try {
                CheckServerData checkServerData = new CheckServerData(appContext, dataStorage, fileStorage);
                checkServerData.performAuthorization();
            } catch (Exception e) {
                String errorMessage = "Ошибка выполнения checkServerData.performAuthorization: " + e.getMessage();
                Log.e(TAG, errorMessage, e);

                if (dataStorage != null) {
                    dataStorage.put("AccessSuccess_errorCode", 999);
                    dataStorage.put("AccessSuccess_errorText", errorMessage);
                    dataStorage.put("AccessSuccess_stop", System.currentTimeMillis());
                }
            }
        }

        /** Запуск модуля "Акции"
         */
        public static void DownloadSlideTask() {

            Log.d(TAG, "Запуск DownloadSlideTask");

            if (!isInitialized()) {
                String error = "BackgroundFunction не инициализирован";
                Log.e(TAG, error);
                throw new IllegalStateException(error);
            }

            try {
                // Создаем экземпляр DownloadSlideTask из SlideShowActivity
                SlideShowActivity.DownloadSlideTask downloadSlideTask = new SlideShowActivity.DownloadSlideTask(appContext, dataStorage, fileStorage);
                downloadSlideTask.execute();

                // Загружаем звуковое сопровождение в фоне
                if (dataStorage.getBoolean("DownloadSlide_needShow")) {
                    scheduleFileDownload(
                            dataStorage.getString("DownloadSlide_soundUrl"),  // URL источника
                            fileStorage.STORAGE_WORKING,                            // Тип хранилища
                            "banners",                                              // Папка назначения
                            dataStorage.getLong("DownloadSlide_minExpires"),                                            // Срок хранения
                            dataStorage.getString("DownloadSlide_soundFile"),  // Имя файла
                            "мелодия",                                              // Текст статуса
                            70,                                                     // Начальный прогресс
                            100                                                     // Конечный прогресс
                    );
                }

                Log.d(TAG, "DownloadSlideTask выполнен");
            } catch (Exception e) {
                String errorMessage = "Ошибка выполнения DownloadSlideTask: " + e.getMessage();
                Log.e(TAG, errorMessage, e);

                if (dataStorage != null) {
                    dataStorage.put("DownloadSlide_errorCode"   , 999);
                    dataStorage.put("DownloadSlide_errorText"   , errorMessage);
                    dataStorage.put("DownloadSlide_stop"        , System.currentTimeMillis());
                }
            }
        }

        /** Запуск модуля "Команда"
         */
        public static void DownloadTeamTask() {

            Log.d(TAG, "Запуск DownloadTeamTask");

            if (!isInitialized()) {
                String error = "BackgroundFunction не инициализирован";
                Log.e(TAG, error);
                throw new IllegalStateException(error);
            }

            try {
                // Создаем экземпляр DownloadTeamTask из TeamActivity
                TeamShowActivity.DownloadTeamTask downloadTeamTask = new TeamShowActivity.DownloadTeamTask(appContext, dataStorage, fileStorage);
                downloadTeamTask.execute();

                // Загружаем звуковое сопровождение в фоне
                if (dataStorage.getBoolean("DownloadTeam_needShow")) {
                    scheduleFileDownload(
                            dataStorage.getString("DownloadTeam_soundUrl"),    // URL источника
                            fileStorage.STORAGE_WORKING,                            // Тип хранилища
                            "team",                                                 // Папка назначения
                            dataStorage.getLong("DownloadTeam_minExpires"),    // Срок хранения
                            dataStorage.getString("DownloadTeam_soundFile"),   // Имя файла
                            "мелодия",                                              // Текст статуса
                            70,                                                     // Начальный прогресс
                            100                                                     // Конечный прогресс
                    );
                }

                Log.d(TAG, "DownloadTeamTask выполнен");
            } catch (Exception e) {
                String errorMessage = "Ошибка выполнения DownloadTeamTask: " + e.getMessage();
                Log.e(TAG, errorMessage, e);

                if (dataStorage != null) {
                    dataStorage.put("DownloadTeam_errorCode", 999);
                    dataStorage.put("DownloadTeam_errorText", errorMessage);
                    dataStorage.put("DownloadTeam_stop", System.currentTimeMillis());
                }
            }
        }

        /**
         * Получение погоды
         */
        public static void Weather() {

            Log.d(TAG, "Запуск Weather");

            if (!isInitialized()) {
                String error = "BackgroundFunction не инициализирован";
                Log.e(TAG, error);
                throw new IllegalStateException(error);
            }

            try {
                WeatherModule weather = new WeatherModule(appContext, dataStorage, fileStorage);
                weather.execute();

                Log.d(TAG, "Weather выполнен");
            } catch (Exception e) {
                String errorMessage = "Ошибка выполнения Weather: " + e.getMessage();
                Log.e(TAG, errorMessage, e);

                if (dataStorage != null) {
                    dataStorage.put("Weather_errorCode", 999);
                    dataStorage.put("Weather_errorText", errorMessage);
                    dataStorage.put("Weather_stop", System.currentTimeMillis());
                }
            }
        }

        /**
         * Удаление старых файлов приложения
         */
        public static void CleanupFileStorageTask() {

            Log.d(TAG, "Запуск cleanupFileStorageTask");

            if (!isInitialized()) {
                String error = "BackgroundFunction не инициализирован";
                Log.e(TAG, error);
                throw new IllegalStateException(error);
            }

            try {
                JSONArray cleanupResult = fileStorage.actualData();

                Log.d(TAG, "cleanupFileStorageTask выполнен");
            } catch (Exception e) {
                String errorMessage = "Ошибка выполнения cleanupFileStorageTask: " + e.getMessage();
                Log.e(TAG, errorMessage, e);
            }
        }

        //

        /**
         * Очистка ресурсов
         */
        public static void cleanup() {
            Log.d(TAG, "Очистка ресурсов");

            if (backgroundTaskManager != null) {
                backgroundTaskManager.removeTaskByName("downloadFileTask");
            }

            fileStorage = null;
            dataStorage = null;
            backgroundTaskManager = null;
            appContext = null;

            Log.i(TAG, "Ресурсы очищены");
        }
    }

    /** Класс для управления пользовательским интерфейсом
     * Организует логику отображения, анимации и взаимодействия с элементами UI.
     * Разделен на вложенные классы по функциональным блокам.
     */
    public static class Interface {

        /**
         * Класс для управления отображением погодных данных.
         * Обрабатывает получение SVG-иконок из спрайта и их отображение в интерфейсе.
         */
        public static class Weather {
            private static final String TAG = "Weather";

            /**
             * Обновляет отображение погодных данных в шапке приложения.
             * Получает SVG-иконки из спрайта и температуру из хранилища данных.
             *
             * @param weatherBottom ImageView для нижней иконки погоды
             * @param weatherTop    ImageView для верхней иконки погоды
             * @param weatherGrad   TextView для отображения температуры
             * @param weatherNoData TextView для сообщения об отсутствии данных
             * @param dataStorage   хранилище данных сеанса
             * @param context       контекст приложения
             */
            public static void updateDisplay(ImageView weatherBottom, ImageView weatherTop,
                                             TextView weatherGrad, TextView weatherNoData,
                                             SeanceDataStorage dataStorage, Context context) {

                dataStorage.put("Weather_needShow", false); // Сбрасываем флаг необходимости обновления

                if (weatherBottom != null && weatherTop != null && weatherGrad != null &&
                        weatherNoData != null && dataStorage != null) {
                    try {
                        // Получаем данные из хранилища
                        String weatherTopId = dataStorage.getString("Weather_top");
                        String weatherBottomId = dataStorage.getString("Weather_bottom");
                        String temperature = dataStorage.getString("Weather_grad");
                        String svgSpritePath = dataStorage.getString("Weather_svg");

                        // Если температура пустая - показываем "нет данных"
                        if (temperature == null || temperature.isEmpty()) {
                            weatherGrad.setVisibility(View.GONE);
                            weatherTop.setVisibility(View.GONE);
                            weatherBottom.setVisibility(View.GONE);
                            weatherNoData.setVisibility(View.VISIBLE);
                            return;
                        }

                        // Есть данные - скрываем "нет данных"
                        weatherNoData.setVisibility(View.GONE);
                        weatherGrad.setVisibility(View.VISIBLE);
                        weatherGrad.setText(temperature);

                        // Получаем SVG для обеих иконок
                        JSONObject svgResult = getIconSvg(svgSpritePath, weatherTopId, weatherBottomId);
                        boolean hasTopSvg = svgResult.optBoolean("success1", false);
                        boolean hasBottomSvg = svgResult.optBoolean("success2", false);
                        String topSvgContent = svgResult.optString("svg1", "");
                        String bottomSvgContent = svgResult.optString("svg2", "");
                        int topWidth = svgResult.optInt("width1", 0);
                        int topHeight = svgResult.optInt("height1", 0);
                        int bottomWidth = svgResult.optInt("width2", 0);
                        int bottomHeight = svgResult.optInt("height2", 0);

                        // Логика отображения иконок в оригинальном размере
                        if (hasTopSvg && hasBottomSvg) {
                            // Обе иконки есть - показываем обе
                            weatherTop.setVisibility(View.VISIBLE);
                            weatherBottom.setVisibility(View.VISIBLE);

                            setImageViewSize(weatherTop, topWidth, topHeight, context);
                            setImageViewSize(weatherBottom, bottomWidth, bottomHeight, context);

                            displaySvgInImageView(weatherTop, topSvgContent);
                            displaySvgInImageView(weatherBottom, bottomSvgContent);

                        } else if (hasTopSvg) {
                            // Только верхняя иконка
                            weatherTop.setVisibility(View.VISIBLE);
                            weatherBottom.setVisibility(View.GONE);

                            setImageViewSize(weatherTop, topWidth, topHeight, context);
                            displaySvgInImageView(weatherTop, topSvgContent);

                        } else {
                            // Нет иконок
                            weatherTop.setVisibility(View.GONE);
                            weatherBottom.setVisibility(View.GONE);
                        }

                    } catch (Exception e) {
                        // В случае ошибки показываем только температуру
                        weatherGrad.setVisibility(View.GONE);
                        weatherTop.setVisibility(View.GONE);
                        weatherBottom.setVisibility(View.GONE);
                        weatherNoData.setVisibility(View.VISIBLE);
                    }
                }
            }

            /**
             * Устанавливает размеры ImageView на основе dp-значений.
             */
            private static void setImageViewSize(ImageView imageView, int widthDp, int heightDp, Context context) {
                if (widthDp > 0 && heightDp > 0) {
                    float density = context.getResources().getDisplayMetrics().density;
                    int widthPx = (int) (widthDp * density);
                    int heightPx = (int) (heightDp * density);

                    ViewGroup.LayoutParams params = imageView.getLayoutParams();
                    params.width = widthPx;
                    params.height = heightPx;
                    imageView.setLayoutParams(params);

                    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imageView.setAdjustViewBounds(true);
                    imageView.requestLayout();
                }
            }

            /**
             * Извлекает SVG-иконки из спрайта по их идентификаторам.
             *
             * @param weatherSvg путь к файлу SVG-спрайта
             * @param id1        идентификатор первой иконки
             * @param id2        идентификатор второй иконки
             * @return JSONObject с результатами извлечения
             */
            private static JSONObject getIconSvg(String weatherSvg, String id1, String id2) {
                JSONObject result = new JSONObject();

                try {
                    String svg1 = "";
                    String svg2 = "";
                    boolean success1 = false;
                    boolean success2 = false;
                    int width1 = 0, height1 = 0, width2 = 0, height2 = 0;

                    if (weatherSvg != null && !weatherSvg.isEmpty()) {
                        File file = new File(weatherSvg);
                        if (file.exists()) {
                            URL fileUrl = file.toURI().toURL();
                            InputStream inputStream = fileUrl.openStream();
                            BufferedInputStream bis = new BufferedInputStream(inputStream, 8192);
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int bytesRead;

                            while ((bytesRead = bis.read(buffer)) != -1) {
                                bos.write(buffer, 0, bytesRead);
                            }

                            String svgContent = bos.toString("UTF-8");
                            bis.close();
                            bos.close();

                            // Обработка первого ID
                            if (id1 != null && !id1.isEmpty()) {
                                String pattern1 = "<g id=\"" + id1 + "\"\\s+viewBox=\"([^\"]+)\"[^>]*>(.*?)</g>";
                                Pattern r1 = Pattern.compile(pattern1, Pattern.DOTALL);
                                Matcher m1 = r1.matcher(svgContent);

                                if (m1.find()) {
                                    String viewBox = m1.group(1);
                                    String gContent = m1.group(2);

                                    String[] viewBoxParts = viewBox.split(" ");
                                    if (viewBoxParts.length == 4) {
                                        width1 = (int) Float.parseFloat(viewBoxParts[2]);
                                        height1 = (int) Float.parseFloat(viewBoxParts[3]);

                                        svg1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
                                                "width=\"" + width1 + "\" height=\"" + height1 + "\" " +
                                                "viewBox=\"" + viewBox + "\">\n" +
                                                "<g>" + gContent + "</g>\n" +
                                                "</svg>";
                                        success1 = true;
                                    }
                                }
                            }

                            // Обработка второго ID
                            if (id2 != null && !id2.isEmpty()) {
                                String pattern2 = "<g id=\"" + id2 + "\"\\s+viewBox=\"([^\"]+)\"[^>]*>(.*?)</g>";
                                Pattern r2 = Pattern.compile(pattern2, Pattern.DOTALL);
                                Matcher m2 = r2.matcher(svgContent);

                                if (m2.find()) {
                                    String viewBox = m2.group(1);
                                    String gContent = m2.group(2);

                                    String[] viewBoxParts = viewBox.split(" ");
                                    if (viewBoxParts.length == 4) {
                                        width2 = (int) Float.parseFloat(viewBoxParts[2]);
                                        height2 = (int) Float.parseFloat(viewBoxParts[3]);

                                        svg2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
                                                "width=\"" + width2 + "\" height=\"" + height2 + "\" " +
                                                "viewBox=\"" + viewBox + "\">\n" +
                                                "<g>" + gContent + "</g>\n" +
                                                "</svg>";
                                        success2 = true;
                                    }
                                }
                            }
                        }
                    }

                    // Формируем результат
                    result.put("success", success1 || success2);
                    result.put("success1", success1);
                    result.put("success2", success2);
                    result.put("svg1", svg1 != null ? svg1 : "");
                    result.put("svg2", svg2 != null ? svg2 : "");
                    result.put("width1", width1);
                    result.put("height1", height1);
                    result.put("width2", width2);
                    result.put("height2", height2);

                } catch (Exception e) {
                    Log.e(TAG, "Ошибка чтения SVG файла: " + e.getMessage());
                    try {
                        result.put("success", false);
                        result.put("success1", false);
                        result.put("success2", false);
                        result.put("svg1", "");
                        result.put("svg2", "");
                        result.put("width1", 0);
                        result.put("height1", 0);
                        result.put("width2", 0);
                        result.put("height2", 0);
                    } catch (JSONException ex) {
                        Log.e(TAG, "JSON ошибка: " + ex.getMessage());
                    }
                }

                return result;
            }

            /**
             * Отображает SVG-контент в ImageView.
             */
            public static void displaySvgInImageView(ImageView imageView, String svgContent) {
                try {
                    if (svgContent != null && !svgContent.isEmpty()) {
                        SVG svg = SVG.getFromString(svgContent);

                        if (svg != null) {
                            imageView.setImageDrawable(new SvgDrawable(svg));
                            imageView.invalidate();
                            imageView.requestLayout();
                        }
                    } else {
                        imageView.setImageDrawable(null);
                    }
                } catch (SVGParseException e) {
                    Log.e(TAG, "Ошибка парсинга SVG", e);
                    imageView.setImageDrawable(null);
                }
            }

            /**
             * Кастомный Drawable для отображения SVG.
             */
            public static class SvgDrawable extends Drawable {
                private SVG svg;

                public SvgDrawable(SVG svg) {
                    this.svg = svg;
                }

                @Override
                public void draw(Canvas canvas) {
                    if (svg != null) {
                        Rect bounds = getBounds();
                        float scaleX = bounds.width() / svg.getDocumentWidth();
                        float scaleY = bounds.height() / svg.getDocumentHeight();
                        float scale = Math.min(scaleX, scaleY);

                        canvas.save();
                        float dx = bounds.left + (bounds.width() - svg.getDocumentWidth() * scale) / 2;
                        float dy = bounds.top + (bounds.height() - svg.getDocumentHeight() * scale) / 2;
                        canvas.translate(dx, dy);
                        canvas.scale(scale, scale);
                        svg.renderToCanvas(canvas);
                        canvas.restore();
                    }
                }

                @Override
                public void setAlpha(int alpha) {
                }

                @Override
                public void setColorFilter(ColorFilter colorFilter) {
                }

                @Override
                public int getOpacity() {
                    return PixelFormat.TRANSLUCENT;
                }
            }
        }

        /**
         * Класс для универсальных процедур анимации элементов интерфейса
         */
        public static class Animation {

            private static final String TAG = "Animation";

            /**
             * Анимация встряски элемента по горизонтали
             *
             * @param view      элемент для анимации
             * @param amplitude базовая амплитуда встряски
             * @param duration  базовая продолжительность в миллисекундах
             */
            public static void shakeHorizontal(View view, float amplitude, long duration) {
                if (view == null) {
                    return;
                }

                // Анимация встряски: быстрое колебание влево-вправо с затуханием
                view.animate()
                        .translationX(-amplitude)
                        .setDuration(duration)
                        .withEndAction(() -> view.animate()
                                .translationX(amplitude)
                                .setDuration(duration)
                                .withEndAction(() -> view.animate()
                                        .translationX(-amplitude * 0.8f)
                                        .setDuration((long) (duration * 0.8f))
                                        .withEndAction(() -> view.animate()
                                                .translationX(amplitude * 0.8f)
                                                .setDuration((long) (duration * 0.8f))
                                                .withEndAction(() -> view.animate()
                                                        .translationX(-amplitude * 0.5f)
                                                        .setDuration((long) (duration * 0.6f))
                                                        .withEndAction(() -> view.animate()
                                                                .translationX(amplitude * 0.5f)
                                                                .setDuration((long) (duration * 0.6f))
                                                                .withEndAction(() -> view.animate()
                                                                        .translationX(0f)
                                                                        .setDuration((long) (duration * 0.4f))
                                                                        .start())
                                                                .start())
                                                        .start())
                                                .start())
                                        .start())
                                .start())
                        .start();
            }

            /**
             * Плавное появление элемента с настраиваемыми параметрами
             *
             * @param view       элемент для анимации
             * @param duration   продолжительность анимации (null = 100ms)
             * @param startAlpha начальная прозрачность (null = текущее значение)
             * @param stopAlpha  конечная прозрачность (null = 1f)
             */
            public static void fadeIn(View view, Long duration, Float startAlpha, Float stopAlpha) {
                if (view == null) {
                    return;
                }

                view.setVisibility(View.VISIBLE);

                // Устанавливаем начальную прозрачность если указана
                if (startAlpha != null) {
                    view.setAlpha(startAlpha);
                }

                // Анимация плавного появления
                view.animate()
                        .alpha(stopAlpha != null ? stopAlpha : 1f)
                        .setDuration(duration != null ? duration : 100L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }

            /**
             * Плавное исчезновение элемента с настраиваемыми параметрами
             *
             * @param view       элемент для анимации
             * @param duration   продолжительность анимации (null = 100ms)
             * @param startAlpha начальная прозрачность (null = текущее значение)
             * @param stopAlpha  конечная прозрачность (null = 0f)
             */
            public static void fadeOut(View view, Long duration, Float startAlpha, Float stopAlpha) {
                if (view == null) {
                    return;
                }

                // Устанавливаем начальную прозрачность если указана
                if (startAlpha != null) {
                    view.setAlpha(startAlpha);
                }

                // Анимация плавного исчезновения
                view.animate()
                        .alpha(stopAlpha != null ? stopAlpha : 0f)
                        .setDuration(duration != null ? duration : 100L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                view.setVisibility(View.GONE);
                            }
                        }).start();

            }

        }

        /**
         * Класс для управления зонами интерфейса.
         * Обрабатывает анимации переключения между различными вариантами отображения зон.
         */
        public static class Zones {
            private static final String TAG = "Zones";

            /**
             * Выполняет анимацию переключения между вариантами трех основных зон интерфейса.
             * Координирует синхронное изменение шапки, активной области и меню.
             *
             * @param menuZone         контейнер меню
             * @param menuZoneAction   строка действия для меню в формате "(скрыть_вариант,длительность_скрытия,показать_вариант,длительность_показа)"
             * @param activeZone       контейнер активной области
             * @param activeZoneAction строка действия для активной области
             * @param headerZone       контейнер шапки
             * @param headerZoneAction строка действия для шапки
             * @param menuOptions      массив вариантов меню (ConstraintLayout[])
             * @param activeOptions    массив вариантов активной области (ConstraintLayout[])
             * @param headerOptions    массив вариантов шапки (ConstraintLayout[])
             */
            public static void animateZonesTransition(ConstraintLayout menuZone, String menuZoneAction,
                                                      ConstraintLayout activeZone, String activeZoneAction,
                                                      ConstraintLayout headerZone, String headerZoneAction,
                                                      ConstraintLayout[] menuOptions,
                                                      ConstraintLayout[] activeOptions,
                                                      ConstraintLayout[] headerOptions) {

                // Анимация переключения зоны меню
                if (menuZoneAction != null && !menuZoneAction.isEmpty()) {
                    animateZoneTransition(menuZone, menuZoneAction, menuOptions);
                }

                // Анимация активной зоны
                if (activeZoneAction != null && !activeZoneAction.isEmpty()) {
                    animateZoneTransition(activeZone, activeZoneAction, activeOptions);
                }

                // Анимация зоны шапки
                if (headerZoneAction != null && !headerZoneAction.isEmpty()) {
                    animateZoneTransition(headerZone, headerZoneAction, headerOptions);
                }
            }

            /**
             * Вспомогательный метод для анимации переключения между вариантами зоны.
             * Выполняет последовательное скрытие текущего варианта и отображение нового.
             */
            private static void animateZoneTransition(ConstraintLayout parentZone, String action, ConstraintLayout[] options) {
                // Парсим параметры
                int[] params = parseZoneParams(action);
                if (params == null) return;

                int hideOption = params[0];
                int hideDuration = params[1];
                int showOption = params[2];
                int showDuration = params[3];

                // Нечего переключать если скрывать и показывать один и тот же вариант
                if (hideOption == showOption) return;

                final ConstraintLayout hideView;
                final ConstraintLayout showView;

                // Определяем какие варианты скрывать и показывать
                if (hideOption > 0 && hideOption <= options.length) {
                    hideView = options[hideOption - 1];
                } else {
                    hideView = null;
                }

                if (showOption > 0 && showOption <= options.length) {
                    showView = options[showOption - 1];
                } else {
                    showView = null;
                }

                // Если есть что скрывать и этот вариант сейчас видим
                if (hideView != null && hideView.getVisibility() == View.VISIBLE) {
                    if (hideDuration > 0) {
                        // Анимированное скрытие
                        hideView.animate().alpha(0f).setDuration(hideDuration).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                hideView.setVisibility(View.GONE);
                                showNewView(showView, showDuration);
                            }
                        }).start();
                    } else {
                        // Мгновенное скрытие
                        hideView.setVisibility(View.GONE);
                        hideView.setAlpha(0f);
                        showNewView(showView, showDuration);
                    }
                } else {
                    // Если нечего скрывать, просто показываем новый вариант
                    showNewView(showView, showDuration);
                }
            }

            /**
             * Парсинг параметров для зон с вариантами.
             * Ожидает строку в формате "(число,число,число,число)".
             *
             * @param action строка с параметрами
             * @return массив из четырех чисел или null при ошибке
             */
            private static int[] parseZoneParams(String action) {
                try {
                    // Удаляем скобки если есть и разбиваем по запятым
                    String cleanAction = action.replaceAll("[()]", "");
                    String[] parts = cleanAction.split(",");

                    if (parts.length == 4) {
                        int hideOption = Integer.parseInt(parts[0].trim());
                        int hideDuration = Integer.parseInt(parts[1].trim());
                        int showOption = Integer.parseInt(parts[2].trim());
                        int showDuration = Integer.parseInt(parts[3].trim());

                        return new int[]{hideOption, hideDuration, showOption, showDuration};
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка парсинга параметров зоны: " + action, e);
                }
                return null;
            }

            /**
             * Показать новый вариант зоны с анимацией или без.
             * Устанавливает начальную прозрачность 0 и плавно повышает до 1.
             */
            private static void showNewView(ConstraintLayout showView, int showDuration) {
                if (showView != null && showView.getVisibility() != View.VISIBLE) {
                    showView.setAlpha(0f);
                    showView.setVisibility(View.VISIBLE);

                    if (showDuration > 0) {
                        showView.animate().alpha(1f).setDuration(showDuration).start();
                    } else {
                        showView.setAlpha(1f);
                    }
                }
            }

            /**
             * Переключение активной зоны.
             * Определяет текущую зону и переключает на указанную с нулевой длительностью анимации.
             *
             * @param activeZone    контейнер активной зоны
             * @param activeOptions массив вариантов активной зоны
             * @param zoneNumber    номер новой зоны (1-5)
             */
            public static void switchActiveZone(ConstraintLayout activeZone, ConstraintLayout[] activeOptions, int zoneNumber) {
                int currentZone = getCurrentActiveZone(activeOptions);
                // Если уже показываем эту зону - ничего не делаем
                if (currentZone == zoneNumber) {
                    return;
                }
                animateZoneTransition(activeZone, "(" + currentZone + ",0," + zoneNumber + ",0)", activeOptions);
            }

            /**
             * Определяет текущий отображаемый вариант активной зоны.
             *
             * @param activeOptions массив вариантов активной зоны
             * @return номер видимого варианта (1-5) или 1 если ни один не виден
             */
            public static int getCurrentActiveZone(ConstraintLayout[] activeOptions) {
                for (int i = 0; i < activeOptions.length; i++) {
                    if (activeOptions[i] != null && activeOptions[i].getVisibility() == View.VISIBLE) {
                        return i + 1;
                    }
                }
                return 1;
            }
        }

        /**
         * Класс для управления главным меню (нижнее меню с иконками)
         * Обрабатывает переключение, анимацию и визуальное выделение пунктов меню
         */
        public static class MainMenu {
            private static final String TAG = "MainMenu";

            /**
             * Переключает меню с анимацией
             *
             * @param menuId           ID нового меню (menuStore, menuWarehouse, menuInfo)
             * @param activeZoneLayout Layout активной зоны
             * @param activeOptions    массив вариантов активной зоны
             * @param dataStorage      хранилище данных сеанса
             * @param activeIndicator  индикатор активного пункта
             * @param menuViews        карта View элементов меню
             */
            public static void switchMenu(String menuId, ConstraintLayout activeZoneLayout,
                                          ConstraintLayout[] activeOptions, SeanceDataStorage dataStorage,
                                          View activeIndicator, MenuViews menuViews) {

                String currentMenu = dataStorage.getString("Menu");
                // Если уже выбран этот пункт - ничего не делаем
                if (menuId.equals(currentMenu)) {
                    return;
                }

                // Обновляем хранилище
                dataStorage.put("Menu", menuId);

                // Анимация переключения
                animateMenuSwitch(menuId, activeZoneLayout, activeOptions,
                        activeIndicator, menuViews);
            }

            /**
             * Анимация переключения меню
             */
            private static void animateMenuSwitch(String newMenuId, ConstraintLayout activeZoneLayout,
                                                  ConstraintLayout[] activeOptions, View activeIndicator,
                                                  MenuViews menuViews) {

                // Определяем номер зоны на основе ID меню
                int zoneNumber = getZoneNumberForMenu(newMenuId);

                // Анимация выделения нового элемента
                animateMenuSelect(newMenuId, activeIndicator, menuViews);

                // Переключение активной зоны
                Interface.Zones.switchActiveZone(activeZoneLayout, activeOptions, zoneNumber);
            }

            /**
             * Определяет номер активной зоны на основе ID меню
             */
            private static int getZoneNumberForMenu(String menuId) {
                switch (menuId) {
                    case "menuStore":
                        return 3;
                    case "menuWarehouse":
                        return 4;
                    case "menuInfo":
                        return 5;
                    default:
                        return 3; // По умолчанию
                }
            }

            /**
             * Анимация выделения меню
             */
            private static void animateMenuSelect(String menuId, View activeIndicator,
                                                  MenuViews menuViews) {

                View targetView = getMenuViewById(menuId, menuViews);
                ImageView targetIcon = getMenuIconById(menuId, menuViews);
                TextView targetText = getMenuTextById(menuId, menuViews);

                if (targetView == null || targetIcon == null || targetText == null) return;

                // Позиционируем индикатор
                activeIndicator.setX(targetView.getX() + (targetView.getWidth() - activeIndicator.getWidth()) / 2);
                activeIndicator.setVisibility(View.VISIBLE);

                // Анимация появления индикатора
                Interface.Animation.fadeIn(activeIndicator, 200L, 0f, 1f);

                // Анимация зума иконки
                targetIcon.animate()
                        .scaleX(1.2f)
                        .scaleY(1.2f)
                        .setDuration(100)
                        .withEndAction(() -> targetIcon.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(100)
                                .start())
                        .start();
            }

            /**
             * Получить контейнер элемента меню по ID
             */
            private static View getMenuViewById(String menuId, MenuViews menuViews) {
                switch (menuId) {
                    case "menuStore":
                        return menuViews.menuStore;
                    case "menuWarehouse":
                        return menuViews.menuWarehouse;
                    case "menuInfo":
                        return menuViews.menuInfo;
                    default:
                        return null;
                }
            }

            /**
             * Получить иконку меню по ID
             */
            private static ImageView getMenuIconById(String menuId, MenuViews menuViews) {
                switch (menuId) {
                    case "menuStore":
                        return menuViews.menuStoreIcon;
                    case "menuWarehouse":
                        return menuViews.menuWarehouseIcon;
                    case "menuInfo":
                        return menuViews.menuInfoIcon;
                    default:
                        return null;
                }
            }

            /**
             * Получить текст меню по ID
             */
            private static TextView getMenuTextById(String menuId, MenuViews menuViews) {
                switch (menuId) {
                    case "menuStore":
                        return menuViews.menuStoreText;
                    case "menuWarehouse":
                        return menuViews.menuWarehouseText;
                    case "menuInfo":
                        return menuViews.menuInfoText;
                    default:
                        return null;
                }
            }

            /**
             * Получает текущий элемент подменю по идентификатору из dataStorage
             *
             * @param dataStorage хранилище данных
             * @param menuViews   карта View элементов меню
             * @return View элемента подменю или null если не найден
             */
            public static View getCurrentMenuView(SeanceDataStorage dataStorage, MenuViews menuViews) {
                String menuId = dataStorage.getString("Menu");
                return getMenuViewById(menuId, menuViews);
            }

            /**
             * Позиционирует активный индикатор на указанном элементе меню
             * с проверкой готовности размеров элемента
             *
             * @param dataStorage     хранилище данных
             * @param activeIndicator индикатор активного пункта
             * @param menuViews       карта View элементов меню
             */
            public static void positionActiveIndicatorOnView(SeanceDataStorage dataStorage,
                                                             View activeIndicator, MenuViews menuViews) {

                View targetView = getCurrentMenuView(dataStorage, menuViews);

                if (targetView == null || activeIndicator == null) return;

                targetView.post(new Runnable() {
                    @Override
                    public void run() {
                        // Проверяем, что targetView имеет правильные размеры
                        if (targetView.getWidth() > 0 && targetView.getHeight() > 0) {
                            // Позиционируем индикатор по центру элемента
                            activeIndicator.setX(targetView.getX() +
                                    (targetView.getWidth() - activeIndicator.getWidth()) / 2);
                            activeIndicator.setVisibility(View.VISIBLE);
                            activeIndicator.setAlpha(1f);
                        } else {
                            // Если размеры еще не готовы, повторяем через небольшой интервал
                            targetView.postDelayed(this, 50);
                        }
                    }
                });
            }

            /**
             * Настройка обработчиков клика для меню
             *
             * @param menuViews карта View элементов меню
             * @param listener  обработчик клика на меню
             */
            public static void setupMenuClickListeners(MenuViews menuViews,
                                                       OnMenuItemClickListener listener) {

                if (menuViews.menuStore != null) {
                    menuViews.menuStore.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onMenuItemClick("menuStore");
                        }
                    });
                }

                if (menuViews.menuWarehouse != null) {
                    menuViews.menuWarehouse.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onMenuItemClick("menuWarehouse");
                        }
                    });
                }

                if (menuViews.menuInfo != null) {
                    menuViews.menuInfo.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onMenuItemClick("menuInfo");
                        }
                    });
                }
            }

            /**
             * Интерфейс для обработки кликов по пунктам меню
             */
            public interface OnMenuItemClickListener {
                void onMenuItemClick(String menuId);
            }

            /**
             * Контейнер для хранения всех View элементов меню
             */
            public static class MenuViews {
                public ConstraintLayout menuStore;
                public ConstraintLayout menuWarehouse;
                public ConstraintLayout menuInfo;

                public ImageView menuStoreIcon;
                public ImageView menuWarehouseIcon;
                public ImageView menuInfoIcon;

                public TextView menuStoreText;
                public TextView menuWarehouseText;
                public TextView menuInfoText;

                public MenuViews() {
                }

                public MenuViews(ConstraintLayout menuStore, ConstraintLayout menuWarehouse,
                                 ConstraintLayout menuInfo, ImageView menuStoreIcon,
                                 ImageView menuWarehouseIcon, ImageView menuInfoIcon,
                                 TextView menuStoreText, TextView menuWarehouseText,
                                 TextView menuInfoText) {
                    this.menuStore = menuStore;
                    this.menuWarehouse = menuWarehouse;
                    this.menuInfo = menuInfo;
                    this.menuStoreIcon = menuStoreIcon;
                    this.menuWarehouseIcon = menuWarehouseIcon;
                    this.menuInfoIcon = menuInfoIcon;
                    this.menuStoreText = menuStoreText;
                    this.menuWarehouseText = menuWarehouseText;
                    this.menuInfoText = menuInfoText;
                }
            }
        }

        /**
         * Управление сообщениями
         */
        public static class Messages {
            private static final String TAG = "Messages";

            private MainActivity activity;
            private SeanceDataStorage dataStorage;
            private ConstraintLayout messageContainer;
            private TextView messageTextView;
            private LinearLayout messageCloseLayout;
            private TextView messageCloseText;
            private TextView messageCloseSeconds;

            // Состояние
            private ValueAnimator currentAnimator;
            private boolean isVisible = false;
            private boolean isAnimating = false;
            private long lastVisibilityChangeTime = 0;
            private static final long MIN_VISIBILITY_TIME = 300; // Минимальное время показа сообщения

            public Messages(MainActivity activity, ConstraintLayout messageContainer,
                            TextView messageTextView, LinearLayout messageCloseLayout,
                            TextView messageCloseText, TextView messageCloseSeconds,
                            SeanceDataStorage dataStorage) {
                this.activity = activity;
                this.messageContainer = messageContainer;
                this.messageTextView = messageTextView;
                this.messageCloseLayout = messageCloseLayout;
                this.messageCloseText = messageCloseText;
                this.messageCloseSeconds = messageCloseSeconds;
                this.dataStorage = dataStorage;

                // Инициализируем невидимым
                if (messageContainer != null) {
                    messageContainer.setVisibility(View.GONE);
                    messageContainer.setAlpha(0f);
                    messageContainer.setScaleX(1f);
                    messageContainer.setScaleY(1f);
                }
            }

            /**
             * Показать информационное сообщение
             */
            public void showMessage(String message, Integer interval) {
                long intervalMs = (interval != null ? interval : dataStorage.getInt("MessageTextInterval")) * 1000L;
                long closeTime = System.currentTimeMillis() + intervalMs;

                dataStorage.put("MessageText", message);
                dataStorage.put("MessageCloseIn", closeTime);
                dataStorage.put("ErrorText", "");

                // Принудительное обновление
                forceUpdateMessageDisplay();
            }

            /**
             * Показать сообщение об ошибке с возможностью закрытия приложения
             */
            public void showError(String error, Integer interval, Boolean appClose) {
                long intervalMs = (interval != null ? interval : dataStorage.getInt("ErrorTextInterval")) * 1000L;
                long closeTime = System.currentTimeMillis() + intervalMs;

                dataStorage.put("ErrorText", error);

                if (appClose != null && appClose) {
                    dataStorage.put("AppCloseIn", closeTime);
                } else {
                    dataStorage.put("MessageCloseIn", closeTime);
                }

                dataStorage.put("MessageText", "");
                // Принудительное обновление
                forceUpdateMessageDisplay();
            }

            /**
             * Обновить отображение сообщений и таймера закрытия
             */
            public void update() {
                updateAppCloseTimer();
                updateMessageDisplay();
            }

            /**
             * Принудительное обновление сообщений (без защиты от частых вызовов)
             */
            private void forceUpdateMessageDisplay() {
                updateAppCloseTimer();
                updateMessageDisplayInternal();
            }

            /**
             * Обновить таймер закрытия приложения
             */
            private void updateAppCloseTimer() {
                long appCloseIn = dataStorage.getLong("AppCloseIn");

                if (appCloseIn > 0) {
                    long secondsRest = (appCloseIn - System.currentTimeMillis()) / 1000;
                    if (secondsRest > 0) {
                        if (messageCloseSeconds != null) {
                            messageCloseSeconds.setText(String.valueOf(secondsRest));
                            messageCloseLayout.setVisibility(View.VISIBLE);
                        }
                    } else {
                        messageCloseLayout.setVisibility(View.GONE);
                        activity.closeAppCompletely();
                    }
                } else {
                    if (messageCloseLayout != null) {
                        messageCloseLayout.setVisibility(View.GONE);
                    }
                }
            }

            /**
             * Обновить отображение сообщений
             */
            private void updateMessageDisplay() {
                // Проверяем, не слишком ли часто обновляем
                if (System.currentTimeMillis() - lastVisibilityChangeTime < MIN_VISIBILITY_TIME) {
                    return;
                }

                updateMessageDisplayInternal();
            }

            /**
             * Внутреннее обновление отображения сообщений
             */
            private void updateMessageDisplayInternal() {
                String errorText = dataStorage.getString("ErrorText");
                String messageText = dataStorage.getString("MessageText");
                long messageCloseIn = dataStorage.getLong("MessageCloseIn");
                long currentTime = System.currentTimeMillis();

                // Определяем, должно ли сообщение быть видимым
                boolean hasMessage = !errorText.isEmpty() || !messageText.isEmpty();
                boolean isInTime = messageCloseIn == 0L || messageCloseIn > currentTime;
                boolean shouldBeVisible = hasMessage && isInTime;

                // Если состояние не изменилось - ничего не делаем
                if (shouldBeVisible == isVisible && !isAnimating) {
                    // Просто обновляем текст если нужно
                    if (shouldBeVisible && messageTextView != null) {
                        String displayText = !errorText.isEmpty() ? errorText : messageText;
                        messageTextView.setText(displayText);
                    }
                    return;
                }

                lastVisibilityChangeTime = currentTime;

                if (shouldBeVisible) {
                    // Показываем сообщение
                    String displayText = !errorText.isEmpty() ? errorText : messageText;
                    boolean isError = !errorText.isEmpty();
                    showMessageInternal(displayText, isError);
                } else {
                    // Скрываем сообщение
                    hideMessageInternal();
                }
            }

            /**
             * Внутренний метод показа сообщения
             */
            private void showMessageInternal(String text, boolean isError) {
                if (messageContainer == null || isAnimating) return;

                // Отменяем текущую анимацию
                cancelCurrentAnimation();

                isAnimating = true;

                // Устанавливаем текст
                messageTextView.setText(text);

                // Определяем цвет фона
                int backgroundColor = Color.parseColor(dataStorage.getString(
                        isError ? "default.ErrorBackgroundColor" : "default.MessageBackgroundColor"));

                // Создаем и устанавливаем фон
                Drawable dissolveDrawable = createMessageBackgroundDrawable(backgroundColor);
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                    messageContainer.setBackgroundDrawable(dissolveDrawable);
                } else {
                    messageContainer.setBackground(dissolveDrawable);
                }

                // Если сообщение уже видимо, просто обновляем без анимации
                if (messageContainer.getVisibility() == View.VISIBLE) {
                    messageContainer.setAlpha(1f);
                    messageContainer.setScaleX(1f);
                    messageContainer.setScaleY(1f);
                    isVisible = true;
                    isAnimating = false;
                    return;
                }

                // Начальное состояние для анимации
                messageContainer.setVisibility(View.VISIBLE);
                messageContainer.setAlpha(0f);
                messageContainer.setScaleX(0.8f);
                messageContainer.setScaleY(0.8f);

                // Анимация появления
                animateMessage(true);
            }

            /**
             * Внутренний метод скрытия сообщения
             */
            private void hideMessageInternal() {
                if (messageContainer == null ||
                        messageContainer.getVisibility() != View.VISIBLE ||
                        isAnimating) {
                    isVisible = false;
                    return;
                }

                // Отменяем текущую анимацию
                cancelCurrentAnimation();

                isAnimating = true;

                // Анимация исчезновения
                animateMessage(false);
            }

            /**
             * Анимация появления/исчезновения сообщения
             */
            private void animateMessage(final boolean show) {
                if (messageContainer == null) {
                    isAnimating = false;
                    return;
                }

                // Определяем параметры анимации
                float startAlpha = show ? 0f : 1f;
                float endAlpha = show ? 1f : 0f;
                float startScale = show ? 0.8f : 1f;
                float endScale = show ? 1f : 0.8f;

                long duration = show ? 150L : 100L;

                // Создаем аниматор
                currentAnimator = ValueAnimator.ofFloat(0f, 1f);
                currentAnimator.setDuration(duration);
                currentAnimator.setInterpolator(show ?
                        new OvershootInterpolator(1.0f) : new AccelerateInterpolator());

                currentAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        if (messageContainer == null) return;

                        float progress = (float) animation.getAnimatedValue();
                        float alpha, scale;

                        if (show) {
                            // Появление: от 0 к 1
                            alpha = startAlpha + (endAlpha - startAlpha) * progress;
                            scale = startScale + (endScale - startScale) * progress;
                        } else {
                            // Исчезновение: от 1 к 0
                            alpha = startAlpha - (startAlpha - endAlpha) * progress;
                            scale = startScale - (startScale - endScale) * progress;
                        }

                        messageContainer.setAlpha(alpha);
                        messageContainer.setScaleX(scale);
                        messageContainer.setScaleY(scale);
                    }
                });

                currentAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        isAnimating = false;

                        if (!show) {
                            // После исчезновения скрываем контейнер
                            if (messageContainer != null) {
                                messageContainer.setVisibility(View.GONE);
                                messageContainer.setAlpha(0f);
                                messageContainer.setScaleX(1f);
                                messageContainer.setScaleY(1f);
                            }
                            isVisible = false;
                        } else {
                            isVisible = true;
                        }

                        currentAnimator = null;
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        isAnimating = false;
                        currentAnimator = null;

                        // При отмене анимации скрытия, оставляем сообщение видимым
                        if (show && messageContainer != null) {
                            messageContainer.setAlpha(1f);
                            messageContainer.setScaleX(1f);
                            messageContainer.setScaleY(1f);
                            isVisible = true;
                        }
                    }
                });

                currentAnimator.start();
            }

            /**
             * Отменить текущую анимацию
             */
            private void cancelCurrentAnimation() {
                if (currentAnimator != null && currentAnimator.isRunning()) {
                    currentAnimator.cancel();
                }
                currentAnimator = null;
                isAnimating = false;
            }

            /**
             * Создание фона для сообщений с размытыми краями
             */
            private Drawable createMessageBackgroundDrawable(int backgroundColor) {
                return new Drawable() {
                    private Paint paint;
                    private float density = activity.getResources().getDisplayMetrics().density;

                    {
                        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    }

                    @Override
                    public void draw(Canvas canvas) {
                        Rect bounds = getBounds();
                        int width = bounds.width();
                        int height = bounds.height();

                        int[] colors = {
                                Color.argb(0, Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor)),
                                backgroundColor,
                                backgroundColor,
                                Color.argb(0, Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor))
                        };

                        LinearGradient gradient = new LinearGradient(
                                0, 0, width, 0,
                                colors,
                                new float[]{0f, 0.1f, 0.9f, 1f},
                                Shader.TileMode.CLAMP
                        );

                        paint.setShader(gradient);
                        canvas.drawRect(0, 0, width, height, paint);
                    }

                    @Override
                    public void setAlpha(int alpha) {
                        paint.setAlpha(alpha);
                    }

                    @Override
                    public void setColorFilter(ColorFilter colorFilter) {
                        paint.setColorFilter(colorFilter);
                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                };
            }

            /**
             * Проверить, активно ли сообщение в данный момент
             */
            public boolean isMessageActive() {
                String errorText = dataStorage.getString("ErrorText");
                String messageText = dataStorage.getString("MessageText");
                long messageCloseIn = dataStorage.getLong("MessageCloseIn");
                long currentTime = System.currentTimeMillis();

                return ((!errorText.isEmpty() || !messageText.isEmpty()) &&
                        (messageCloseIn == 0L || messageCloseIn > currentTime));
            }

            /**
             * Немедленно скрыть сообщение (без анимации)
             */
            public void hideImmediately() {
                cancelCurrentAnimation();

                if (messageContainer != null) {
                    messageContainer.setVisibility(View.GONE);
                    messageContainer.setAlpha(0f);
                    messageContainer.setScaleX(1f);
                    messageContainer.setScaleY(1f);
                }

                isVisible = false;
                isAnimating = false;

                // Очищаем тексты в хранилище
                dataStorage.put("MessageText", "");
                dataStorage.put("ErrorText", "");
                dataStorage.put("MessageCloseIn", 0L);
            }

            /**
             * Очистка ресурсов
             */
            public void cleanup() {
                cancelCurrentAnimation();

                if (messageContainer != null) {
                    messageContainer.setVisibility(View.GONE);
                    messageContainer.setAlpha(0f);
                    messageContainer.setScaleX(1f);
                    messageContainer.setScaleY(1f);
                }

                isVisible = false;
                isAnimating = false;
            }
        }

        /**
         * Класс для управления прогрессом загрузки
         * Обрабатывает отображение прогресс-бара, статуса и связанных анимаций
         */
        public static class Progress {
            private static final String TAG = "Progress";

            private MainActivity activity;
            private SeanceDataStorage dataStorage;
            private ProgressBar progressBar;
            private TextView progressStatusTextView;

            private int lastProgress = -1;
            private String lastProgressStatus = "Загрузка...";
            private boolean isVisible = false;

            public Progress(MainActivity activity, ProgressBar progressBar,
                            TextView progressStatusTextView, SeanceDataStorage dataStorage) {
                this.activity = activity;
                this.progressBar = progressBar;
                this.progressStatusTextView = progressStatusTextView;
                this.dataStorage = dataStorage;

                // Инициализация невидимым
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                    progressBar.setAlpha(0f);
                }
                if (progressStatusTextView != null) {
                    progressStatusTextView.setVisibility(View.GONE);
                    progressStatusTextView.setAlpha(0f);
                }
            }

            /**
             * Обновление отображения прогресс-бара и статуса
             */
            public void update() {
                boolean progressVisible = dataStorage.getBoolean("Progress_visible");
                int progress = dataStorage.getInt("Progress");
                String progressStatus = dataStorage.getString("Progress_status").toLowerCase();

                // Обновление значений прогресса
                updateProgressValue(progress);
                updateStatusText(progressStatus);

                // Управление видимостью
                manageVisibility(progressVisible);
            }

            /**
             * Обновление значения прогресса с анимацией
             */
            private void updateProgressValue(int progress) {
                if (progressBar != null && progress != lastProgress) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        progressBar.setProgress(progress, true);
                    } else {
                        progressBar.setProgress(progress);
                    }
                    lastProgress = progress;
                }
            }

            /**
             * Обновление текста статуса
             */
            private void updateStatusText(String status) {
                if (progressStatusTextView != null && !status.equals(lastProgressStatus)) {
                    progressStatusTextView.setText(status);
                    lastProgressStatus = status;
                }
            }

            /**
             * Управление видимостью прогресс-бар
             */
            private void manageVisibility(boolean shouldBeVisible) {
                if (progressBar == null || isVisible == shouldBeVisible) return;

                if (shouldBeVisible) {
                    showProgress();
                } else {
                    hideProgress();
                }
                isVisible = shouldBeVisible;
            }

            /**
             * Показать прогресс с анимацией
             */
            private void showProgress() {
                if (progressBar.getVisibility() != View.VISIBLE) {
                    Interface.Animation.fadeIn(progressBar, 100L, 0f, 0.8f);
                    Interface.Animation.fadeIn(progressStatusTextView, 100L, 0f, 0.8f);
                }
            }

            /**
             * Скрыть прогресс с анимацией
             */
            private void hideProgress() {
                if (progressBar.getVisibility() == View.VISIBLE) {
                    Interface.Animation.fadeOut(progressBar, 400L, null, 0f);
                    Interface.Animation.fadeOut(progressStatusTextView, 400L, null, 0f);
                }
            }

            /**
             * Обновить прогресс напрямую с контролем видимости
             */
            public void setProgress(int progress, boolean visible, String status) {
                dataStorage.put("Progress", progress);
                dataStorage.put("Progress_visible", visible);
                dataStorage.put("Progress_status", status);
                update();
            }

            /**
             * Получить текущее значение прогресса
             */
            public int getCurrentProgress() {
                return dataStorage.getInt("Progress");
            }

            /**
             * Проверить, виден ли прогресс
             */
            public boolean isProgressVisible() {
                return isVisible;
            }

            /**
             * Немедленно скрыть прогресс (без анимации)
             */
            public void hideImmediately() {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                    progressBar.setAlpha(0f);
                }
                if (progressStatusTextView != null) {
                    progressStatusTextView.setVisibility(View.GONE);
                    progressStatusTextView.setAlpha(0f);
                }
                isVisible = false;
                dataStorage.put("Progress", 0);
            }

            /**
             * Очистка ресурсов
             */
            public void cleanup() {
                hideImmediately();
                progressBar = null;
                progressStatusTextView = null;
            }
        }

        /**
         * Класс для управления заставкой приложения.
         * Обрабатывает видео-заставку и fallback на изображение-заглушку.
         */
        public static class Splash {
            private static final String TAG = "Splash";

            private final MainActivity activity;
            private final SeanceDataStorage dataStorage;
            private ExoPlayer player;
            private PlayerView playerView;
            private ImageView fallbackImageView;
            private boolean isFirstFrameRendered = false;

            // Константы для статусов заставки
            public static final int STATUS_NOT_STARTED = 0;
            public static final int STATUS_PLAYING = 1;
            public static final int STATUS_COMPLETED = 2;
            public static final int STATUS_FALLBACK = -1;

            public Splash(MainActivity activity, PlayerView playerView,
                          ImageView fallbackImageView, SeanceDataStorage dataStorage) {
                this.activity = activity;
                this.playerView = playerView;
                this.fallbackImageView = fallbackImageView;
                this.dataStorage = dataStorage;
            }

            /**
             * Инициализация и запуск заставки
             */
            public void initialize() {
                // Скрываем элементы по умолчанию
                if (playerView != null) {
                    playerView.setAlpha(0f);
                }
                if (fallbackImageView != null) {
                    fallbackImageView.setVisibility(View.GONE);
                }

                // Определяем тип заставки на основе настроек
                String videoPath = dataStorage.getString("Animate_mp4");

                // Пытаемся запустить видео
                if (videoPath != null && !videoPath.isEmpty()) {
                    setupVideoPlayer(videoPath);
                } else {
                    // Нет видео - сразу переходим к заглушке
                    dataStorage.put("Animate_status", STATUS_FALLBACK);
                    showFallbackImage();
                }
            }

            /**
             * Настройка видеоплеера для заставки
             */
            private void setupVideoPlayer(String videoPath) {
                try {
                    player = new ExoPlayer.Builder(activity).build();
                    if (playerView != null) {
                        playerView.setPlayer(player);
                        playerView.setUseController(false);
                        playerView.setBackgroundColor(Color.BLACK);
                    }

                    // Настройка обработчиков событий
                    player.addListener(createPlayerListener());

                    // Загрузка и подготовка видео
                    MediaItem mediaItem = MediaItem.fromUri("asset:///" + videoPath);
                    player.setMediaItem(mediaItem);
                    player.prepare();

                    // Восстановление позиции если было сохранено
                    long savedPosition = dataStorage.getLong("Animate_cur");
                    player.seekTo(Math.max(savedPosition, 1000));

                    // Запуск воспроизведения без звука
                    player.setPlayWhenReady(true);
                    player.setVolume(0f);

                    dataStorage.put("Animate_status", STATUS_PLAYING);

                } catch (Exception e) {
                    Log.e(TAG, "Ошибка инициализации видео, переключаемся на заглушку", e);
                    handleVideoError();
                }
            }

            /**
             * Создание обработчика событий видеоплеера
             */
            private Player.Listener createPlayerListener() {
                return new Player.Listener() {
                    @Override
                    public void onRenderedFirstFrame() {
                        if (!isFirstFrameRendered) {
                            isFirstFrameRendered = true;

                            // Плавное появление видео
                            if (playerView != null) {
                                Interface.Animation.fadeIn(playerView, 1000L, 0f, 1f);
                            }

                            // Сохраняем длительность видео
                            if (player != null) {
                                long duration = player.getDuration();
                                dataStorage.put("Animate_dur", duration);

                                // Запускаем фоновые задачи после начала заставки
                                activity.backgroundTaskManager.addBackgroundTask(
                                        "checkServerDataTask", null, 0
                                );
                            }
                        }
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        Log.e(TAG, "Ошибка воспроизведения видео", error);
                        handleVideoError();
                    }

                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (playbackState == Player.STATE_ENDED) {
                            // Видео завершилось
                            dataStorage.put("Animate_status", STATUS_COMPLETED);
                            dataStorage.put("Animate_cur", dataStorage.getLong("Animate_dur"));
                        }
                    }
                };
            }

            /**
             * Обработка ошибки видео - переключение на изображение-заглушку
             */
            private void handleVideoError() {
                dataStorage.put("Animate_dur", 5363L);
                dataStorage.put("Animate_cur", 5363L);
                dataStorage.put("Animate_status", STATUS_FALLBACK);

                // Запускаем серверную авторизацию в фоне
                activity.backgroundTaskManager.addBackgroundTask("checkServerDataTask", null, 0);

                showFallbackImage();
            }

            /**
             * Показать изображение-заглушку
             */
            private void showFallbackImage() {
                if (!dataStorage.getBoolean("Animate_png_show")) {
                    try {
                        String imagePath = dataStorage.getString("Animate_png");
                        InputStream ims = activity.getAssets().open(imagePath);
                        Drawable d = Drawable.createFromStream(ims, null);

                        if (fallbackImageView != null) {
                            fallbackImageView.setImageDrawable(d);
                            Interface.Animation.fadeIn(fallbackImageView, 1000L, 0f, 1f);
                        }

                        dataStorage.put("Animate_png_show", true);

                    } catch (IOException ex) {
                        Log.e(TAG, "Ошибка загрузки изображения-заглушки", ex);
                    }
                }
            }

            /**
             * Управление состоянием заставки
             */
            public void update() {
                int status = dataStorage.getInt("Animate_status");
                long duration = dataStorage.getLong("Animate_dur");

                switch (status) {
                    case STATUS_NOT_STARTED:
                        // Еще не запущена - ничего не делаем
                        break;

                    case STATUS_PLAYING:
                        // Обновляем текущую позицию воспроизведения
                        if (player != null && duration > 0L) {
                            long currentPosition = player.getCurrentPosition();
                            dataStorage.put("Animate_cur", currentPosition);

                            // Проверяем завершение
                            if (currentPosition >= duration) {
                                dataStorage.put("Animate_status", STATUS_COMPLETED);
                            }
                        }
                        break;

                    case STATUS_COMPLETED:
                        // Видео завершено - останавливаем
                        pause();
                        break;

                    case STATUS_FALLBACK:
                        // Используем изображение-заглушку
                        showFallbackImage();
                        break;
                }
            }

            /**
             * Приостановить воспроизведение и сохранить состояние
             */
            public void pause() {
                if (player != null) {
                    long currentPosition = player.getCurrentPosition();
                    dataStorage.put("Animate_cur", currentPosition);
                    player.pause();

                    // Плавно скрываем видео
                    if (playerView != null && playerView.getAlpha() > 0f) {
                        // Interface.Animation.fadeOut(playerView, 300L, null, 0f);
                    }
                    // Плавно скрываем заглушку
                    if (fallbackImageView != null && fallbackImageView.getAlpha() > 0f) {
                        Interface.Animation.fadeOut(fallbackImageView, 300L, null, 0f);
                    }
                }
            }

            /**
             * Возобновить воспроизведение
             */
            public void resume() {
                if (player != null && dataStorage.getInt("Animate_status") == STATUS_PLAYING) {
                    player.setPlayWhenReady(true);
                }
            }

            /**
             * Освободить ресурсы
             */
            public void release() {
                if (player != null) {
                    // Сохраняем текущую позицию
                    long currentPosition = player.getCurrentPosition();
                    dataStorage.put("Animate_cur", currentPosition);

                    // Освобождаем ресурсы
                    player.release();
                    player = null;
                }

                // Очищаем изображение-заглушку
                if (fallbackImageView != null) {
                    fallbackImageView.setImageDrawable(null);
                }
            }

            /**
             * Получить текущий статус заставки
             */
            public int getStatus() {
                return dataStorage.getInt("Animate_status");
            }

            /**
             * Получить прогресс воспроизведения (0-100%)
             */
            public int getProgress() {
                long duration = dataStorage.getLong("Animate_dur");
                long current = dataStorage.getLong("Animate_cur");

                if (duration <= 0) return 0;
                return (int) ((current * 100) / duration);
            }

            /**
             * Пропустить заставку (перейти сразу к основному интерфейсу)
             */
            public void skip() {
                if (player != null) {
                    player.seekTo(player.getDuration());
                }
                dataStorage.put("Animate_status", STATUS_COMPLETED);
                dataStorage.put("Animate_cur", dataStorage.getLong("Animate_dur"));

                // Скрываем элементы заставки
                if (playerView != null) {
                    Interface.Animation.fadeOut(playerView, 200L, null, 0f);
                }
                if (fallbackImageView != null) {
                    Interface.Animation.fadeOut(fallbackImageView, 200L, null, 0f);
                }
            }
        }

        /**
         * Класс для централизованного управления стилями интерфейса
         * Обеспечивает согласованное оформление всех элементов
         */
        public static class Styling {
            private static final String TAG = "Styling";

            private Context context;
            private SeanceDataStorage dataStorage;

            // Кэшированные цвета для оптимизации
            private int cachedMainBackgroundColor = Color.TRANSPARENT;
            private int cachedMainFontColor = Color.BLACK;
            private int cachedMainElementColor = Color.BLACK;
            private float cachedIconAlpha = 0.4f;

            public Styling(Context context, SeanceDataStorage dataStorage) {
                this.context = context;
                this.dataStorage = dataStorage;
            }

            /**
             * Применить стили ко всем элементам интерфейса
             * Централизованный метод для обновления всех стилей
             */
            public void applyAllStyles(View rootView) {
                // Получаем текущие значения стилей
                updateCachedColors();

                // 1. Применяем стили к основным зонам
                applyZoneStyles(rootView);

                // 2. Применяем стили к текстовым элементам
                applyTextStyles(rootView);

                // 3. Применяем стили к иконкам
                applyIconStyles(rootView);

                // 4. Применяем стили к системным элементам
                applySystemStyles();

            }

            /**
             * Обновить кэшированные значения цветов
             */
            private void updateCachedColors() {
                try {
                    cachedMainBackgroundColor = Color.parseColor(dataStorage.getString("default.mainBackgroundColor"));
                    cachedMainFontColor = Color.parseColor(dataStorage.getString("default.mainFontColor"));
                    cachedMainElementColor = Color.parseColor(dataStorage.getString("default.mainElementColor"));
                    cachedIconAlpha = dataStorage.getFloat("IconAlpha");
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка парсинга цветов", e);
                }
            }

            /**
             * Применить стили к зонам интерфейса
             */
            private void applyZoneStyles(View rootView) {
                // Идентификаторы зон для стилизации
                int[] zoneIds = {
                        R.id.rootLayout,
                        R.id.headerZone,
                        R.id.activeZone,
                        R.id.menuZone,
                        R.id.headerOption1,
                        R.id.headerOption2,
                        R.id.activeOption1,
                        R.id.activeOption2,
                        R.id.activeOption3,
                        R.id.activeOption4,
                        R.id.activeOption5,
                        R.id.menuOption1,
                        R.id.menuOption2,
                        R.id.playerView,
                        R.id.animateImageView
                };

                for (int id : zoneIds) {
                    View view = rootView.findViewById(id);
                    if (view != null) {
                        setViewBackgroundColor(view, cachedMainBackgroundColor);
                    }
                }
            }

            /**
             * Применить стили к текстовым элементам
             */
            private void applyTextStyles(View rootView) {
                // Идентификаторы текстовых элементов
                int[] textViewIds = {
                        R.id.userText,
                        R.id.progressStatusTextView,
                        R.id.messageTextView,
                        R.id.menuStoreText,
                        R.id.menuWarehouseText,
                        R.id.menuInfoText,
                        R.id.weather_grad,
                        R.id.weatherNoData,
                        R.id.messageCloseText,
                        R.id.messageCloseSeconds
                };

                for (int id : textViewIds) {
                    TextView textView = rootView.findViewById(id);
                    if (textView != null) {
                        textView.setTextColor(cachedMainFontColor);
                    }
                }
            }

            /**
             * Применить стили к иконкам
             */
            private void applyIconStyles(View rootView) {
                // Идентификаторы иконок
                int[] imageViewIds = {R.id.userIcon};

                for (int id : imageViewIds) {
                    ImageView imageView = rootView.findViewById(id);
                    if (imageView != null) {
                        imageView.setAlpha(cachedIconAlpha);
                    }
                }
            }

            /**
             * Применить стили к системным элементам
             */
            private void applySystemStyles() {
                if (context instanceof android.app.Activity) {
                    android.app.Activity activity = (android.app.Activity) context;
                    android.view.Window window = activity.getWindow();
                    if (window != null) {
                        window.setStatusBarColor(cachedMainBackgroundColor);
                        window.setNavigationBarColor(cachedMainBackgroundColor);
                    }
                }
            }

            /**
             * Обновить цвет элемента с проверкой на совпадение
             */
            public static void setViewBackgroundColor(View view, int color) {
                if (view != null) {
                    Drawable background = view.getBackground();
                    if (background instanceof ColorDrawable) {
                        int currentColor = ((ColorDrawable) background).getColor();
                        if (currentColor != color) {
                            view.setBackgroundColor(color);
                        }
                    } else {
                        view.setBackgroundColor(color);
                    }
                }
            }

            /**
             * Очистка ресурсов
             */
            public void cleanup() {
                context = null;
                dataStorage = null;
            }
        }

        /**
         * Класс для централизованного управления жестами приложения
         * Обрабатывает двойные тапы, долгие нажатия и другие жесты
         */
        public static class Gestures {
            private static final String TAG = "Gestures";

            private MainActivity activity;
            private GestureDetector gestureDetector;
            private SeanceDataStorage dataStorage;
            private VibrationManager vibrationManager;

            // Ссылки на UI элементы
            private View weatherContainer;
            private TextView userText;
            private ImageView userIcon;
            private ImageView decanterIcon;

            // Состояние жестов иконки пользователя
            private boolean isUserIconDragging = false;
            private float userIconInitialY;
            private float userIconMaxDragDistance;
            private boolean userIconMaxReached = false;
            private ValueAnimator userIconResetAnimator;
            private Handler longPressHandler;

            // Состояние
            private Interface.Messages messagesManager;

            public Gestures(MainActivity activity, Interface.Messages messagesManager,
                            SeanceDataStorage dataStorage, VibrationManager vibrationManager) {
                this.activity = activity;
                this.messagesManager = messagesManager;
                this.dataStorage = dataStorage;
                this.vibrationManager = vibrationManager;
                this.longPressHandler = new Handler();

                initializeGestureDetector();
            }

            /**
             * Инициализация детектора жестов
             */
            private void initializeGestureDetector() {
                gestureDetector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        return handleDoubleTap(e);
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        handleLongPress(e);
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        return false;
                    }
                });
            }

            /**
             * Установить ссылки на UI элементы, включая иконки пользователя
             */
            public void setUiElements(View weatherContainer, TextView userText,
                                      ImageView userIcon, ImageView decanterIcon) {
                this.weatherContainer = weatherContainer;
                this.userText = userText;
                this.userIcon = userIcon;
                this.decanterIcon = decanterIcon;

                setupGestureListeners();
                setupUserIconDragGesture();
            }

            /**
             * Настройка обработчиков жестов для элементов
             */
            private void setupGestureListeners() {

                if (userText != null) {
                    userText.setOnTouchListener(createGeneralTouchListener());
                }
                if (weatherContainer != null) {
                    weatherContainer.setOnTouchListener(createGeneralTouchListener());
                }
            }

            /**
             * Создание общего обработчика касаний для элементов без специальных жестов
             */
            private View.OnTouchListener createGeneralTouchListener() {
                return new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return gestureDetector.onTouchEvent(event);
                    }
                };
            }

            /**
             * Настройка жеста оттяжки для иконки пользователя (только после долгого нажатия)
             */
            public void setupUserIconDragGesture() {
                if (userIcon == null) return;

                userIcon.post(new Runnable() {
                    @Override
                    public void run() {
                        float initialY = userIcon.getY();
                        if (Float.isNaN(initialY)) {
                            initialY = 0f;
                        }
                        userIconInitialY = initialY;
                    }
                });

                userIcon.setOnTouchListener(new View.OnTouchListener() {
                    private float startRawY;
                    private boolean isDragging = false;
                    private float lastProgress = 0f;
                    private Runnable longPressRunnable;
                    private boolean isLongPressTriggered = false;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        // Передаем событие в общий детектор жестов для двойного тапа
                        gestureDetector.onTouchEvent(event);

                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                if (!activity.uiUpdater.ActionIsPermitted()) return false;

                                // Запускаем таймер для долгого нажатия
                                isLongPressTriggered = false;
                                longPressRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!activity.uiUpdater.ActionIsPermitted()) return;

                                        // Долгое нажатие сработало
                                        isLongPressTriggered = true;
                                        isDragging = true;
                                        isUserIconDragging = true;

                                        // Сохраняем начальную позицию в поле класса
                                        userIconInitialY = userIcon.getY();
                                        startRawY = event.getRawY();
                                        userIconMaxReached = false;
                                        lastProgress = 0f;

                                        // Вибро-подтверждение начала жеста оттяжки
                                        vibrationManager.vibrate(VibrationManager.TYPE_CONFIRMATION);

                                        // Вычисляем максимальное расстояние для оттяжки
                                        int[] headerLocation = new int[2];
                                        activity.headerOption2.getLocationOnScreen(headerLocation);
                                        float headerBottom = headerLocation[1] + activity.headerOption2.getHeight();
                                        int[] iconLocation = new int[2];
                                        userIcon.getLocationOnScreen(iconLocation);
                                        userIconMaxDragDistance = headerBottom - (iconLocation[1] + userIcon.getHeight());
                                        if (userIconMaxDragDistance < 0) {
                                            userIconMaxDragDistance = 0;
                                        }

                                        // Убедимся, что decanterIcon находится в той же позиции
                                        if (decanterIcon != null) {
                                            decanterIcon.setY(userIconInitialY);
                                            safeSetScale(decanterIcon, 1.0f, 1.0f);
                                            decanterIcon.setAlpha(0f);
                                        }
                                    }
                                };

                                // Запускаем проверку долгого нажатия через 500ms
                                longPressHandler.postDelayed(longPressRunnable, 500);
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                if (!activity.uiUpdater.ActionIsPermitted()) {
                                    // Отменяем долгое нажатие если действие запрещено
                                    cancelLongPress();
                                    return false;
                                }

                                // Если долгое нажатие еще не сработало, проверяем не вышли ли за пределы
                                if (!isLongPressTriggered) {
                                    float currentRawY = event.getRawY();
                                    float deltaY = Math.abs(currentRawY - startRawY);

                                    // Если палец сдвинулся слишком сильно до срабатывания долгого нажатия - отменяем
                                    if (deltaY > Interface.Utils.dpToPx(activity, 20)) {
                                        cancelLongPress();
                                        return false;
                                    }
                                }

                                if (isDragging && isUserIconDragging) {
                                    float currentRawY = event.getRawY();
                                    float deltaY = currentRawY - startRawY;

                                    if (deltaY > 0) { // Только вниз
                                        float progress = Math.min(deltaY / userIconMaxDragDistance, 1.0f);

                                        // Визуальные эффекты - только масштаб, прозрачность фиксированная
                                        float scale = 1.0f - 0.1f * progress; // Легкое уменьшение
                                        safeSetScale(userIcon, scale, scale);

                                        // Прозрачность всегда остается IconAlpha
                                        userIcon.setAlpha(dataStorage.getFloat("IconAlpha"));

                                        // Обновляем позицию иконки
                                        float newY = userIconInitialY + (progress * userIconMaxDragDistance);
                                        userIcon.setY(newY);

                                        // Обновляем отображение иконки декатера
                                        updateDecanterIcon(progress);

                                        // Определяем направление движения
                                        boolean isIncreasing = progress > lastProgress;

                                        // Проверяем переход через порог (80%)
                                        if (isIncreasing && progress >= 0.8f && !userIconMaxReached) {
                                            // Достигли порога - вибрация
                                            vibrationManager.vibrate(VibrationManager.TYPE_ACTIVATION);
                                            userIconMaxReached = true;
                                        } else if (!isIncreasing && progress < 0.8f && userIconMaxReached) {
                                            // Вернулись ниже порога
                                            vibrationManager.vibrate(VibrationManager.TYPE_DEACTIVATION);
                                            userIconMaxReached = false;
                                        }

                                        lastProgress = progress;
                                    }
                                    return true;
                                }
                                return false;

                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                // Отменяем ожидание долгого нажатия
                                cancelLongPress();

                                if (isDragging && isUserIconDragging) {
                                    // Запускаем анимацию возврата
                                    resetUserIconPosition();

                                    // Если достигнут порог - запускаем функцию
                                    if (userIconMaxReached) {
                                        vibrationManager.vibrate(VibrationManager.TYPE_SOS);
                                        messagesManager.showMessage("Безумно ждать любви заочной?\n" +
                                                "В наш век все чувства лишь на срок;\n" +
                                                "Но я вас помню — да и точно,\n" +
                                                "Я вас никак забыть не мог!", 8);
                                    }

                                    // Сбрасываем состояния
                                    isDragging = false;
                                    isUserIconDragging = false;
                                    userIconMaxReached = false;
                                    isLongPressTriggered = false;
                                }
                                return true;
                        }
                        return false;
                    }

                    private void cancelLongPress() {
                        if (longPressHandler != null && longPressRunnable != null) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                        }
                        isLongPressTriggered = false;
                    }
                });
            }

            /**
             * Анимация возврата иконки пользователя в исходное положение
             */
            public void resetUserIconPosition() {
                if (userIconResetAnimator != null && userIconResetAnimator.isRunning()) {
                    userIconResetAnimator.cancel();
                }

                // Получаем текущую позицию иконки
                float startY = userIcon.getY();
                // Конечная позиция - исходное положение (поле класса)
                float endY = userIconInitialY;

                // Проверка на корректность значений
                if (Float.isNaN(startY) || Float.isNaN(endY)) {
                    // В случае ошибки просто устанавливаем значения по умолчанию
                    userIcon.setY(userIconInitialY);
                    safeSetScale(userIcon, 1.0f, 1.0f);
                    userIcon.setAlpha(dataStorage.getFloat("IconAlpha"));
                    return;
                }

                userIconResetAnimator = ValueAnimator.ofFloat(startY, endY);
                userIconResetAnimator.setDuration(300);
                userIconResetAnimator.setInterpolator(new OvershootInterpolator(1.5f));
                userIconResetAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        float value = (float) animation.getAnimatedValue();
                        userIcon.setY(value);

                        // Вычисляем прогресс возврата (от 1 до 0)
                        float returnProgress = 1 - (value - endY) / (startY - endY);

                        // Восстанавливаем масштаб
                        safeSetScale(userIcon, 0.9f + 0.1f * returnProgress, 0.9f + 0.1f * returnProgress);

                        // Прозрачность всегда остается IconAlpha
                        userIcon.setAlpha(dataStorage.getFloat("IconAlpha"));

                        // Обновляем иконку декатера во время возврата
                        if (userIconMaxDragDistance > 0) {
                            updateDecanterIcon(returnProgress * (startY - endY) / userIconMaxDragDistance);
                        }
                    }
                });
                userIconResetAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Гарантируем, что иконка вернулась точно на место
                        userIcon.setY(endY);
                        safeSetScale(userIcon, 1.0f, 1.0f);
                        userIcon.setAlpha(dataStorage.getFloat("IconAlpha"));

                        // Плавное исчезновение иконки декатера за 50ms
                        if (decanterIcon != null) {
                            Interface.Animation.fadeOut(decanterIcon, 50L, null, null);
                        }
                    }
                });
                userIconResetAnimator.start();
            }

            /**
             * Обновление отображения иконки декатера в зависимости от прогресса оттяжки
             */
            private void updateDecanterIcon(float progress) {
                if (decanterIcon != null) {
                    // Убедимся, что иконка видима в начале оттяжки
                    if (progress > 0 && decanterIcon.getVisibility() != View.VISIBLE) {
                        decanterIcon.setVisibility(View.VISIBLE);
                    }

                    // Проверка progress на NaN
                    if (Float.isNaN(progress)) {
                        progress = 0f;
                    }

                    // Alpha ограничена значением IconAlpha и пропорциональна прогрессу оттяжки
                    float maxAlpha = 1.0f;
                    float alpha = progress * maxAlpha; // Умножаем прогресс на максимальную альфу
                    decanterIcon.setAlpha(alpha);

                    // Иконка декатера всегда остается на исходной позиции
                    // Убедимся, что позиция корректна
                    if (!Float.isNaN(userIconInitialY)) {
                        decanterIcon.setY(userIconInitialY);
                    }
                    safeSetScale(decanterIcon, 1.0f, 1.0f);
                }
            }

            /**
             * Безопасная установка масштаба
             */
            private void safeSetScale(View view, float scaleX, float scaleY) {
                if (Float.isNaN(scaleX)) {
                    scaleX = 1.0f;
                }
                if (Float.isNaN(scaleY)) {
                    scaleY = 1.0f;
                }
                view.setScaleX(scaleX);
                view.setScaleY(scaleY);
            }

            /**
             * Проверка, находится ли точка касания в пределах View
             */
            private boolean isViewUnderTouch(View view, MotionEvent event) {
                if (view == null) return false;

                // Если идет перетаскивание userIcon, блокируем другие жесты
                if (isUserIconDragging && view != userIcon) {
                    return false;
                }

                int[] location = new int[2];
                view.getLocationOnScreen(location);

                int x = (int) event.getRawX();
                int y = (int) event.getRawY();

                return x >= location[0] && x <= location[0] + view.getWidth() &&
                        y >= location[1] && y <= location[1] + view.getHeight();
            }

            /**
             * Обработка двойного тапа
             */
            private boolean handleDoubleTap(MotionEvent e) {
                if (weatherContainer != null && isViewUnderTouch(weatherContainer, e)) {
                    onWeatherDoubleTap();
                    return true;
                }

                if (userText != null && isViewUnderTouch(userText, e)) {
                    onUserTextDoubleTap();
                    return true;
                }

                return false;
            }

            /**
             * Обработка долгого нажатия
             */
            private void handleLongPress(MotionEvent e) {
                // Зарезервировано для будущего использования
                // В текущей реализации долгие нажатия не используются
            }

            /**
             * Двойной тап на погоду - показ детальной информации
             */
            private void onWeatherDoubleTap() {
                if (!activity.uiUpdater.ActionIsPermitted() ||
                        dataStorage.getString("Weather_grad").isEmpty()) {
                    return;
                }

                // Анимация встряски
                Interface.Animation.shakeHorizontal(weatherContainer, 10f, 50L);

                // Получение данных о погоде
                String description = dataStorage.getString("Weather_desc");
                String temperature = dataStorage.getString("Weather_grad");
                String feelsLike = dataStorage.getString("Weather_grad_e");
                String Weather_wind = dataStorage.getString("Weather_wind");
                String Weather_press = dataStorage.getString("Weather_press");
                String Weather_gm = dataStorage.getString("Weather_gm");
                String Weather_hum = dataStorage.getString("Weather_hum");

                // Тактильная обратная связь
                vibrationManager.vibrate(VibrationManager.TYPE_SUCCESS);

                // Формирование сообщения
                if (description != null && !description.isEmpty()) {
                    String message = String.format("в Москве %s,\nтемпература: %s,\nпо ощущению: %s,\n\nветер: %s,\nвлажность: %s,\nдавление: %s,\nг-м активность: %s.",
                            description, temperature, feelsLike, Weather_wind, Weather_hum, Weather_press, Weather_gm);
                    messagesManager.showMessage(message, 8);
                } else {
                    messagesManager.showMessage("Данные о погоде недоступны", 8);
                }
            }

            /**
             * Двойной тап на текст пользователя - вход в настройки
             */
            private void onUserTextDoubleTap() {
                if (!activity.uiUpdater.ActionIsPermitted()) {
                    return;
                }

                // Анимация встряски
                Interface.Animation.shakeHorizontal(userText, 10f, 50L);

                // Тактильная обратная связь
                vibrationManager.vibrate(VibrationManager.TYPE_CONFIRMATION);

                // Информационное сообщение
                messagesManager.showMessage("Настройки пользователя", null);
            }

            /**
             * Передать событие касания в детектор жестов
             * Используется для элементов, которые не имеют собственных обработчиков
             */
            public boolean onTouchEvent(MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
            }

            /**
             * Геттер для флага перетаскивания (нужен для isViewUnderTouch)
             */
            public boolean isUserIconDragging() {
                return isUserIconDragging;
            }

            /**
             * Очистка ресурсов
             */
            public void cleanup() {
                if (userIconResetAnimator != null && userIconResetAnimator.isRunning()) {
                    userIconResetAnimator.cancel();
                }
                if (userIconResetAnimator != null) {
                    userIconResetAnimator.removeAllListeners();
                    userIconResetAnimator = null;
                }

                if (longPressHandler != null) {
                    longPressHandler.removeCallbacksAndMessages(null);
                    longPressHandler = null;
                }

                // Удаляем обработчики касаний
                if (userIcon != null) {
                    userIcon.setOnTouchListener(null);
                }
                if (userText != null) {
                    userText.setOnTouchListener(null);
                }
                if (weatherContainer != null) {
                    weatherContainer.setOnTouchListener(null);
                }

                gestureDetector = null;
                activity = null;
                messagesManager = null;
                dataStorage = null;
                vibrationManager = null;
                weatherContainer = null;
                userText = null;
                userIcon = null;
                decanterIcon = null;
            }
        }

        /**
         * Класс для утилит интерфейса.
         * Содержит вспомогательные методы для работы с UI, конвертации, анимаций и других общих операций.
         */
        public static class Utils {
            private static final String TAG = "Interface.Utils";

            /**
             * Конвертирует dp в пиксели
             *
             * @param context контекст приложения
             * @param dp      значение в dp
             * @return значение в пикселях
             */
            public static float dpToPx(Context context, float dp) {
                return TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        dp,
                        context.getResources().getDisplayMetrics()
                );
            }

            /**
             * Заменяет переменные в формате %variable% на значения из хранилища данных
             *
             * @param text        исходный текст с переменными
             * @param dataStorage хранилище данных
             * @return текст с замененными переменными
             */
            public static String replaceVariables(String text, SeanceDataStorage dataStorage) {
                if (text == null || text.isEmpty() || !text.contains("%")) {
                    return text;
                }

                Pattern pattern = Pattern.compile("%([^%]+)%");
                Matcher matcher = pattern.matcher(text);
                StringBuffer result = new StringBuffer();

                while (matcher.find()) {
                    String variableName = matcher.group(1);
                    String replacement = dataStorage.getString(variableName);

                    if (replacement != null && !replacement.isEmpty()) {
                        matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
                    } else {
                        matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                    }
                }

                matcher.appendTail(result);
                return result.toString();
            }

            // Получить идентификатор клиента (пукай это пока Android id)
            public static String getClientId(Context context) {

                try {

                    String androidId = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    if (androidId != null && !androidId.isEmpty() &&
                            !"9774d56d682e549c".equals(androidId)) {
                        return androidId;
                    }
                } catch (Exception e) {
                }
                return "unknown";
            }

            /**
             * Проверяет наличие сохраненного файла с данными сеанса
             * (это нужно чтобы, для различных сценарий открытия активностей, также для возврата в предыдущее меню)
             */
            public static boolean DataSeanceStorageFilenameExist(Context context, String name) {

                String filename = "SeanceDataStorage_" + name + ".json";
                File file = new File(context.getFilesDir(), filename);
                return file.exists();
            }

            /**
             * Значения в хранилище данных активности по умолчанию
             */
            public static void InitializeThisActivityDefaultDataForSeanceDataStorage(SeanceDataStorage dataStorage, Context context) {

                // Основные данные

                dataStorage.put("Ver", AppVersionUtils.getName(context));                              // Текстовое представление версии программы
                dataStorage.put("VerCode", AppVersionUtils.getCode(context));                          // Числовое представление версии программы
                dataStorage.put("Animate_mp4", "animate.mp4");                                              // Файл заставки
                dataStorage.put("Animate_png", "animate.png");                                              // Файл заглушки вместо заставки
                dataStorage.put("Animate_png_show", false);                                                 // Файл заглушки показан
                dataStorage.put("Animate_cur", 0L);                                                         // Текущее положение видео
                dataStorage.put("Animate_dur", 0L);                                                         // Длительность видео
                dataStorage.put("Animate_status", 0);                                                       // Статус заставки
                dataStorage.put("Progress", 0);                                                             // Прогресс выполнения
                dataStorage.put("Progress_visible", true);                                                  // Видимость прогресса выполнения
                dataStorage.put("Progress_status", "");                                                     // Статус прогресса
                dataStorage.put("Refresh", 100);                                                             // Частота обновления интерфейса в миллисекундах
                dataStorage.put("ErrorText", "");                                                           // Текст ошибки
                dataStorage.put("MessageText", "");                                                         // Текст сообщения пользователю
                dataStorage.put("MessageCloseIn", 0L);                                                      // На какое время показывается информативное сообщение
                dataStorage.put("AppCloseIn", 0L);                                                          // Когда закрываем приложение
                dataStorage.put("AppBlock", false);                                                         // Явный признак блокировки всех действий
                dataStorage.put("Can_work", false);                                                         // Все загрузки выполнены, доступ санкционирован - можно работать
                dataStorage.put("lastSwipe", 0L);                                                           // Дата последнего свайпа
                dataStorage.put("maxSwipe", 3);                                                             // Интервал между свайпами (секунды)
                dataStorage.put("MessageTextInterval", 5);                                                  // Стандартный интервал показа сообщений (секунды)
                dataStorage.put("ErrorTextInterval", 5);                                                    // Стандартный интервал показа ошибок (секунды)
                dataStorage.put("IconAlpha", 0.4f);                                                         // Прозрачность иконок по умолчанию
                dataStorage.put("Menu", "menuInfo");                                                        // Текущее активное подменю
                dataStorage.put("headerZone", "headerOption1");                                             // Текущий вариант шапки                                                                 //
                dataStorage.put("activeZone", "activeOption1");                                             // Текущий вариант активной зоны
                dataStorage.put("menuZone", "menuOption1");                                                 // Текущий вариант меню

                // Инициализация на сервере
                dataStorage.put("AccessSuccess", false);                                                    // Доступ к приложению открыт
                dataStorage.put("AccessSuccess_demo", false);                                               // включен демо-доступ
                dataStorage.put("AccessSuccess_errorText", "");                                             // Текст ошибки при связи с сервером
                dataStorage.put("AccessSuccess_errorCode", 0);                                              // Код ошибки при связи с сервером
                dataStorage.put("AccessSuccess_start", 0L);                                                 // Когда запущен фон
                dataStorage.put("AccessSuccess_stop", 0L);                                                  // Когда выполнен фон
                dataStorage.put("AccessSuccess_sent", false);                                               // Получили ответ от сервера
                dataStorage.put("AccessSuccess_received", false);                                           // Загрузили ответ от сервера
                dataStorage.put("AccessSuccess_clientId", "");                                              // Уникальный идентификатор устройства
                dataStorage.put("AccessSuccess_currentVersion", "");                                        // Нужная версия приложения для запуска
                dataStorage.put("AccessSuccess_currentDistribution", "");                                   // Ссылка на скачивание нужной версии
                dataStorage.put("AccessSuccess_userName", "");                                              // Имя пользователя
                dataStorage.put("AccessSuccess_userFullName", "");                                          // Полное имя пользователя
                dataStorage.put("AccessSuccess_objectCode", "");                                            // Код объекта-склада
                dataStorage.put("AccessSuccess_objectName", "");                                            // Наименование объекта-склада
                dataStorage.put("AccessSuccess_welcome", 0);                                                // Сколько по времени длится сообщение приветствия
                dataStorage.put("AccessSuccess_welcomeText", "");                                           // Текст сообщения приветствия
                dataStorage.put("AccessSuccess_welcomeStop", 0L);                                           // Время, когда заканчивается приветственное сообщение

                // Получение данных о погоде
                dataStorage.put("Weather_start", 0L);                                                       // Когда запущен фон
                dataStorage.put("Weather_stop", 0L);                                                        // Когда выполнен фон
                dataStorage.put("Weather_DataRefresh", 300);                                                // Обновляем данные погоды, интервал в секундах
                dataStorage.put("Weather_needShow", false);                                                 // Необходимо обновить отображение погоды
                dataStorage.put("Weather_errorText", "");                                                   // Текст ошибки получения погоды
                dataStorage.put("Weather_errorCode", 0);                                                    // Код ошибки получения погоды
                dataStorage.put("Weather_top", "");                                                         // svg погоды верхняя часть
                dataStorage.put("Weather_bottom", "");                                                      // svg погоды нижняя часть
                dataStorage.put("Weather_svg", "");                                                         // splash файл с иконками svg
                dataStorage.put("Weather_grad", "");                                                        // Градусы текущей погоды
                dataStorage.put("Weather_grad_e", "");                                                      // Градусы по ощущению
                dataStorage.put("Weather_wind", "");                                                        // Ветер
                dataStorage.put("Weather_press", "");                                                       // Давление
                dataStorage.put("Weather_hum", "");                                                         // Влажность
                dataStorage.put("Weather_gm", "");                                                          // Геомагнитная активность


                dataStorage.put("Weather_desc", "");                                                        // Описание текущей погоды

                // Загрузка и подготовка данных слайдов об акциях
                dataStorage.put("DownloadSlide_start", 0L);                                                 // Время старта задания
                dataStorage.put("DownloadSlide_stop", 0L);                                                  // Время выполнения задания
                dataStorage.put("DownloadSlide_errorCode", 0);                                              // Код ошибки при получении слайдов
                dataStorage.put("DownloadSlide_errorText", "");                                             // Текст ошибки при получении слайдов
                dataStorage.put("DownloadSlide_soundUrl", "drive=1XJ9cbXELoT8UhT_XWVPTRtPRoAHNrctr");       // Адрес для загрузки фонового сопровождения
                dataStorage.put("DownloadSlide_soundFile", "slide.mp3");                                    // Имя звукового файла
                dataStorage.put("DownloadSlide_len", 0);                                                    // Сколько слайдов всего (данные получены)
                dataStorage.put("DownloadSlide_cur", 0);                                                    // Сколько слайдов загружено локально
                dataStorage.put("DownloadSlide_minExpires", 0L);                                            // Минимальный срок хранения среди скачанных слайдов
                dataStorage.put("DownloadSlide_data", "");                                                  // Данные слайдов сериализованы в файл
                dataStorage.put("DownloadSlide_webView", "");                                               // Данные слайдов готовы к показу (страница html)
                dataStorage.put("DownloadSlide_needShow", false);                                           // Флаг необходимости открыть модуль показа акций

                // Загрузка и подготовка данных об команде
                dataStorage.put("DownloadTeam_start", 0L);                                                  // Время старта задания
                dataStorage.put("DownloadTeam_stop", 0L);                                                   // Время выполнения задания
                dataStorage.put("DownloadTeam_errorCode", 0);                                               // Код ошибки при получении данных команды
                dataStorage.put("DownloadTeam_errorText", "");                                              // Текст ошибки при получении данных команды
                dataStorage.put("DownloadTeam_soundUrl", "drive=1gnnvRn-u9wDAFcp1Q1Nfucty9uxzZEBv");        // Адрес для загрузки фонового сопровождения
                dataStorage.put("DownloadTeam_soundFile", "team.mp3");                                      // Имя звукового файла
                dataStorage.put("DownloadTeam_len", 0);                                                     // Сколько членов команды всего (данные получены)
                dataStorage.put("DownloadTeam_cur", 0);                                                     // Сколько членов команды загружено локально
                dataStorage.put("DownloadTeam_minExpires", 0L);                                             // Минимальный срок хранения среди скачанных слайдов
                dataStorage.put("DownloadTeam_data", "");                                                   // Данные команды сериализованы в файл
                dataStorage.put("DownloadTeam_webView", "");                                                // Данные команды готовы к показу (страница html)
                dataStorage.put("DownloadTeam_needShow", false);                                            // Флаг необходимости открыть модуль показа команды


                // Загрузка большого файла в фоне
                dataStorage.put("DownloadFile_start", 0L);                                                    // Время старта загрузки
                dataStorage.put("DownloadFile_stop", 0L);                                                     // Время завершения загрузки
                dataStorage.put("DownloadFile_from", "");                                                     // Адрес загружаемого файла
                dataStorage.put("DownloadFile_to", 0);                                                      // Тип каталога согласно FileStorage
                dataStorage.put("DownloadFile_folder", "");                                                   // Подкаталог
                dataStorage.put("DownloadFile_expires", 0L);                                                  // Срок хранения загруженного файла
                dataStorage.put("DownloadFile_fileName", "");                                                 // Если необходимо фиксированное имя файла на выходе
                dataStorage.put("DownloadFile_targetPath", "");                                               // Целевой путь
                dataStorage.put("DownloadFile_totalSize", 0L);                                                // Общий размер файла
                dataStorage.put("DownloadFile_downloadedSize", 0L);                                           // Загруженный размер
                dataStorage.put("DownloadFile_status", "");                                                   // Заголовок для отображения статуса
                dataStorage.put("DownloadFile_downloadProgressStart", 0);                                     // С какого общего прогресса стартовали (для обновления общего прогресса)
                dataStorage.put("DownloadFile_downloadProgressStop", 0);                                     // До какого общего прогресса дойдет дело когда скачаем
                dataStorage.put("DownloadFile_progress", 0);                                                  // Прогресс в процентах
                dataStorage.put("DownloadFile_speed", 0L);                                                    // Скорость загрузки (Мбайт/сек)
                dataStorage.put("DownloadFile_elapsedTime", 0L);                                              // Прошедшее время (мс)
                dataStorage.put("DownloadFile_remainingTime", 0L);                                            // Оставшееся время (мс)
                dataStorage.put("DownloadFile_errorCode", "");                                                // Код ошибки загрузки
                dataStorage.put("DownloadFile_errorText", "");                                                // Текст ошибки загрузки

            }

            /**
             * Форматирует время в читаемый формат
             *
             * @param milliseconds время в миллисекундах
             * @return отформатированная строка времени
             */
            public static String formatTime(long milliseconds) {
                long seconds = milliseconds / 1000;
                long minutes = seconds / 60;
                long hours = minutes / 60;

                if (hours > 0) {
                    return String.format("%dч %dм", hours, minutes % 60);
                } else if (minutes > 0) {
                    return String.format("%dм %dс", minutes, seconds % 60);
                } else {
                    return String.format("%dс", seconds);
                }
            }

        }
    }

    /** Управление обновлением интерфейса
     * Имеет доступ ко всем элементам MainActivity.
     */
    public class UIUpdater {

        private final SlideShowProcessor slideShowProcessor = new SlideShowProcessor();
        private final TeamShowProcessor teamShowProcessor = new TeamShowProcessor();

        /** Основной метод обновления UI
         */
        public void update() {
            if (isFinishing()) return;

            boolean canWork = dataStorage.getBoolean("Can_work");
            updateCommonUI();

            if (canWork) {
                updateWorkingUI();
            } else {
                initializationProcessor.processInitialization();

                if (splashManager != null) {
                    splashManager.update();
                }
            }
        }

        /** Обновление функций UI в рабочем режиме
         */
        private void updateWorkingUI() {

            updateWeather();                                // Отображение погоды
            slideShowProcessor.processSlideShowModule();    // Запуск слайдов об акциях
            teamShowProcessor.processTeamShowModule();      // Запуск информации о команде
        }

        /** Обновление отображения погоды
         */
        private void updateWeather() {
            if (dataStorage.getBoolean("Weather_needShow")) {
                Interface.Weather.updateDisplay(
                        weatherBottom,
                        weatherTop,
                        weatherGrad,
                        weatherNoData,
                        dataStorage,
                        MainActivity.this
                );
            }
        }

        /** Обновление общих элементов UI
         */
        private void updateCommonUI() {
            if (progressManager != null) {
                progressManager.update();
            }

            if (messagesManager != null) {
                messagesManager.update();
            }
        }

        /**
         * Запустить вибрацию
         */
        private void vibrate(String patternType) {

            vibrationManager.vibrate(patternType);
        }

        /** Дальнейшие действия разрешены
         */
        private boolean ActionIsPermitted() {

            // Не выведено сообщение и не установлена явная блокировка и не выполняется фон по команде
            long currentTime = System.currentTimeMillis();
            return currentTime >= dataStorage.getLong("AppCloseIn") && currentTime >= dataStorage.getLong("MessageCloseIn") && !dataStorage.getBoolean("AppBlock") && !dataStorage.getBoolean("Progress_visible");
        }

        /**  Запуск показа слайдов об акциях
         */
        public class SlideShowProcessor {

            /**
             * Обработка модуля слайдов
             */
            void processSlideShowModule() {
                int slideErrorCode = dataStorage.getInt("DownloadSlide_errorCode");

                if (slideErrorCode > 0) {
                    handleSlideError(slideErrorCode);
                } else if (dataStorage.getBoolean("DownloadSlide_needShow") &&
                        dataStorage.getLong("DownloadFile_stop") > 0L) {
                    progressManager.update();
                    handleSlideShowReady();
                }
            }

            /**
             * Обработка ошибки слайдов
             */
            private void handleSlideError(int errorCode) {
                String errorText = dataStorage.getString("DownloadSlide_errorText");
                dataStorage.put("DownloadSlide_errorText", "");
                dataStorage.put("DownloadSlide_errorCode", 0);

                messagesManager.showError(errorCode + ": " + errorText, 5, false);
                dataStorage.put("Progress", 0);
                dataStorage.put("Progress_visible", false);
            }

            /**
             * Обработка готовности слайдов
             */
            private void handleSlideShowReady() {
                dataStorage.put("DownloadSlide_needShow", false);

                String soundPath = dataStorage.getString("DownloadFile_targetPath");
                String webViewPath = dataStorage.getString("DownloadSlide_webView");

                if (soundPath.isEmpty()) {
                    handleSoundLoadError();
                } else if (!webViewPath.isEmpty()) {
                    openSlideShowActivity(webViewPath, soundPath);
                }
            }

            /**
             * Ошибка загрузки звука
             */
            private void handleSoundLoadError() {
                dataStorage.put("DownloadSlide_errorText", "");
                dataStorage.put("DownloadSlide_errorCode", 0);
                messagesManager.showError("11: Не удалось получить звуковое сопровождение", 5, false);
                dataStorage.put("Progress", 0);
                dataStorage.put("Progress_visible", false);
            }

            /**
             * Открытие активности слайдов
             */
            private void openSlideShowActivity(String webViewPath, String soundPath) {
                try {

                    messagesManager.showMessage("", 0);
                    dataStorage.saveState(TAG);
                    Intent intent = new Intent(MainActivity.this, SlideShowActivity.class);

                    JSONObject param = new JSONObject();
                    param.put("path", webViewPath);
                    param.put("sound", soundPath);
                    fileStorage.getParams(fileStorage.STORAGE_WORKING, "settings.json", "banners", param, null);

                    releaseInnerClasses();
                    // Запускаем новую активность с небольшой задержкой
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startActivity(intent);
                        finish();
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }, 100);

                } catch (Exception e) {
                    messagesManager.showError("неудача:\n" + e.getMessage(), 5, false);
                }
            }
        }

        /**  Запуск показа информации о команде
         */
        public class TeamShowProcessor {

            /** Обработка модуля данных о команде
             */
            void processTeamShowModule() {
                int teamErrorCode = dataStorage.getInt("DownloadTeam_errorCode");

                if (teamErrorCode > 0) {
                    handleTeamError(teamErrorCode);
                } else if (dataStorage.getBoolean("DownloadTeam_needShow") &&
                        dataStorage.getLong("DownloadFile_stop") > 0L) {
                    handleTeamShowReady();
                }
            }

            /** Обработка ошибки вывода данных о команде
             */
            private void handleTeamError(int errorCode) {
                String errorText = dataStorage.getString("DownloadTeam_errorText");
                dataStorage.put("DownloadTeam_errorText", "");
                dataStorage.put("DownloadTeam_errorCode", 0);

                messagesManager.showError(errorCode + ": " + errorText, 5, false);
                dataStorage.put("Progress", 0);
                dataStorage.put("Progress_visible", false);
            }

            /** Обработка готовности данных о команде
             */
            private void handleTeamShowReady() {
                dataStorage.put("DownloadTeam_needShow", false);
                progressManager.update();

                String soundPath = dataStorage.getString("DownloadFile_targetPath");
                String DownloadTeam_data = dataStorage.getString("DownloadTeam_data");

                if (soundPath.isEmpty()) {
                    handleSoundLoadError();
                } else if (!DownloadTeam_data.isEmpty()) {
                    openTeamShowActivity(DownloadTeam_data, soundPath);
                }
            }

            /** Ошибка загрузки звука
             */
            private void handleSoundLoadError() {
                dataStorage.put("DownloadTeam_errorText", "");
                dataStorage.put("DownloadTeam_errorCode", 0);
                messagesManager.showError("11: Не удалось получить звуковое сопровождение", 5, false);
                dataStorage.put("Progress", 0);
                dataStorage.put("Progress_visible", false);
            }

            /** Открытие активности слайдов
             */
            private void openTeamShowActivity(String dataPath, String soundPath) {
                try {
                    messagesManager.showMessage("", 0);
                    dataStorage.saveState(TAG);
                    Intent intent = new Intent(MainActivity.this, TeamShowActivity.class);

                    JSONObject param = new JSONObject();
                    param.put("path", dataPath);
                    param.put("sound", soundPath);
                    fileStorage.getParams(fileStorage.STORAGE_WORKING, "settings.json", "team", param, null);

                    releaseInnerClasses();
                    // Запускаем новую активность с небольшой задержкой
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startActivity(intent);
                        finish();
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }, 100);

                } catch (Exception e) {
                    messagesManager.showError("неудача:\n" + e.getMessage(), 5, false);
                }
            }
        }

    }

    /**
     * Процессор инициализации приложения - отвечает за всю логику подготовки к работе
     */
    private class InitializationProcessor {
        private static final String TAG = "InitializationProcessor";
        private volatile Context appContext;

        public InitializationProcessor(Context context) {
            this.appContext  = context;
        }

        /**
         * Основной метод подготовки к работе
         * Выполняется пока не получены доступы и данные
         */
        public void processInitialization() {
            // Если уже можно работать - выходим
            if (dataStorage.getBoolean("Can_work")) {
                return;
            }

            // Обновляем прогресс загрузки данных
            updateProgress();

            // Обрабатываем связь с сервером
            processServerConnection();
        }

        /**
         * Обновление прогресса инициализации
         */
        private void updateProgress() {

            if (dataStorage.getLong("DownloadFile_start")>0L) {
                return;
            }

            int progress = calculateProgress();
            dataStorage.put("Progress", progress);
        }

        /**
         * Вычисление текущего прогресса инициализации
         */
        private int calculateProgress() {
            long accessStop = dataStorage.getLong("AccessSuccess_stop");
            long weatherStop = dataStorage.getLong("Weather_stop");

            // Если все фоны выполнились
            if (accessStop > 0L && weatherStop > 0L) {
                return 100;
            }

            // Считаем прогресс согласно выполнению заданий
            if (dataStorage.getLong("AccessSuccess_start") != 0L) {
                int progress = 0;
                progress += dataStorage.getBoolean("AccessSuccess_sent") ? 40 : 0;
                progress += dataStorage.getBoolean("AccessSuccess_received") ? 40 : 0;
                progress += weatherStop > 0L ? 20 : 0;
                return progress;
            }

            return 0;
        }

        /** Обработка связи с сервером
         */
        private void processServerConnection() {
            long accessStop = dataStorage.getLong("AccessSuccess_stop");
            if (accessStop <= 0L) {
                return; // Связь с сервером еще не выполнена
            }

            int errorCode = dataStorage.getInt("AccessSuccess_errorCode");

            switch (errorCode) {
                case 0:
                    // Все хорошо - обрабатываем успешную авторизацию
                    processSuccessfulAuthorization();
                    break;

                case 1:
                    // Необходимо обновить версию программы
                    processAppUpdate();
                    break;

                default:
                    // Все остальное - ошибки, завершаем сеанс
                    processError(errorCode);
                    break;
            }
        }

        /**  Обработка успешной авторизации
         */
        private void processSuccessfulAuthorization() {
            // Проверяем завершение заставки
            int animateStatus = dataStorage.getInt("Animate_status");
            if (animateStatus != -1 && animateStatus != 2) {
                return; // Заставка еще не доиграна
            }

            // Проверяем время
            if (dataStorage.getLong("AccessSuccess_stop") >= System.currentTimeMillis()) {
                return;
            }

            // Обработка приветственного сообщения
            processWelcomeMessage();
        }

        /** Обработка приветственного сообщения
         */
        private void processWelcomeMessage() {
            long welcomeStop = dataStorage.getLong("AccessSuccess_welcomeStop");

            if (welcomeStop == 0L) {
                // Устанавливаем время окончания приветствия
                setupWelcomeMessage();
            } else if (welcomeStop < System.currentTimeMillis()) {
                // Приветствие закончилось - переходим в рабочий режим
                transitionToWorkMode();
            }
        }

        /** Настройка приветственного сообщения
         */
        private void setupWelcomeMessage() {
            long stopTime = System.currentTimeMillis() + 500L +
                    dataStorage.getInt("AccessSuccess_welcome") * 1000L;
            dataStorage.put("AccessSuccess_welcomeStop", stopTime);

            // Показываем приветственное сообщение
            String welcomeText = dataStorage.getString("AccessSuccess_welcomeText");
            if (!welcomeText.isEmpty() && dataStorage.getInt("AccessSuccess_welcome")>0) {
                welcomeText = Interface.Utils.replaceVariables(welcomeText, dataStorage);
                messagesManager.showMessage(welcomeText,
                        dataStorage.getInt("AccessSuccess_welcome"));
            }

            // Особенная приятная вибрация при разрешении работы
            uiUpdater.vibrate(VibrationManager.TYPE_WORK_ALLOWED);
        }

        /** Переход в рабочий режим
         */
        private void transitionToWorkMode() {
            dataStorage.put("Can_work", true);
            dataStorage.put("Progress_visible", false);
            dataStorage.put("Progress", 100);

            // Обновляем имя пользователя
            userText.setText(dataStorage.getString("AccessSuccess_userName"));

            // Показываем рабочие варианты интерфейса
            showWorkingInterface();

            // Позиционируем индикатор на кнопке "Информация"
            dataStorage.put("Menu", "menuInfo");
            Interface.MainMenu.positionActiveIndicatorOnView(dataStorage, activeIndicator, menuViews);
        }

        /** Отображение рабочего интерфейса
         */
        private void showWorkingInterface() {
            Interface.Zones.animateZonesTransition(
                    menuZone, "(1,100,2,200)",
                    activeZone, "(1,100,5,200)",
                    headerZone, "(1,100,2,200)",
                    menuOptions,
                    activeOptions,
                    headerOptions
            );
        }

        /** Обработка обновления приложения
         */
        private void processAppUpdate() {
            long downloadStart = dataStorage.getLong("DownloadFile_start");
            long downloadStop = dataStorage.getLong("DownloadFile_stop");
            long currentTime = System.currentTimeMillis();

            if (downloadStart == 0L) {
                // Запускаем загрузку обновления
                startAppUpdateDownload();
            } else {
                // Управляем отображением статуса загрузки
                manageDownloadStatus(downloadStart, downloadStop, currentTime);
            }
        }

        /** Запуск загрузки обновления приложения
         */
        private void startAppUpdateDownload() {
            BackgroundFunction.scheduleFileDownload(
                    dataStorage.getString("AccessSuccess_currentDistribution"),
                    fileStorage.STORAGE_WORKING,
                    "appUpdate",
                    System.currentTimeMillis() + 24 * 3600 * 1000L,
                    "Decanter_" + dataStorage.getString("AccessSuccess_currentVersion") + ".apk",
                    "обновление",
                    0,
                    100
            );
        }

        /** Управление отображением статуса загрузки
         */
        private void manageDownloadStatus(long downloadStart, long downloadStop, long currentTime) {
            // Сообщение об обновлении
            if (downloadStart + 1000L < currentTime && downloadStop == 0L) {
                updateDownloadMessage(currentTime);
            }

            // Обработка завершения загрузки
            if (downloadStop > 0L) {
                processDownloadComplete();
            }
        }

        /** Обновление сообщения о загрузке
         */
        private void updateDownloadMessage(long currentTime) {
            long messageCloseIn = dataStorage.getLong("MessageCloseIn");

            if (messageCloseIn == 0L) {
                String updateText = "Идет обновление на версию " +
                        dataStorage.getString("AccessSuccess_currentVersion");
                messagesManager.showMessage(updateText, 5);
            } else if (messageCloseIn - currentTime <= 1000L) {
                dataStorage.put("MessageCloseIn", currentTime + 5000L);
            }
        }

        /** Обработка завершения загрузки
         */
        private void processDownloadComplete() {
            dataStorage.put("Progress_visible", false);
            int errorCode = dataStorage.getInt("DownloadFile_errorCode");
            String targetPath = dataStorage.getString("DownloadFile_targetPath");

            // отрабатываем это один раз
            if (dataStorage.containsKey("AccessSuccess_UpdateStarted")) {
                return;
            }
            dataStorage.put("AccessSuccess_UpdateStarted", false);

            if (targetPath.isEmpty() || new File(targetPath).length()==0) {
                // Не удалось скачать
                showDownloadError(errorCode);
            } else {
                // Загрузка успешна - запускаем установку
                launchUpdateInstallation(targetPath);
            }
        }

        /** Показ ошибки загрузки
         */
        private void showDownloadError(int errorCode) {
            String errorText;
            if (errorCode > 0) {
                errorText = "Не удалось загрузить обновление:\n" +
                        errorCode + ": " + dataStorage.getString("DownloadFile_errorText");
            } else {
                errorText = "Не удалось загрузить обновление:\nнеизвестная причина ";
            }

            messagesManager.showError(errorText, 20, true);
        }

        /** Запуск установки обновления
         */
        private void launchUpdateInstallation(String targetPath) {

            if (targetPath.endsWith(".apk")) {
                try {
                    // 1. Проверяем версии
                    android.content.pm.PackageInfo currentPackageInfo = this.appContext.getPackageManager()
                            .getPackageInfo(this.appContext.getPackageName(),
                                    android.content.pm.PackageManager.GET_SIGNATURES);
                    android.content.pm.PackageInfo apkPackageInfo = this.appContext.getPackageManager()
                            .getPackageArchiveInfo(targetPath, android.content.pm.PackageManager.GET_SIGNATURES);

                    // 2. Проверяем подписи
                    boolean signaturesMatch = false;
                    if (apkPackageInfo != null) {
                        signaturesMatch = true;
                        for (int i = 0; i < currentPackageInfo.signatures.length && signaturesMatch; i++) {
                            signaturesMatch = currentPackageInfo.signatures[i]
                                    .equals(apkPackageInfo.signatures[i]);
                        }
                    }

                    // 3. Если подписи совпадают - устанавливаем с даунгрейдом
                    //if (signaturesMatch) {
                        installApk(targetPath);

                    //}
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка проверки подписи", e);
                }
            }
        }

        /** Непосредственно запуск самого обновления
         */
        private void installApk(String apkPath) {
            try {
                // Показываем инструкцию для пользователя
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    // Для Android 9+ можем попробовать
                    Uri apkUri = FileProvider.getUriForFile(
                            this.appContext,
                            this.appContext.getPackageName() + ".provider",
                            new File(apkPath)
                    );

                    Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                    intent.setData(apkUri);
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    // Попробуем добавить флаг (работает не на всех устройствах)
                    intent.putExtra(Intent.EXTRA_ALLOW_REPLACE, true);
                    this.appContext.startActivity(intent);
                    finish();
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка", e);
            }
        }

        /**
         * Обработка ошибок сервера
         */
        private void processError(int errorCode) {
            // Выводим ошибку один раз
            if (dataStorage.getString("ErrorText").isEmpty()) {
                String errorText = dataStorage.getString("AccessSuccess_errorText");
                messagesManager.showError(errorText, 20, true);
            }
        }
    }

    /** Запускает поток для регулярного обновления пользовательского интерфейса
     */
    private void startUIUpdateThread() {
        new Thread(() -> {
            // Работаем пока поток не прерван и активность не завершается
            while (!Thread.currentThread().isInterrupted() && !isFinishing()) {
                try {
                    // Обновляем UI в основном потоке
                    uiHandler.post(uiUpdater::update);
                    // Ждем указанный интервал перед следующим обновлением
                    Thread.sleep(dataStorage.getInt("Refresh"));
                } catch (InterruptedException e) {
                    // Корректно завершаем поток при прерывании
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка в потоке обновления UI", e);
                    break;
                }
            }
        }).start();
    }

    //region Жизненный цикл активности

    /** Создание активности - точка входа в приложение
     * Вызывается при первом создании активности.
     * Последовательность инициализации:
     * 1. Базовая конфигурация активности
     * 2. Восстановление состояния (если доступно)
     * 3. Инициализация системы хранения данных
     * 4. Настройка пользовательского интерфейса
     * 5. Запуск системных сервисов
     * 6. Планирование фоновых задач
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //startActivity(new Intent(this, TeamShowActivity.class));
        //finish();

        // Фиксируем портретную ориентацию
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        // Устанавливаем основной layout
        setContentView(R.layout.activity_main);

        initializeDataStorageSystems();                                                                 // Инициализация системы хранения данных
        initializeVibrate();                                                                            // Инициализируем менеджер вибрации
        initializeUserInterface();                                                                      // Инициализация пользовательского интерфейса
        initializeSystemServices();                                                                     // Запуск системных сервисов
        initializeCommands();                                                                           // Инициализация системы команд
        BackgroundFunction.initialize(this, dataStorage, fileStorage, backgroundTaskManager);   // Инициализация обертки фоновых функций
        scheduleBackgroundOperations();                                                                 // Планирование фоновых операций

    }

    //region Дочерние функции onCreate

    /** Инициализация системы хранения данных
     * Восстанавливает состояние из файла или создает новое хранилище.
     */
    private void initializeDataStorageSystems() {
        // Менеджер файлового хранилища
        fileStorage = FileStorageManager.getInstance(this);

        // Восстанавливаем или создаем хранилище данных сеанса
        if (Interface.Utils.DataSeanceStorageFilenameExist(this, TAG)) {
            dataStorage = SeanceDataStorage.getInstanceFile(TAG, this);
        } else {
            dataStorage = SeanceDataStorage.getInstance(this);
            // Проверяем, не инициализированы ли данные в памяти (например, после поворота)
            if (dataStorage.getString("AccessSuccess_clientId").isEmpty()) {
                Interface.Utils.InitializeThisActivityDefaultDataForSeanceDataStorage(dataStorage, this);
                dataStorage.put("AccessSuccess_clientId", Interface.Utils.getClientId(this));
            }
        }
    }

    /** Инициализация вибрации
     */
    private void initializeVibrate() {

        // Менеджер вибрации
        vibrationManager = VibrationManager.getInstance(this);
    }

    /** Инициализация пользовательского интерфейса
     * Настраивает все визуальные компоненты и их обработчики.
     */
    private void initializeUserInterface() {

        // Инициализация всех компонентов интерфейса
        initializeUIComponents();

        // Применение стилей и темы
        try {
            if (stylingManager != null) {
                View rootView = findViewById(android.R.id.content);
                stylingManager.applyAllStyles(rootView);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка применения стилей", e);
        }

        // Настройка начального состояния интерфейсных зон
        if (dataStorage.getBoolean("Can_work")) {
            // Рабочий вариант интерфейса
            configureWorkingInterface();
        } else {
            // Инициализация приложения
            configureInitializationInterface();
        }

        // Обновление отображения погоды
        Interface.Weather.updateDisplay(weatherBottom, weatherTop, weatherGrad, weatherNoData, dataStorage, this);
    }

    /** Инициализация всех UI-компонентов
     * Этапы инициализации:
     * 1. Основные контейнеры зон - верхняя, центральная, нижняя
     * 2. Варианты отображения для каждой зоны
     * 3. Элементы интерфейса внутри вариантов
     * 4. Функциональные менеджеры для управления UI
     * 5. Обработчики пользовательских взаимодействий
     */
    private void initializeUIComponents() {

        // Основные зоны интерфейса: шапка, активная область, меню
        menuZone = findViewById(R.id.menuZone);      // Нижняя зона - меню навигации
        activeZone = findViewById(R.id.activeZone);  // Центральная зона - основной контент
        headerZone = findViewById(R.id.headerZone);  // Верхняя зона - статусная информация

        // Варианты шапки
        headerOption1 = findViewById(R.id.headerOption1);  // Пустая шапка (режим загрузки)
        headerOption2 = findViewById(R.id.headerOption2);  // Рабочая шапка

        // Варианты активной зоны
        activeOption1 = findViewById(R.id.activeOption1);  // Заставка/анимация загрузки
        activeOption2 = findViewById(R.id.activeOption2);  // Экран приветствия
        activeOption3 = findViewById(R.id.activeOption3);  // Раздел "Магазин"
        activeOption4 = findViewById(R.id.activeOption4);  // Раздел "Склад"
        activeOption5 = findViewById(R.id.activeOption5);  // Раздел "Информация"

        // Варианты меню
        menuOption1 = findViewById(R.id.menuOption1);      // Информация о клиенте
        menuOption2 = findViewById(R.id.menuOption2);      // Основное навигационное меню

        // Элементы шапки
        userIcon = headerOption2.findViewById(R.id.userIcon);
        userText = headerOption2.findViewById(R.id.userText);
        decanterIcon = headerOption2.findViewById(R.id.decanterIcon);

        // Восстановление имени пользователя если доступно
        String userName = dataStorage.getString("AccessSuccess_userName");
        if (!userName.isEmpty()) { userText.setText(userName); }

        // Элементы погоды
        weatherContainer = headerOption2.findViewById(R.id.weatherContainer);
        weatherTop = headerOption2.findViewById(R.id.weatherTop);
        weatherBottom = headerOption2.findViewById(R.id.weatherBottom);
        weatherGrad = headerOption2.findViewById(R.id.weather_grad);
        weatherNoData = headerOption2.findViewById(R.id.weatherNoData);

        // Элементы заставки
        playerView = findViewById(R.id.playerView);
        animateImageView = findViewById(R.id.animateImageView);
        animateImageView.setBackgroundColor(Color.TRANSPARENT);

        // Инициализация видеоплеера для заставки
        player = new ExoPlayer.Builder(this).build();
        if (playerView != null) {
            playerView.setPlayer(player);
            playerView.setUseController(false);
            playerView.setBackgroundColor(Color.TRANSPARENT);
            playerView.setAlpha(0f);
        }

        // Элементы прогресса
        progressBar = findViewById(R.id.progressBar);
        progressStatusTextView = findViewById(R.id.progressStatusTextView);

        // Элементы системы сообщений
        messageContainer = findViewById(R.id.messageContainer);
        messageTextView = findViewById(R.id.messageTextView);
        messageCloseLayout = findViewById(R.id.messageCloseLayout);
        messageCloseText = findViewById(R.id.messageCloseText);
        messageCloseSeconds = findViewById(R.id.messageCloseSeconds);

        // Элементы основного меню

        // Магазин
        menuStore = findViewById(R.id.menuStore);
        menuStoreIcon = findViewById(R.id.menuStoreIcon);
        menuStoreText = findViewById(R.id.menuStoreText);

        // Склад
        menuWarehouse = findViewById(R.id.menuWarehouse);
        menuWarehouseIcon = findViewById(R.id.menuWarehouseIcon);
        menuWarehouseText = findViewById(R.id.menuWarehouseText);

        // Информация
        menuInfo = findViewById(R.id.menuInfo);
        menuInfoIcon = findViewById(R.id.menuInfoIcon);
        menuInfoText = findViewById(R.id.menuInfoText);

        // Индикатор активного пункта меню
        activeIndicator = findViewById(R.id.activeIndicator);

        // Массивы для удобного управления вариантами зон
        menuOptions = new ConstraintLayout[]{menuOption1, menuOption2};
        activeOptions = new ConstraintLayout[]{activeOption1, activeOption2, activeOption3,
                activeOption4, activeOption5};
        headerOptions = new ConstraintLayout[]{headerOption1, headerOption2};

        // Группировка элементов меню для централизованного доступа
        menuViews = new Interface.MainMenu.MenuViews(
                menuStore, menuWarehouse, menuInfo,
                menuStoreIcon, menuWarehouseIcon, menuInfoIcon,
                menuStoreText, menuWarehouseText, menuInfoText
        );

        // Менеджер сообщений и уведомлений
        messagesManager = new Interface.Messages(
                this, messageContainer, messageTextView,
                messageCloseLayout, messageCloseText, messageCloseSeconds, dataStorage
        );

        // Менеджер прогресса загрузки
        progressManager = new Interface.Progress(
                this, progressBar, progressStatusTextView, dataStorage
        );

        // Менеджер заставки и анимации
        splashManager = new Interface.Splash(this, playerView, animateImageView, dataStorage);
        splashManager.initialize();

        // Менеджер стилей и оформления
        stylingManager = new Interface.Styling(this, dataStorage);

        // Менеджер жестов и касаний
        gesturesManager = new Interface.Gestures(this, messagesManager, dataStorage, vibrationManager);

        // Настройка обработчиков кликов для пунктов меню
        Interface.MainMenu.setupMenuClickListeners(menuViews, menuId -> {
            if (uiUpdater.ActionIsPermitted()) {
                Interface.MainMenu.switchMenu(
                        menuId, activeZone, activeOptions,
                        dataStorage, activeIndicator, menuViews
                );
            }
        });

        // Настройка элементов для обработки жестов
        gesturesManager.setUiElements(weatherContainer, userText, userIcon, decanterIcon);

        // Обновление интерфейса
        uiUpdater = new UIUpdater();
        // Инициализация работы
        initializationProcessor = new InitializationProcessor(this);

    }

    /** Настройка рабочего интерфейса
     * Конфигурирует интерфейс для режима работы приложения.
     */
    private void configureWorkingInterface() {
        Interface.Zones.animateZonesTransition(
                menuZone, "(1,0,2,0)",
                activeZone, "(1,0,5,0)",
                headerZone, "(1,0,2,0)",
                menuOptions,
                activeOptions,
                headerOptions
        );

        dataStorage.put("Progress_visible", false);
        Interface.MainMenu.positionActiveIndicatorOnView(dataStorage, activeIndicator, menuViews);
    }

    /**  Настройка интерфейса инициализации
     * Конфигурирует интерфейс для режима загрузки/инициализации.
     */
    private void configureInitializationInterface() {
        Interface.Zones.animateZonesTransition(
                menuZone, "(1,0,1,0)",
                activeZone, "(1,0,1,0)",
                headerZone, "(1,0,1,0)",
                menuOptions,
                activeOptions,
                headerOptions
        );
    }

    /** Инициализация системных сервисов
     * Запускает все необходимые сервисы приложения.
     */
    private void initializeSystemServices() {


        // Менеджер фоновых задач
        backgroundTaskManager = new BackgroundTaskManager();

        // Инициализация системы фоновых задач
        initializeBackgroundTaskSystem();
    }

    /**
     * Инициализация системы фоновых задач
     * Настраивает систему для выполнения асинхронных операций.
     */
    private void initializeBackgroundTaskSystem() {
        uiHandler = new Handler(Looper.getMainLooper());
        BackgroundFunction.registerBackgroundFunctions(backgroundTaskManager);
        backgroundTaskManager.initialize();
        startUIUpdateThread();
    }

    /** Инициализация системы команд через реестр
     * <p>
     * Метод выполняет:
     * 1. Создание контекста выполнения с передачей всех зависимостей
     * 2. Инициализацию реестра команд (синглтон)
     * 3. Регистрацию всех команд в реестре
     * 4. Настройку обработчиков свайпа для всех элементов команд
     * 5. Применение стилей к элементам команд
     * <p>
     * Контекст содержит все необходимые зависимости для работы команд:
     * - Контекст приложения для доступа к ресурсам
     * - Хранилище данных для состояния приложения
     * - Менеджер файлов для операций с файловой системой
     * - Менеджер фоновых задач для асинхронных операций
     * - Менеджер вибрации для тактильной обратной связи
     * - Колбэк для взаимодействия с UI активности
     */
    private void initializeCommands() {
        // Создаем контекст выполнения команд со всеми зависимостями
        commandContext = new CommandSystem.CommandContext(
                this,                      // Context - контекст активности
                dataStorage,               // SeanceDataStorage - хранилище данных сеанса
                fileStorage,               // FileStorageManager - менеджер файлового хранилища
                backgroundTaskManager,     // BackgroundTaskManager - менеджер фоновых задач
                vibrationManager,          // VibrationManager - менеджер вибрации
                mainCommandCallback        // CommandCallback - колбэк для UI взаимодействия
        );

        // Инициализируем реестр команд (получаем экземпляр синглтона)
        commandRegistry = CommandSystem.CommandRegistry.getInstance();

        // Инициализируем реестр с созданным контекстом
        // Внутри происходит регистрация всех команд: PriceCheckCommand, ReceiptCommand и т.д.
        commandRegistry.initialize(commandContext);

        // Получаем корневое View для поиска элементов команд в layout
        View rootView = findViewById(android.R.id.content);

        // Автоматически инициализируем все обработчики свайпа для элементов команд
        // Для каждой команды создается SwipeHandler, который обрабатывает жесты свайпа
        commandRegistry.initializeAllSwipeHandlers(rootView);

        // Получаем цвета и параметры стиля из хранилища данных
        int mainFontColor = Color.parseColor(dataStorage.getString("default.mainFontColor"));
        int mainElementColor = Color.parseColor(dataStorage.getString("default.mainElementColor"));
        float iconAlpha = dataStorage.getFloat("IconAlpha");

        // Автоматически применяем стили ко всем командам
        commandRegistry.applyStylesToAllCommands(mainFontColor, mainElementColor, iconAlpha, rootView);

    }

    /** Планирование фоновых операций
     * Запускает периодические и одноразовые фоновые задачи.
     */
    private void scheduleBackgroundOperations() {
        // Очистка устаревших файлов в хранилище
        backgroundTaskManager.addBackgroundTask("cleanupFileStorageTask", null, 0);

        // Периодическое обновление погоды
        backgroundTaskManager.addBackgroundTask("updateWeatherTask", null,
                dataStorage.getInt("Weather_DataRefresh") * 1000L);
    }

    //endregion

    /** Активность становится видимой пользователю
     * Вызывается когда активность переходит из остановленного состояния в запущенное.
     * Действия:
     * - Возобновление медиа-контента (заставки)
     * - Подготовка к взаимодействию
     */
    @Override
    protected void onStart() {
        super.onStart();

        // Возобновляем воспроизведение заставки
        if (splashManager != null) {
            splashManager.resume();
        }
    }

    /** Активность выходит на передний план и становится активной
     * Вызывается когда активность начинает взаимодействие с пользователем.
     * Действия:
     * - Принудительное обновление интерфейса
     * - Проверка актуальности данных
     */
    @Override
    protected void onResume() {
        super.onResume();

        // Обновление пользовательского интерфейса
        uiUpdater.update();
    }

    /** Активность теряет фокус (но остается видимой)
     * Вызывается когда активность перестает быть интерактивной.
     * Действия:
     * - Сохранение текущего состояния медиа
     * - Приостановка воспроизведения
     * - Остановка вибрации
     */
    @Override
    protected void onPause() {
        super.onPause();

        // Сохраняем позицию воспроизведения заставки
        if (splashManager != null) {
            splashManager.pause();
        }

        // Отменяем активную вибрацию
        vibrationManager.cancel();
    }

    /** Активность больше не видима пользователю
     * Вызывается когда активность переходит из запущенного в остановленное состояние.
     * Действия:
     * - Приостановка всех активных операций
     */
    @Override
    protected void onStop() {
        super.onStop();


    }

    /** Финальное уничтожение активности
     * Вызывается перед окончательным уничтожением активности.
     * Критически важные действия:
     * 1. Остановка пользовательских операций
     * 2. Освобождение медиа-ресурсов
     * 3. Завершение фоновых потоков
     * 4. Очистка ссылок на UI компоненты
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        releaseMediaResources();                                        // Освобождение медиа-ресурсов
        shutdownBackgroundSystems();                                    // Завершение фоновых систем
        cleanupUIReferences();                                          // Очистка UI компонентов
        releaseInnerClasses();                                          // Очистка внутренних классов

    }

    //region Дочерние функции onDestroy

    /**
     * Полное завершение приложения
     */
    public void closeAppCompletely() {

        try {
            // Сначала пытаемся стандартным способом
            finishAndRemoveTask(); // Удаляем из списка недавних

            // Двойное убийство процесса для надежности
            int pid = android.os.Process.myPid();
            android.os.Process.killProcess(pid);

            // Жесткий выход
            System.exit(0);

            // На всякий случай еще один способ
            android.os.Process.killProcess(android.os.Process.myPid());

        } catch (Exception e) {
            // Последняя попытка
            finish();
            System.exit(1);
        }
    }

    /** Освобождение медиа-ресурсов
     * Останавливает и освобождает все медиа-компоненты.
     */
    private void releaseMediaResources() {
        // Остановка и освобождение видеоплеера
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** Завершение работы фоновых систем
     * Корректно останавливает все фоновые процессы.
     */
    private void shutdownBackgroundSystems() {
        // Корректное завершение менеджера фоновых задач
        if (backgroundTaskManager != null) {
            backgroundTaskManager.shutdown();
        }

        // Остановка UI Handler
        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
        }
    }

    /** Очистка ссылок на UI компоненты
     * Предотвращает утечки памяти при уничтожении активности.
     */
    private void cleanupUIReferences() {
        // Удаление обработчиков касаний для предотвращения утечек
        if (userIcon != null) {
            userIcon.setOnTouchListener(null);
        }

        // Очистка системы команд
        if (commandRegistry != null) {
            commandRegistry.cleanup();
            commandRegistry = null;
        }

        if (commandContext != null) {
            commandContext = null;
        }

        // Очистка менеджеров интерфейса
        cleanupInterfaceManagers();
    }

    /** Очистка внутренних классов
     * Освобождает ресурсы вспомогательных классов.
     */
    private void releaseInnerClasses() {
        if (fileStorage != null) {
            fileStorage.release();
        }

        if (dataStorage != null) {
            dataStorage.destroy();
        }

        BackgroundFunction.cleanup();
    }

    /** Очистка менеджеров интерфейса
     * Освобождает ресурсы всех UI-менеджеров.
     */
    private void cleanupInterfaceManagers() {
        if (messagesManager != null) {
            messagesManager.cleanup();
            messagesManager = null;
        }

        if (progressManager != null) {
            progressManager.cleanup();
            progressManager = null;
        }

        if (splashManager != null) {
            splashManager.release();
            splashManager = null;
        }

        if (stylingManager != null) {
            stylingManager.cleanup();
            stylingManager = null;
        }

        if (gesturesManager != null) {
            gesturesManager.cleanup();
            gesturesManager = null;
        }
    }

    //endregion

    /** Обработка изменений конфигурации
     * Вызывается при изменении конфигурации устройства (поворот экрана и т.д.).
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Перестраиваем UI если нужно
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Блокируем ландшафтную ориентацию
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    //endregion

}