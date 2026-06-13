package com.smolpochta.decanter;

//region Импорты

// Основные классы Android

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

// Интерфейс пользователя
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

// Графика и отрисовка
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.PictureDrawable;
import android.text.TextUtils;

// Ввод и касания
import android.view.MotionEvent;

// Датчики и ориентация
import android.hardware.SensorManager;
import android.view.OrientationEventListener;

// AndroidX
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

// JSON
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Работа с файлами
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

// Сеть
import java.net.HttpURLConnection;
import java.net.URL;

// Коллекции
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

// Медиа
import android.media.MediaPlayer;

// Сторонние библиотеки
import com.caverock.androidsvg.SVG;

//endregion

public class TeamShowActivity extends AppCompatActivity {
    private static final Logger log = LoggerFactory.getLogger(TeamShowActivity.class);

    //region Поля и переменные активности

    // Менеджеры
    private SoundManager soundManager;
    private FileStorageManager fileStorage;
    private TeamUIManager uiManager;
    private DataDisplayManager dataDisplayManager;
    private AutoScrollManager autoScrollManager;
    private SettingsManager settingsManager;
    private ActivityInitializer activityInitializer;

    // Ориентация и UI
    private int currentOrientation;
    private FrameLayout portraitContainer;
    private int currentViewType;
    private ViewPager2 portraitPager;
    private PortraitPagerAdapter portraitAdapter;
    private boolean isOrientationChanging = false;

    // Звук (портретная ориентация)
    private TextView portraitVolumeUpText;
    private TextView portraitVolumeDownText;
    private TextView portraitVolumeText;

    // Данные команды
    private JSONObject teamData;
    private JSONArray allEmployees;
    private JSONObject groupsData;
    private List<JSONObject> portraitEmployees = new ArrayList<>();
    private boolean dataLoaded = false;

    // Таймеры
    private Handler portraitAutoScrollHandler;
    private Runnable portraitAutoScrollRunnable;

    // Ориентация слушатели
    private OrientationEventListener orientationEventListener;
    private int lastDeviceOrientation = 0;

    // Позиции и счетчики
    private int currentPortraitPosition = 0;

    // Пути к файлам
    private String dataFilePath = "";
    private String soundFilePath = "";

    // Константы
    private static final int ORIENTATION_LANDSCAPE = 1;
    private static final int ORIENTATION_PORTRAIT = 2;
    private static final int VIEW_LANDSCAPE = 1;
    private static final int VIEW_PORTRAIT = 2;
    private static final int PORTRAIT_AUTO_SCROLL_DELAY = 8000;

    private TextView landscapeOrientationButton;    // Кнопка "Личности" для ландшафта
    private TextView portraitOrientationButton;     // Кнопка "Команда" для портрета

    //endregion

    //region Обработчики жизненного цикла

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Инициализация UI переменных
        initUIVariables();

        // Начальная настройка ориентации
        setupInitialOrientation();

        // Инициализация менеджеров
        initManagers();

        // Настройка UI
        setupUI();

        // Загрузка данных
        loadData();

        // Настройка автоскролла
        setupAutoScroll();

        // Настройка звука
        setupSound();

        // Показ интерфейса в зависимости от ориентации
        showOrientationDependentUI();

    }

    @Override
    protected void onPause() {
        super.onPause();
        if (soundManager != null) {
            soundManager.pause();
        }
        settingsManager.saveSettings(fileStorage, soundManager);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (soundManager != null && soundManager.isInitialized()) {
            soundManager.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupResources();
    }

    @Override
    public void onBackPressed() {
        navigateToMainActivity();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        handleOrientationChange(newConfig);
        super.onConfigurationChanged(newConfig);
    }

    //endregion

    //region Инициализация UI и менеджеров

    /**
     * Подготовка переменных для портретного режима управления звуком.
     * Инициализирует null значения, чтобы избежать NPE при первом обращении.
     */
    private void initUIVariables() {
        portraitVolumeUpText = null;    // Кнопка "+" для увеличения громкости (портрет)
        portraitVolumeDownText = null;  // Кнопка "-" для уменьшения громкости (портрет)
        portraitVolumeText = null;      // Текстовый индикатор текущей громкости (портрет)

        landscapeOrientationButton = null;
        portraitOrientationButton = null;
    }

    /**
     * Начальная конфигурация ориентации экрана.
     * Определяет текущую ориентацию устройства и блокирует ее в ландшафтном режиме.
     */
    private void setupInitialOrientation() {

        currentOrientation = ORIENTATION_LANDSCAPE;
        lockOrientationLandscape();    // Фиксирует ландшафтную ориентацию
    }

    /**
     * Инициализация всех управляющих компонентов (менеджеров) активности.
     * Каждый менеджер отвечает за свою функциональную область.
     */
    private void initManagers() {
        activityInitializer = new ActivityInitializer(this);
        activityInitializer.initializeActivityConfiguration();  // Настройка активности
        activityInitializer.initializeManagers();               // Инициализация менеджеров

        uiManager = new TeamUIManager(this);                    // Менеджер пользовательского интерфейса
        settingsManager = new SettingsManager(this);            // Менеджер настроек приложения
        dataDisplayManager = new DataDisplayManager(this, uiManager);  // Менеджер отображения данных
        autoScrollManager = new AutoScrollManager(this, uiManager);    // Менеджер автоскролла
    }

    /**
     * Базовая настройка пользовательского интерфейса.
     * Создает основные контейнеры и устанавливает начальное состояние.
     */
    private void setupUI() {
        uiManager.setupUI();                    // Создание основного интерфейса
        uiManager.getContainer().setAlpha(0f);  // Устанавливает прозрачность для анимации

        ensurePortraitContainer();              // Создает контейнер для портретного режима
        if (portraitContainer != null) {
            portraitContainer.setVisibility(View.GONE);  // Скрывает портретный контейнер
            portraitContainer.setAlpha(0f);              // Устанавливает прозрачность
        }
    }

    /**
     * Загрузка данных приложения из хранилища.
     * Включает настройки пользователя и данные о команде.
     */
    private void loadData() {
        settingsManager.loadSettings(fileStorage, soundManager);       // Загружает сохраненные настройки
        dataDisplayManager.loadTeamData(dataFilePath, fileStorage);    // Загружает данные команды
    }

    /**
     * Настройка системы автоматической прокрутки для ландшафтного режима.
     * Управляет плавным скроллингом и центрированием элементов.
     */
    private void setupAutoScroll() {
        autoScrollManager.setCenteringDuration(1000f);            // Длительность анимации центрирования
        autoScrollManager.setupTouchAndScrollMonitoring();        // Настройка обработки касаний
        autoScrollManager.setOrientation(ORIENTATION_LANDSCAPE);  // Активация только для ландшафта
    }

    /**
     * Настройка звукового сопровождения приложения.
     * Инициализирует фоновую музыку и элементы управления громкостью.
     */
    private void setupSound() {
        activityInitializer.initializeSound(soundFilePath);  // Загрузка и запуск музыки
    }

    /**
     * Отображение соответствующего интерфейса в зависимости от ориентации.
     * При старте активности показывает либо ландшафтный, либо портретный режим.
     */
    private void showOrientationDependentUI() {
        if (currentOrientation == ORIENTATION_LANDSCAPE) {
            setupLandscapeUI();  // Показывает ландшафтный интерфейс
        } else {
            setupPortraitUI();   // Показывает портретный интерфейс с каруселью
        }
    }

    //endregion

    //region Очистка ресурсов

    /**
     * Освобождает все ресурсы, используемые активностью.
     * Вызывается в onDestroy() для предотвращения утечек памяти.
     */
    private void cleanupResources() {
        // Очистка ссылок на UI элементы управления звуком (портретная ориентация)
        portraitVolumeUpText = null;    // Кнопка увеличения громкости
        portraitVolumeDownText = null;  // Кнопка уменьшения громкости
        portraitVolumeText = null;      // Текстовый индикатор громкости

        // Очистка данных сотрудников для портретного режима
        if (portraitEmployees != null) {
            portraitEmployees.clear();   // Удаление всех элементов из списка
            portraitEmployees = null;    // Освобождение ссылки на список
        }

        portraitAdapter = null;  // Освобождение адаптера ViewPager

        // Очистка кнопок ориентации
        landscapeOrientationButton = null;
        portraitOrientationButton = null;

        // Остановка и очистка менеджера автоскролла (ландшафтный режим)
        if (autoScrollManager != null) {
            autoScrollManager.stopAutoScroll();    // Остановка текущего скролла
            autoScrollManager.cleanupDebug();      // Удаление отладочных элементов
            autoScrollManager.cleanupAll();        // Освобождение всех ресурсов
        }

        // Удаление временного файла данных сеанса
        deleteSeanceDataFile();

        // Очистка менеджеров ресурсоемких компонентов
        if (soundManager != null) {
            soundManager.cleanup();  // Остановка и освобождение MediaPlayer
        }

        if (dataDisplayManager != null) {
            dataDisplayManager.cleanup();  // Очистка данных отображения
        }

        // Очистка таймера автоскролла для портретного режима
        if (portraitAutoScrollHandler != null && portraitAutoScrollRunnable != null) {
            portraitAutoScrollHandler.removeCallbacks(portraitAutoScrollRunnable);  // Удаление задач
            portraitAutoScrollHandler = null;  // Освобождение обработчика
        }

        // Очистка портретного контейнера и его дочерних элементов
        if (portraitContainer != null) {
            portraitContainer.removeAllViews();  // Удаление всех View из контейнера
            portraitContainer = null;            // Освобождение ссылки на контейнер
        }
    }

    /**
     * Удаляет временный файл данных сеанса MainActivity.
     * Файл используется для передачи данных между активностями.
     */
    private void deleteSeanceDataFile() {
        try {
            String filename = "SeanceDataStorage_MainActivity.json";
            File file = new File(this.getFilesDir(), filename);
            if (file.exists()) {
                file.delete();  // Удаление файла если он существует
            }
        } catch (SecurityException e) {
            // Ошибка доступа к файлу (разрешения)
        } catch (Exception e) {
            // Общая ошибка при удалении файла
        }
    }

    /**
     * Навигация обратно в MainActivity с анимацией перехода.
     * Сохраняет текущее состояние перед переходом.
     */
    private void navigateToMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);  // Запуск MainActivity
        finish();  // Завершение текущей активности
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);  // Анимация перехода
    }

    //endregion

    //region Обработка смены ориентации

    /**
     * Обрабатывает изменение конфигурации экрана (поворот устройства).
     * Управляет плавным переходом между ландшафтным и портретным режимами.
     *
     * @param newConfig Новая конфигурация устройства
     */
    private void handleOrientationChange(android.content.res.Configuration newConfig) {
        int oldOrientation = currentOrientation;  // Запоминаем текущую ориентацию

        // Определяем новую ориентацию на основе конфигурации
        int newOrientation = (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;

        // Если ориентация изменилась
        if (oldOrientation != newOrientation) {
            // Фиксируем ориентацию после смены
            if (newOrientation == ORIENTATION_LANDSCAPE) {
                lockOrientationLandscape();
            } else {
                lockOrientationPortrait();
            }

            runOnUiThread(() -> {
                hideCurrentContainer(oldOrientation);            // Скрываем текущий контейнер
                showNewContainer(newOrientation);                // Показываем новый контейнер
                updateSoundControlsForOrientation(newOrientation);  // Обновляем элементы управления звуком
            });
        }

        currentOrientation = newOrientation;  // Обновляем текущую ориентацию

        // Обновляем состояние автоскролла для новой ориентации
        if (autoScrollManager != null) {
            autoScrollManager.setOrientation(currentOrientation);
        }
    }

    /**
     * Скрывает текущий контейнер при смене ориентации.
     * Используется для плавного перехода между режимами.
     *
     * @param oldOrientation Предыдущая ориентация экрана
     */
    private void hideCurrentContainer(int oldOrientation) {
        if (oldOrientation == ORIENTATION_LANDSCAPE) {
            // Была ландшафтная ориентация - скрываем ландшафтный контейнер
            if (uiManager != null && uiManager.getContainer() != null &&
                    uiManager.getContainer().getVisibility() == View.VISIBLE) {
                uiManager.getContainer().setAlpha(0f);        // Устанавливаем прозрачность
                uiManager.getContainer().setVisibility(View.GONE);  // Скрываем контейнер
            }
        } else {
            // Была портретная ориентация - скрываем портретный контейнер
            if (portraitContainer != null && portraitContainer.getVisibility() == View.VISIBLE) {
                portraitContainer.setAlpha(0f);        // Устанавливаем прозрачность
                portraitContainer.setVisibility(View.GONE);  // Скрываем контейнер
            }
        }
    }

    /**
     * Обновляет элементы управления звуком в зависимости от текущей ориентации.
     * Элементы управления располагаются по-разному в ландшафтном и портретном режимах.
     *
     * @param orientation Текущая ориентация экрана
     */
    private void updateSoundControlsForOrientation(int orientation) {
        if (orientation == ORIENTATION_LANDSCAPE) {
            updateSoundControlsForLandscape();  // Настройка для ландшафта
        } else {
            updateSoundControlsForPortrait();   // Настройка для портрета
        }
    }

    //endregion

    //region Отображение UI по ориентации

    /**
     * Показывает новый контейнер соответствующей ориентации.
     * Выполняет подготовку и отображение интерфейса для новой ориентации.
     *
     * @param newOrientation Новая ориентация экрана
     */
    private void showNewContainer(int newOrientation) {
        if (newOrientation == ORIENTATION_LANDSCAPE) {
            // Переход в ландшафтный режим
            ensureLandscapeContainerClean();  // Очистка контейнера от портретных элементов
            showLandscapeContainer();         // Показ ландшафтного интерфейса
        } else {
            // Переход в портретный режим
            removeExistingPortraitSoundControls();  // Удаление старых элементов управления
            ensurePortraitContainer();              // Создание/проверка портретного контейнера
            createPortraitSoundControls();          // Создание элементов управления звуком
            showPortraitContainer();                // Показ портретного интерфейса
        }
    }

    /**
     * Показывает ландшафтный контейнер с анимацией появления.
     * Настраивает автоскролл и элементы управления звуком.
     */
    private void showLandscapeContainer() {
        if (uiManager != null && uiManager.getContainer() != null) {
            // Устанавливаем ландшафтный контейнер как content view если он еще не установлен
            if (getContentView() != uiManager.getContainer()) {
                setContentView(uiManager.getContainer());
            }

            uiManager.getContainer().setVisibility(View.VISIBLE);  // Делаем видимым
            uiManager.getContainer().setAlpha(0f);                 // Начальная прозрачность

            // Анимация плавного появления за 1.5 секунды
            uiManager.getContainer().animate()
                    .alpha(1f)  // Плавное увеличение прозрачности до 100%
                    .setDuration(1500)  // Длительность анимации
                    .setStartDelay(100)  // Задержка перед началом
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())  // Плавное замедление
                    .start();

            updateSoundControlsForLandscape();  // Настройка элементов управления звуком
            setupLandscapeAutoScroll();         // Настройка автоскролла

            Log.d("TeamShowActivity", "Ландшафтный UI показан с анимацией");
        }
    }

    /**
     * Показывает портретный контейнер с анимацией появления.
     * Отображает карусель сотрудников в портретном режиме.
     */
    private void showPortraitContainer() {
        // Устанавливаем портретный контейнер как content view если он еще не установлен
        if (getContentView() != portraitContainer) {
            setContentView(portraitContainer);
        }

        portraitContainer.setVisibility(View.VISIBLE);  // Делаем видимым
        portraitContainer.setAlpha(0f);                 // Начальная прозрачность

        // Анимация плавного появления за 1.5 секунды
        portraitContainer.animate()
                .alpha(1f)  // Плавное увеличение прозрачности до 100%
                .setDuration(1500)  // Длительность анимации
                .setStartDelay(100)  // Задержка перед началом
                .setInterpolator(new android.view.animation.DecelerateInterpolator())  // Плавное замедление
                .start();

        updateSoundControlsForPortrait();  // Настройка элементов управления звуком

        Log.d("TeamShowActivity", "Портретный UI показан с анимацией");
    }

    /**
     * Настраивает автоскролл для ландшафтного режима.
     * Запускает мониторинг касаний и планирует начальное центрирование.
     */
    private void setupLandscapeAutoScroll() {
        if (autoScrollManager != null) {
            autoScrollManager.setOrientation(ORIENTATION_LANDSCAPE);  // Указываем ориентацию
            autoScrollManager.setupTouchAndScrollMonitoring();        // Настраиваем обработку

            ScrollView scrollView = uiManager.getMainScrollView();
            if (scrollView != null) {
                // Планируем начальное центрирование с небольшой задержкой
                scrollView.postDelayed(() -> {
                    if (autoScrollManager != null) {
                        autoScrollManager.scheduleInitialCentering(scrollView);
                    }
                }, 300);  // Задержка 300мс для полной отрисовки
            }
        }
    }

    /**
     * Настраивает ландшафтный пользовательский интерфейс при запуске.
     * Устанавливает контейнер, анимацию и элементы управления.
     */
    private void setupLandscapeUI() {
        Log.d("TeamShowActivity", "setupLandscapeUI вызван");

        if (uiManager != null && uiManager.getContainer() != null) {
            setContentView(uiManager.getContainer());  // Установка корневого View
            uiManager.getContainer().setVisibility(View.VISIBLE);  // Делаем видимым
            uiManager.getContainer().setAlpha(0f);                 // Начальная прозрачность

            // Анимация плавного появления
            uiManager.getContainer().animate()
                    .alpha(1f)
                    .setDuration(1500)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

            Log.d("TeamShowActivity", "Ландшафтный UI установлен");
        }

        currentViewType = VIEW_LANDSCAPE;  // Устанавливаем тип отображения
        updateSoundControlsForLandscape(); // Настраиваем элементы управления звуком
        setupLandscapeAutoScroll();        // Настраиваем автоскролл
    }

    /**
     * Настраивает портретный пользовательский интерфейс при запуске.
     * Создает контейнер, ViewPager с каруселью сотрудников и элементы управления.
     */
    private void setupPortraitUI() {
        Log.d("TeamShowActivity", "setupPortraitUI вызван");

        ensurePortraitContainer();              // Создание/проверка портретного контейнера
        removeExistingPortraitSoundControls();  // Удаление старых элементов управления звуком
        createPortraitSoundControls();          // Создание новых элементов управления звуком

        setContentView(portraitContainer);      // Установка портретного контейнера как корневого
        portraitContainer.setVisibility(View.VISIBLE);  // Делаем видимым
        portraitContainer.setAlpha(0f);                 // Начальная прозрачность

        // Анимация плавного появления
        portraitContainer.animate()
                .alpha(1f)
                .setDuration(1500)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        setupInfiniteViewPager();            // Настройка ViewPager с бесконечной прокруткой
        updateSoundControlsForPortrait();    // Настройка элементов управления звуком

        Log.d("PortraitUI", "portraitContainer установлен как content view");
        currentViewType = VIEW_PORTRAIT;     // Устанавливаем тип отображения
    }

    //endregion

    //region Портретный ViewPager

    /**
     * Настраивает ViewPager2 для портретного режима с бесконечной циклической прокруткой.
     * Создает адаптер, устанавливает начальную позицию в середине "бесконечного" списка
     * и настраивает слушатели для управления автоскроллом.
     */
    private void setupInfiniteViewPager() {
        // Проверка наличия необходимых компонентов
        if (portraitPager != null && portraitEmployees != null && !portraitEmployees.isEmpty()) {
            // Создание адаптера только если он еще не создан
            if (portraitAdapter == null) {
                portraitAdapter = new PortraitPagerAdapter(portraitEmployees);
                portraitPager.setAdapter(portraitAdapter);  // Установка адаптера в ViewPager
                portraitPager.setVisibility(View.VISIBLE);  // Делаем ViewPager видимым

                // Устанавливаем начальную позицию в середине "бесконечного" списка
                // LOOP_MULTIPLIER = 1000, поэтому делим пополам для середины
                int startPosition = portraitEmployees.size() * (PortraitPagerAdapter.LOOP_MULTIPLIER / 2);
                portraitPager.setCurrentItem(startPosition, false);  // Без анимации

                Log.d("PortraitUI", "ViewPager инициализирован с бесконечной прокруткой");

                setupViewPagerListeners();   // Настройка слушателей событий ViewPager
                setupPortraitAutoScroll();   // Запуск автоскролла
            }
        } else {
            Log.w("PortraitUI", "Не удалось настроить ViewPager");
        }
    }

    /**
     * Настраивает слушатели событий ViewPager для обработки переключения страниц
     * и управления автоскроллом при ручном взаимодействии пользователя.
     */
    private void setupViewPagerListeners() {
        portraitPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            /**
             * Вызывается при изменении выбранной страницы.
             * Рассчитывает реальную позицию в списке сотрудников (с учетом бесконечной прокрутки).
             *
             * @param position Текущая позиция в ViewPager (может быть больше размера списка)
             */
            @Override
            public void onPageSelected(int position) {
                // Рассчитываем реальную позицию: position % размер_списка
                currentPortraitPosition = position % portraitEmployees.size();
                Log.d("PortraitUI", "Страница изменена на: " + position);
            }

            /**
             * Вызывается при изменении состояния прокрутки.
             * Управляет автоскроллом: приостанавливает при ручном перелистывании,
             * возобновляет при завершении.
             *
             * @param state Текущее состояние прокрутки
             */
            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    // Пользователь начал перелистывать - приостанавливаем автоскролл
                    pausePortraitAutoScroll();
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    // Прокрутка завершена - сбрасываем таймер автоскролла
                    resetPortraitAutoScroll();
                }
            }
        });
    }

    //endregion

    //region Управление ориентацией

    /**
     * Обновляет текущую ориентацию экрана на основе системной конфигурации.
     * Используется для определения, в каком режиме показывать интерфейс.
     */
    private void updateCurrentOrientation() {
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            currentOrientation = ORIENTATION_LANDSCAPE;  // Ландшафтная ориентация
        } else {
            currentOrientation = ORIENTATION_PORTRAIT;   // Портретная ориентация
        }
    }

    /**
     * Блокирует ориентацию в ландшафтном режиме.
     */
    private void lockOrientationLandscape() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    /**
     * Блокирует ориентацию в портретном режиме.
     */
    private void lockOrientationPortrait() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    /**
     * Переключает на портретную ориентацию.
     */
    public void switchToPortraitOrientation() {
        lockOrientationPortrait();
        currentOrientation = ORIENTATION_PORTRAIT;
        handleOrientationChange(getResources().getConfiguration());
    }

    /**
     * Переключает на ландшафтную ориентацию.
     */
    public void switchToLandscapeOrientation() {
        lockOrientationLandscape();
        currentOrientation = ORIENTATION_LANDSCAPE;
        handleOrientationChange(getResources().getConfiguration());
    }

    /**
     * Получает текущий корневой View активности.
     * Используется для проверки, какой контейнер сейчас установлен как content view.
     *
     * @return Текущий корневой View или null если не найден
     */
    private View getContentView() {
        ViewGroup contentView = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
        if (contentView != null && contentView.getChildCount() > 0) {
            return contentView.getChildAt(0);  // Первый дочерний элемент - наш контейнер
        }
        return null;
    }

    /**
     * Конвертирует значения из density-independent pixels (dp) в пиксели (px).
     * Учитывает плотность экрана устройства для корректного отображения на разных устройствах.
     *
     * @param dp Значение в dp для конвертации
     * @return Значение в пикселях
     */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    //endregion

    //region Портретный контейнер и элементы управления

    /**
     * Создает портретный контейнер если он еще не существует.
     * Контейнер содержит ViewPager для отображения карусели сотрудников.
     */
    private void ensurePortraitContainer() {
        if (portraitContainer == null) {
            Log.d("TeamShowActivity", "Создаем портретный контейнер");

            // Создание основного контейнера - FrameLayout на весь экран
            portraitContainer = new FrameLayout(this);
            portraitContainer.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            portraitContainer.setBackgroundColor(Color.BLACK);  // Черный фон

            // Создание ViewPager2 для отображения сотрудников в портретном режиме
            portraitPager = new ViewPager2(this);
            portraitPager.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            portraitPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);  // Горизонтальная прокрутка
            portraitContainer.addView(portraitPager);  // Добавление ViewPager в контейнер
        }
    }

    /**
     * Удаляет старые элементы управления звуком из портретного контейнера.
     * Используется при повторном создании элементов или очистке.
     */
    private void removeExistingPortraitSoundControls() {
        if (portraitContainer == null) return;

        // Поиск контейнера с элементами управления звуком по тегу
        View soundControls = portraitContainer.findViewWithTag("portrait_sound_controls");
        if (soundControls != null && soundControls.getParent() != null) {
            ((ViewGroup) soundControls.getParent()).removeView(soundControls);  // Удаление из родителя
        }

        // Сброс ссылок на элементы управления
        portraitVolumeUpText = null;
        portraitVolumeDownText = null;
        portraitVolumeText = null;
    }

    /**
     * Создает элементы управления звуком для портретной ориентации.
     * Размещает кнопки и индикатор громкости в правом нижнем углу.
     */
    private void createPortraitSoundControls() {
        // Проверка: если контейнер не существует или элементы уже созданы - выходим
        if (portraitContainer == null ||
                portraitContainer.findViewWithTag("portrait_sound_controls") != null) {
            return;
        }

        FrameLayout soundControlsContainer = createSoundControlsContainer();
        createVolumeControls(soundControlsContainer);
        createPortraitOrientationButton(soundControlsContainer);
        addSoundControlsToContainer(soundControlsContainer);

        updatePortraitVolumeText();               // Обновление текста текущей громкости
        setPortraitSoundControlsVisibility(false); // Скрываем элементы по умолчанию
    }

    /** Создаем кнопку переключения ориентации для портрета
     * @param container
     */
    private void createPortraitOrientationButton(FrameLayout container) {
        int bottomMargin = dpToPx(48);
        int leftMargin = dpToPx(16);  // Отступ слева

        portraitOrientationButton = new TextView(this);
        portraitOrientationButton.setText("КОМАНДА");
        portraitOrientationButton.setTextSize(16);
        portraitOrientationButton.setTextColor(Color.WHITE);
        portraitOrientationButton.setAlpha(0.5f);
        portraitOrientationButton.setTypeface(null, Typeface.BOLD);
        portraitOrientationButton.setGravity(Gravity.CENTER);
        portraitOrientationButton.setPadding(dpToPx(20), dpToPx(4), dpToPx(8), dpToPx(4));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40),
                Gravity.BOTTOM | Gravity.START  // Левая нижняя часть
        );
        params.setMargins(leftMargin, 0, 0, bottomMargin);
        portraitOrientationButton.setLayoutParams(params);

        portraitOrientationButton.setOnClickListener(v -> switchToLandscapeOrientation());
        portraitOrientationButton.setTag("portrait_orientation_button");
        container.addView(portraitOrientationButton);
    }


    /**
     * Создает контейнер для размещения элементов управления звуком.
     * Контейнер позиционируется в правом нижнем углу экрана.
     *
     * @return FrameLayout контейнер для элементов управления
     */
    private FrameLayout createSoundControlsContainer() {
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END  // Правый нижний угол
        );
        containerParams.setMargins(0, 0, 0, 0);
        container.setLayoutParams(containerParams);
        container.setTag("portrait_sound_controls");  // Уникальный тег для идентификации
        return container;
    }

    /**
     * Создает кнопки и текстовый индикатор для управления громкостью.
     * Располагает элементы с определенными отступами для портретной ориентации.
     *
     * @param container Контейнер для добавления созданных элементов
     */
    private void createVolumeControls(FrameLayout container) {
        int bottomMargin = dpToPx(48);  // Отступ от нижнего края (аналогично ландшафту)
        int rightMargin = dpToPx(16);   // Отступ от правого края (аналогично ландшафту)

        // Кнопка уменьшения громкости - самая правая
        portraitVolumeDownText = createVolumeButton("ЗВУК -", rightMargin, bottomMargin);
        portraitVolumeDownText.setOnClickListener(v -> handleVolumeDecrease());
        portraitVolumeDownText.setTag("portrait_volume_down");
        container.addView(portraitVolumeDownText);

        // Кнопка увеличения громкости - слева от кнопки уменьшения
        portraitVolumeUpText = createVolumeButton("ЗВУК +", rightMargin + dpToPx(85), bottomMargin);
        portraitVolumeUpText.setOnClickListener(v -> handleVolumeIncrease());
        portraitVolumeUpText.setTag("portrait_volume_up");
        container.addView(portraitVolumeUpText);

        // Текстовый индикатор текущей громкости - самый левый
        portraitVolumeText = createVolumeText(rightMargin + dpToPx(170), bottomMargin);
        portraitVolumeText.setTag("portrait_volume_text");
        container.addView(portraitVolumeText);
    }

    /**
     * Создает кнопку для управления громкостью с заданными параметрами.
     *
     * @param text Текст на кнопке ("ЗВУК +" или "ЗВУК -")
     * @param rightMargin Отступ от правого края в пикселях
     * @param bottomMargin Отступ от нижнего края в пикселях
     * @return Настроенная TextView кнопка
     */
    private TextView createVolumeButton(String text, int rightMargin, int bottomMargin) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAlpha(0.5f);  // Полупрозрачность для ненавязчивого отображения
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40),  // Фиксированная высота 40dp
                Gravity.BOTTOM | Gravity.END
        );
        params.setMargins(0, 0, rightMargin, bottomMargin);
        button.setLayoutParams(params);

        return button;
    }

    /**
     * Создает текстовый индикатор для отображения текущей громкости.
     *
     * @param rightMargin Отступ от правого края в пикселях
     * @param bottomMargin Отступ от нижнего края в пикселях
     * @return Настроенная TextView для отображения громкости
     */
    private TextView createVolumeText(int rightMargin, int bottomMargin) {
        TextView textView = new TextView(this);
        textView.setText("");  // Начально пустой текст
        textView.setTextSize(16);
        textView.setTextColor(Color.WHITE);
        textView.setAlpha(0f);  // Начально невидим
        textView.setTypeface(null, Typeface.BOLD);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dpToPx(120),  // Фиксированная ширина 120dp
                dpToPx(40),   // Фиксированная высота 40dp
                Gravity.BOTTOM | Gravity.END
        );
        params.setMargins(0, 0, rightMargin, bottomMargin);
        textView.setLayoutParams(params);

        return textView;
    }

    /**
     * Добавляет контейнер с элементами управления звуком в портретный контейнер.
     * Удаляет старый контейнер если он существует.
     *
     * @param soundControlsContainer Контейнер с элементами управления
     */
    private void addSoundControlsToContainer(FrameLayout soundControlsContainer) {
        // Поиск и удаление старого контейнера если существует
        View oldContainer = portraitContainer.findViewWithTag("portrait_sound_controls");
        if (oldContainer != null && oldContainer.getParent() != null) {
            ((ViewGroup) oldContainer.getParent()).removeView(oldContainer);
        }

        portraitContainer.addView(soundControlsContainer);  // Добавление нового контейнера
    }

    /**
     * Управляет видимостью элементов управления звуком в портретной ориентации.
     *
     * @param visible true - показать элементы, false - скрыть
     */
    private void setPortraitSoundControlsVisibility(boolean visible) {
        if (portraitVolumeUpText != null) {
            portraitVolumeUpText.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (portraitVolumeDownText != null) {
            portraitVolumeDownText.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (portraitVolumeText != null) {
            portraitVolumeText.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Обновляет текстовый индикатор текущей громкости в портретной ориентации.
     * Форматирует значение громкости: "МАКСИМУМ", "БЕЗ ЗВУКА" или "X%".
     */
    private void updatePortraitVolumeText() {
        if (portraitVolumeText != null && soundManager != null) {
            int volume = soundManager.getVolume();  // Получение текущей громкости
            String text = volume == 100 ? "МАКСИМУМ" :
                    volume == 0 ? "БЕЗ ЗВУКА" :
                            volume + "%";  // Форматирование текста
            portraitVolumeText.setText(text);
        }
    }

    //endregion

    //region Управление звуком

    /**
     * Обновляет элементы управления звуком для ландшафтной ориентации.
     * Очищает контейнер от портретных элементов и настраивает ландшафтные.
     */
    private void updateSoundControlsForLandscape() {
        ensureLandscapeContainerClean();  // Удаление портретных элементов из ландшафтного контейнера

        if (uiManager != null) {
            // Настройка положения каждого элемента управления
            updateLandscapeSoundControl(uiManager.getVolumeUpText(), dpToPx(85));     // "ЗВУК +"
            updateLandscapeSoundControl(uiManager.getVolumeDownText(), 0);            // "ЗВУК -"
            updateLandscapeSoundControl(uiManager.getVolumeText(), dpToPx(170));      // Текст громкости

            // Показываем кнопку "ЛИЧНОСТИ" в ландшафте
            if (uiManager.getOrientationButton() != null) {
                uiManager.getOrientationButton().setVisibility(View.VISIBLE);
            }
        }

        // Скрываем портретную кнопку ориентации
        if (portraitOrientationButton != null) {
            portraitOrientationButton.setVisibility(View.GONE);
        }

        setPortraitSoundControlsVisibility(false);  // Скрытие портретных элементов
    }

    /**
     * Обновляет параметры одного элемента управления звуком в ландшафтной ориентации.
     *
     * @param control Элемент управления (TextView)
     * @param rightMargin Отступ от правого края в пикселях
     */
    private void updateLandscapeSoundControl(TextView control, int rightMargin) {
        if (control != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) control.getLayoutParams();
            params.gravity = Gravity.BOTTOM | Gravity.END;  // Правый нижний угол
            params.setMargins(0, 0, rightMargin, 0);        // Установка отступа
            control.setLayoutParams(params);
            control.setVisibility(View.VISIBLE);
            // Текст громкости невидим по умолчанию, кнопки - полупрозрачные
            control.setAlpha(control == uiManager.getVolumeText() ? 0f : 0.5f);
        }
    }

    /**
     * Очищает ландшафтный контейнер от портретных элементов управления звуком.
     * Предотвращает дублирование элементов при переключении ориентаций.
     */
    private void ensureLandscapeContainerClean() {
        if (uiManager != null && uiManager.getContainer() != null) {
            View portraitSoundControls = uiManager.getContainer().findViewWithTag("portrait_sound_controls");
            if (portraitSoundControls != null && portraitSoundControls.getParent() != null) {
                ((ViewGroup) portraitSoundControls.getParent()).removeView(portraitSoundControls);
            }
        }
    }

    /**
     * Обновляет элементы управления звуком для портретной ориентации.
     * Скрывает ландшафтные элементы и показывает/настраивает портретные.
     */
    private void updateSoundControlsForPortrait() {
        // Скрытие ландшафтных элементов управления
        hideLandscapeSoundControls();

        // Скрываем ландшафтную кнопку ориентации
        if (uiManager != null && uiManager.getOrientationButton() != null) {
            uiManager.getOrientationButton().setVisibility(View.GONE);
        }

        // Создание портретных элементов если они еще не созданы
        if (portraitContainer != null) {
            if (portraitVolumeUpText == null || portraitVolumeDownText == null) {
                createPortraitSoundControls();
            }

            // Показываем портретную кнопку ориентации если существует
            if (portraitOrientationButton != null) {
                portraitOrientationButton.setVisibility(View.VISIBLE);
            }

        }

        // Настройка и отображение портретных элементов если они существуют
        if (portraitVolumeDownText != null && portraitVolumeUpText != null && portraitVolumeText != null) {
            setupPortraitSoundControls();          // Настройка параметров элементов
            setPortraitSoundControlsVisibility(true);  // Отображение элементов
        }
    }

    /**
     * Скрывает элементы управления звуком ландшафтной ориентации.
     */
    private void hideLandscapeSoundControls() {
        if (uiManager != null) {
            setControlVisibility(uiManager.getVolumeUpText(), View.GONE);
            setControlVisibility(uiManager.getVolumeDownText(), View.GONE);
            setControlVisibility(uiManager.getVolumeText(), View.GONE);

            setControlVisibility(uiManager.getOrientationButton(), View.GONE);
        }
    }

    /**
     * Устанавливает видимость элемента управления звуком.
     *
     * @param control Элемент управления (TextView)
     * @param visibility Видимость (View.VISIBLE или View.GONE)
     */
    private void setControlVisibility(TextView control, int visibility) {
        if (control != null) {
            control.setVisibility(visibility);
        }
    }

    /**
     * Настраивает позиции и параметры портретных элементов управления звуком.
     * Устанавливает одинаковые отступы как в ландшафтном режиме для единообразия.
     */
    private void setupPortraitSoundControls() {
        int bottomMargin = dpToPx(48);  // Отступ от нижнего края
        int rightMargin = dpToPx(16);   // Базовый отступ от правого края

        // Настройка каждого элемента с соответствующими отступами и прозрачностью
        setupPortraitSoundControl(portraitVolumeDownText, rightMargin, bottomMargin, 0.5f);           // "ЗВУК -"
        setupPortraitSoundControl(portraitVolumeUpText, rightMargin + dpToPx(85), bottomMargin, 0.5f); // "ЗВУК +"
        setupPortraitSoundControl(portraitVolumeText, rightMargin + dpToPx(140), bottomMargin, 0f);    // Текст громкости
    }

    /**
     * Настраивает параметры одного портретного элемента управления звуком.
     *
     * @param control Элемент управления
     * @param rightMargin Отступ от правого края
     * @param bottomMargin Отступ от нижнего края
     * @param alpha Прозрачность элемента (0.0 - 1.0)
     */
    private void setupPortraitSoundControl(TextView control, int rightMargin, int bottomMargin, float alpha) {
        if (control != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) control.getLayoutParams();
            params.gravity = Gravity.BOTTOM | Gravity.END;  // Правый нижний угол
            // Для текста громкости - фиксированная ширина, для кнопок - по содержимому
            params.width = control == portraitVolumeText ? dpToPx(120) : FrameLayout.LayoutParams.WRAP_CONTENT;
            params.height = dpToPx(40);  // Фиксированная высота
            params.setMargins(0, 0, rightMargin, bottomMargin);  // Установка отступов
            control.setLayoutParams(params);
            control.setAlpha(alpha);  // Установка прозрачности

            if (control == portraitVolumeText) {
                control.setGravity(Gravity.CENTER);  // Центрирование текста
            }
        }
    }

    /**
     * Показывает текстовый индикатор громкости временно в обеих ориентациях.
     * Используется при изменении громкости для подтверждения действия пользователя.
     */
    public void showVolumeTextTemporarilyInBothOrientations() {
        // Показ в ландшафтной ориентации
        if (uiManager != null) {
            uiManager.showVolumeTextTemporarily();
        }

        // Показ в портретной ориентации с анимацией
        if (portraitVolumeText != null) {
            portraitVolumeText.animate().cancel();  // Отмена текущей анимации
            portraitVolumeText.animate()
                    .alpha(0.5f)           // Быстрое появление
                    .setDuration(150)
                    .withEndAction(() -> {
                        // Через 1.5 секунды начинаем плавное исчезновение
                        portraitVolumeText.postDelayed(() -> {
                            portraitVolumeText.animate()
                                    .alpha(0f)     // Медленное исчезновение
                                    .setDuration(800)
                                    .start();
                        }, 1500);
                    })
                    .start();
        }
    }

    /**
     * Обработчик уменьшения громкости.
     * Вызывается при нажатии кнопки "ЗВУК -" в портретной ориентации.
     */
    public void handleVolumeDecrease() {
        if (soundManager != null) {
            soundManager.decreaseVolume();  // Уменьшение громкости на 10 единиц
        }
        updatePortraitVolumeText();                        // Обновление текста громкости
        showVolumeTextTemporarilyInBothOrientations();     // Показ подтверждения
        settingsManager.saveSettings(fileStorage, soundManager);  // Сохранение настроек
    }

    /**
     * Обработчик увеличения громкости.
     * Вызывается при нажатии кнопки "ЗВУК +" в портретной ориентации.
     */
    public void handleVolumeIncrease() {
        if (soundManager != null) {
            soundManager.increaseVolume();  // Увеличение громкости на 10 единиц
        }
        updatePortraitVolumeText();                        // Обновление текста громкости
        showVolumeTextTemporarilyInBothOrientations();     // Показ подтверждения
        settingsManager.saveSettings(fileStorage, soundManager);  // Сохранение настроек
    }

    //endregion

    //region Портретный автоскролл

    /**
     * Настраивает автоматическую прокрутку ViewPager в портретном режиме.
     * Прокручивает карусель сотрудников каждые 8 секунд.
     */
    private void setupPortraitAutoScroll() {
        // Создание Handler для работы с UI потоком
        if (portraitAutoScrollHandler == null) {
            portraitAutoScrollHandler = new Handler(Looper.getMainLooper());
        }

        // Создание задачи для автоматической прокрутки
        portraitAutoScrollRunnable = new Runnable() {
            @Override
            public void run() {
                // Проверка наличия данных и необходимости прокрутки
                if (portraitPager != null && portraitEmployees != null && portraitEmployees.size() > 1) {
                    int nextPosition = portraitPager.getCurrentItem() + 1;  // Следующая позиция
                    portraitPager.setCurrentItem(nextPosition, true);       // Прокрутка с анимацией

                    // Планирование следующей прокрутки через 8 секунд
                    portraitAutoScrollHandler.postDelayed(this, PORTRAIT_AUTO_SCROLL_DELAY);
                }
            }
        };

        // Запуск первой прокрутки через 8 секунд
        portraitAutoScrollHandler.postDelayed(portraitAutoScrollRunnable, PORTRAIT_AUTO_SCROLL_DELAY);
    }

    /**
     * Сбрасывает таймер автоскролла.
     * Вызывается после ручного перелистывания для отсчета новых 8 секунд.
     */
    private void resetPortraitAutoScroll() {
        if (portraitAutoScrollHandler != null && portraitAutoScrollRunnable != null) {
            portraitAutoScrollHandler.removeCallbacks(portraitAutoScrollRunnable);  // Удаление текущей задачи
            portraitAutoScrollHandler.postDelayed(portraitAutoScrollRunnable, PORTRAIT_AUTO_SCROLL_DELAY);  // Новая задача
        }
    }

    /**
     * Приостанавливает автоскролл.
     * Вызывается когда пользователь начинает перелистывать вручную.
     */
    private void pausePortraitAutoScroll() {
        if (portraitAutoScrollHandler != null && portraitAutoScrollRunnable != null) {
            portraitAutoScrollHandler.removeCallbacks(portraitAutoScrollRunnable);  // Удаление запланированной задачи
        }
    }

    //endregion

    //region Работа с фотографиями и данными

    /**
     * Загружает фотографию сотрудника для портретного режима.
     * Пытается загрузить из указанного пути, при неудаче использует резервные варианты.
     *
     * @param photoPath Путь к файлу фотографии
     * @param imageView ImageView для отображения фотографии
     * @param employee Данные сотрудника для создания резервного изображения с инициалами
     */
    private void loadPortraitPhoto(String photoPath, ImageView imageView, JSONObject employee) {
        try {
            Log.d("PortraitPhoto", "Попытка загрузить: " + photoPath);

            File photoFile = new File(photoPath);

            if (photoFile.exists()) {
                Log.d("PortraitPhoto", "Файл найден: " + photoFile.getAbsolutePath());

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;  // Без уменьшения размера для максимального качества
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;  // 32-битный формат

                Bitmap photoBitmap = BitmapFactory.decodeFile(photoPath, options);
                if (photoBitmap != null) {
                    imageView.setImageBitmap(photoBitmap);
                    Log.d("PortraitPhoto", "Фото загружено успешно");
                } else {
                    Log.w("PortraitPhoto", "Не удалось декодировать файл");
                    setPortraitInitialsBackground(imageView, employee);  // Резервный вариант
                }
            } else {
                Log.w("PortraitPhoto", "Файл НЕ существует: " + photoFile.getAbsolutePath());

                // Попытка найти файл в директории приложения (team)
                File appDir = getFilesDir();
                File teamDir = new File(appDir, "team");
                String fileName = new File(photoPath).getName();
                File teamFile = new File(teamDir, fileName);

                Log.d("PortraitPhoto", "Пробуем найти в team: " + teamFile.getAbsolutePath());

                if (teamFile.exists()) {
                    // Повторная попытка загрузки из директории приложения
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 1;
                    Bitmap photoBitmap = BitmapFactory.decodeFile(teamFile.getAbsolutePath(), options);
                    if (photoBitmap != null) {
                        imageView.setImageBitmap(photoBitmap);
                        Log.d("PortraitPhoto", "Фото найдено в team директории");
                    } else {
                        setPortraitInitialsBackground(imageView, employee);  // Резервный вариант
                    }
                } else {
                    setPortraitInitialsBackground(imageView, employee);  // Резервный вариант
                }
            }
        } catch (Exception e) {
            Log.e("PortraitPhoto", "Ошибка загрузки фото: " + e.getMessage());
            setPortraitInitialsBackground(imageView, employee);  // Резервный вариант при любой ошибке
        }
    }

    /**
     * Создает изображение с инициалами сотрудника на цветном фоне.
     * Используется когда фотография сотрудника недоступна.
     *
     * @param imageView ImageView для установки изображения
     * @param employee Данные сотрудника для генерации инициалов и цвета
     */
    private void setPortraitInitialsBackground(ImageView imageView, JSONObject employee) {
        int color = Color.parseColor("#333333");  // Цвет по умолчанию (темно-серый)
        String initials = "?";  // Инициалы по умолчанию

        if (employee != null) {
            try {
                // Генерация уникального цвета на основе имени сотрудника
                String name = employee.getString("first_name") + employee.getString("second_name");
                int hash = name.hashCode();
                int r = (hash & 0xFF) % 128 + 100;  // Красный компонент (100-228)
                int g = ((hash >> 8) & 0xFF) % 128 + 100;  // Зеленый компонент
                int b = ((hash >> 16) & 0xFF) % 128 + 100;  // Синий компонент
                color = Color.rgb(r, g, b);

                // Извлечение первых букв имени и фамилии для инициалов
                String firstName = employee.optString("first_name", "");
                String secondName = employee.optString("second_name", "");
                if (!firstName.isEmpty() && !secondName.isEmpty()) {
                    initials = firstName.substring(0, 1) + secondName.substring(0, 1);  // Две буквы
                } else if (!firstName.isEmpty()) {
                    initials = firstName.substring(0, 1);  // Одна буква
                }
            } catch (JSONException e) {
                // Используем значения по умолчанию при ошибке
            }
        }

        // Создание квадратного bitmap максимального размера (по большей стороне экрана)
        int size = Math.max(getScreenWidth(), getScreenHeight());
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Рисование цветного фона
        Paint bgPaint = new Paint();
        bgPaint.setColor(color);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, size, size, bgPaint);

        // Настройка и рисование текста инициалов
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size / 4);  // Размер текста - 1/4 от размера изображения
        textPaint.setTextAlign(Paint.Align.CENTER);  // Центрирование по горизонтали
        textPaint.setAntiAlias(true);  // Сглаживание краев
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);  // Жирный шрифт

        // Расчет позиции текста для вертикального центрирования
        float x = size / 2f;
        float y = size / 2f - ((textPaint.descent() + textPaint.ascent()) / 2);
        canvas.drawText(initials, x, y, textPaint);

        imageView.setImageBitmap(bitmap);  // Установка созданного изображения
    }

    /**
     * Получает ширину экрана устройства в пикселях.
     *
     * @return Ширина экрана в пикселях
     */
    private int getScreenWidth() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        return metrics.widthPixels;
    }

    /**
     * Получает высоту экрана устройства в пикселях.
     *
     * @return Высота экрана в пикселях
     */
    private int getScreenHeight() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        return metrics.heightPixels;
    }

    /**
     * Подготавливает список сотрудников для портретного режима.
     * Создает упрощенную копию данных сотрудников (без должностей) в случайном порядке.
     *
     * @param allEmployees Полный список сотрудников из JSON
     * @return Список сотрудников для портретного режима в случайном порядке
     */
    public List<JSONObject> preparePortraitEmployees(JSONArray allEmployees) {
        List<JSONObject> portraitList = new ArrayList<>();

        if (allEmployees == null) {
            return portraitList;
        }

        // Подготовка случайного порядка отображения
        dataDisplayManager.prepareRandomEmployeeOrder(allEmployees.length());
        int[] shuffledIndices = dataDisplayManager.getShuffledEmployeeIndices();

        if (shuffledIndices == null) {
            return portraitList;
        }

        try {
            // Создание упрощенных объектов сотрудников в случайном порядке
            for (int index : shuffledIndices) {
                if (index < allEmployees.length()) {
                    JSONObject employee = allEmployees.getJSONObject(index);
                    JSONObject portraitEmployee = new JSONObject();
                    // Копируем только основные поля (без должности)
                    portraitEmployee.put("first_name", employee.optString("first_name", ""));
                    portraitEmployee.put("second_name", employee.optString("second_name", ""));
                    portraitEmployee.put("photo", employee.optString("photo", ""));
                    portraitEmployee.put("group", employee.optString("group", ""));
                    portraitList.add(portraitEmployee);
                }
            }
        } catch (JSONException e) {
            Log.e("DataDisplayManager", "Ошибка подготовки портретов", e);
        }

        return portraitList;
    }

    /**
     * Устанавливает данные сотрудников для портретного режима.
     * Обновляет адаптер ViewPager и настраивает начальную позицию.
     *
     * @param employees Список сотрудников для портретного режима
     */
    public void setPortraitEmployees(List<JSONObject> employees) {
        this.portraitEmployees = employees;

        Log.d("TeamShowActivity", "Установлено портретных сотрудников: " +
                (portraitEmployees != null ? portraitEmployees.size() : 0));

        // Обновление ViewPager если он готов и есть данные
        if (portraitPager != null && portraitEmployees != null && !portraitEmployees.isEmpty()) {
            runOnUiThread(() -> {
                if (portraitAdapter == null) {
                    // Первое создание адаптера
                    portraitAdapter = new PortraitPagerAdapter(portraitEmployees);
                    portraitPager.setAdapter(portraitAdapter);
                    portraitPager.setVisibility(View.VISIBLE);

                    // Установка начальной позиции в середине "бесконечного" списка
                    int startPosition = portraitEmployees.size() * (PortraitPagerAdapter.LOOP_MULTIPLIER / 2);
                    portraitPager.setCurrentItem(startPosition, false);

                    Log.d("TeamShowActivity", "Адаптер создан в setPortraitEmployees с бесконечной прокруткой");
                    Log.d("TeamShowActivity", "Начальная позиция: " + startPosition);

                } else {
                    // Обновление существующего адаптера
                    portraitAdapter.updateData(portraitEmployees);

                    // Обновление текущей позиции с сохранением видимой страницы
                    int currentRealPosition = portraitPager.getCurrentItem() % portraitEmployees.size();
                    int newPosition = portraitEmployees.size() * (PortraitPagerAdapter.LOOP_MULTIPLIER / 2)
                            + currentRealPosition;
                    portraitPager.setCurrentItem(newPosition, false);

                    Log.d("TeamShowActivity", "Адаптер обновлен в setPortraitEmployees с бесконечной прокруткой");
                }
            });
        } else {
            Log.w("TeamShowActivity", "ViewPager не готов");
        }
    }

    //endregion

    /**
     * Адаптер для ViewPager2, отображающего сотрудников в портретном режиме.
     * Реализует бесконечную циклическую прокрутку за счет умножения реального количества элементов.
     * Каждый элемент представляет собой фотографию сотрудника с именем внизу.
     */
    private class PortraitPagerAdapter extends RecyclerView.Adapter<PortraitPagerAdapter.ViewHolder> {
        private List<JSONObject> employees;  // Список сотрудников для отображения
        private static final int LOOP_MULTIPLIER = 1000;  // Множитель для создания эффекта бесконечной прокрутки

        /**
         * Конструктор адаптера.
         *
         * @param employees Список сотрудников в формате JSONObject
         */
        public PortraitPagerAdapter(List<JSONObject> employees) {
            this.employees = employees;
        }

        /**
         * Обновляет данные адаптера новым списком сотрудников.
         * Уведомляет ViewPager об изменении данных для перерисовки.
         *
         * @param newEmployees Новый список сотрудников
         */
        public void updateData(List<JSONObject> newEmployees) {
            this.employees = newEmployees;
            notifyDataSetChanged();  // Уведомление RecyclerView об изменении данных
            Log.d("PortraitAdapter", "Данные обновлены: " + employees.size() + " элементов");
        }

        /**
         * Создает новый ViewHolder для отображения элемента.
         * Вызывается RecyclerView когда нужен новый ViewHolder для отображения элемента.
         *
         * @param parent Родительский ViewGroup
         * @param viewType Тип View (не используется в этой реализации)
         * @return Новый ViewHolder
         */
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Log.d("PortraitAdapter", "onCreateViewHolder ВЫЗВАН");

            // Создание контейнера - FrameLayout на весь экран
            FrameLayout container = new FrameLayout(parent.getContext());
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            // Создание ImageView для фотографии сотрудника
            ImageView photoView = new ImageView(parent.getContext());
            photoView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);  // Масштабирование с обрезкой по центру
            container.addView(photoView);

            // Создание TextView для отображения имени сотрудника
            TextView nameTextView = new TextView(parent.getContext());
            FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL  // Расположение внизу по центру
            );
            nameParams.bottomMargin = dpToPx(100);  // Отступ от нижнего края 100dp
            nameTextView.setLayoutParams(nameParams);
            nameTextView.setTextColor(Color.WHITE);
            nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);  // Крупный текст 32sp
            nameTextView.setTypeface(null, Typeface.BOLD);  // Жирный шрифт
            nameTextView.setGravity(Gravity.CENTER);  // Центрирование текста
            // Тень для улучшения читаемости на любом фоне
            nameTextView.setShadowLayer(dpToPx(3), 0, 0, Color.BLACK);
            container.addView(nameTextView);

            // Создание и возврат ViewHolder с созданными View
            return new ViewHolder(container, photoView, nameTextView);
        }

        /**
         * Привязывает данные сотрудника к ViewHolder.
         * Вызывается RecyclerView для отображения данных в указанной позиции.
         *
         * @param holder ViewHolder для заполнения
         * @param position Позиция элемента в адаптере (с учетом множителя бесконечной прокрутки)
         */
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Log.d("PortraitAdapter", "onBindViewHolder позиция: " + position);

            // Проверка наличия данных
            if (employees == null || employees.isEmpty()) {
                Log.e("PortraitAdapter", "CRITICAL: employees пустой в onBindViewHolder!");
                holder.nameTextView.setText("Тест " + position);  // Заглушка при отсутствии данных
                setPortraitInitialsBackground(holder.photoView, null);  // Фон по умолчанию
                return;
            }

            // Расчет реальной позиции с учетом бесконечной прокрутки
            int realPosition = position % employees.size();
            JSONObject employee = employees.get(realPosition);

            // Привязка данных сотрудника к View
            holder.bind(employee);
        }

        /**
         * Возвращает общее количество элементов в адаптере.
         * Для создания эффекта бесконечной прокрутки умножает реальное количество на множитель.
         *
         * @return Общее количество элементов (реальное количество * LOOP_MULTIPLIER)
         */
        @Override
        public int getItemCount() {
            if (employees == null || employees.isEmpty()) {
                Log.w("PortraitAdapter", "employees пустой, возвращаем 0");
                return 0;
            }

            int count = employees.size() * LOOP_MULTIPLIER;
            Log.d("PortraitAdapter", "getItemCount() возвращает: " + count);
            return count;
        }

        /**
         * ViewHolder для хранения View элементов одного сотрудника.
         * Содержит ImageView для фотографии и TextView для имени.
         */
        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView photoView;      // View для отображения фотографии сотрудника
            TextView nameTextView;    // View для отображения имени сотрудника

            /**
             * Конструктор ViewHolder.
             *
             * @param itemView Корневой View элемента
             * @param photoView ImageView для фотографии
             * @param nameTextView TextView для имени
             */
            ViewHolder(View itemView, ImageView photoView, TextView nameTextView) {
                super(itemView);
                this.photoView = photoView;
                this.nameTextView = nameTextView;
            }

            /**
             * Привязывает данные сотрудника к View элемента.
             * Загружает фотографию и устанавливает имя.
             *
             * @employee Данные сотрудника в формате JSONObject
             */
            void bind(JSONObject employee) {
                try {
                    // Извлечение имени и фамилии из JSON
                    String firstName = employee.optString("first_name", "");
                    String secondName = employee.optString("second_name", "");
                    nameTextView.setText(firstName);  // Отображение только имени (без фамилии)

                    // Получение пути к фотографии
                    String photoPath = employee.optString("photo", "");
                    if (!photoPath.isEmpty()) {
                        // Загрузка фотографии из файла
                        loadPortraitPhoto(photoPath, photoView, employee);
                    } else {
                        // Если фото нет - создаем фон с инициалами
                        setPortraitInitialsBackground(photoView, employee);
                    }
                } catch (Exception e) {
                    // Обработка ошибок при отображении сотрудника
                    Log.e("PortraitAdapter", "Ошибка отображения сотрудника", e);
                    nameTextView.setText("Сотрудник");  // Текст по умолчанию
                    setPortraitInitialsBackground(photoView, null);  // Фон по умолчанию
                }
            }
        }
    }

    //region Внутренние классы

    /**
     * Менеджер для управления воспроизведением фоновой музыки.
     * Обеспечивает загрузку, воспроизведение, управление громкостью и очистку ресурсов MediaPlayer.
     * Реализует безопасную работу с аудио-ресурсами в контексте жизненного цикла Activity.
     */
    private class SoundManager {
        private MediaPlayer mediaPlayer;
        private int volume = 10; // Текущая громкость (0-100)
        private boolean initialized = false; // Флаг успешной инициализации

        /**
         * Инициализирует MediaPlayer с указанным аудиофайлом.
         * При повторном вызове выполняет cleanup предыдущего экземпляра.
         *
         * @param filePath Абсолютный путь к аудиофайлу
         * @return true - инициализация успешна, false - произошла ошибка
         */
        public boolean initialize(String filePath) {
            cleanup(); // Гарантируем очистку предыдущего состояния

            try {
                if (filePath != null && !filePath.isEmpty()) {
                    return initializeFromFile(filePath);
                }
                return false; // Пустой путь к файлу
            } catch (Exception e) {
                cleanup(); // Очищаем ресурсы при исключении
                return false;
            }
        }

        /**
         * Приватный метод создания и настройки MediaPlayer.
         * Выполняет асинхронную подготовку к воспроизведению.
         */
        private boolean initializeFromFile(String filePath) {
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(filePath);
                mediaPlayer.prepare(); // Блокирующий вызов, рекомендуется использовать prepareAsync()
                configureMediaPlayer();
                initialized = true;
                return true;
            } catch (Exception e) {
                // Не логируем здесь - логика обработки ошибок в вызывающем коде
                return false;
            }
        }

        /**
         * Настраивает базовые параметры MediaPlayer.
         * Устанавливает зацикливание и начальный уровень громкости.
         */
        private void configureMediaPlayer() {
            if (mediaPlayer != null) {
                mediaPlayer.setLooping(true); // Бесконечное воспроизведение
                setVolume(volume); // Применяем сохраненную громкость
            }
        }

        /**
         * Начинает воспроизведение аудио.
         * Проверяет состояние MediaPlayer перед запуском.
         */
        public void start() {
            if (mediaPlayer != null && !mediaPlayer.isPlaying() && initialized) {
                mediaPlayer.start();
            }
        }

        /**
         * Приостанавливает воспроизведение.
         * Сохраняет позицию для возможного возобновления.
         */
        public void pause() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        }

        /**
         * Освобождает все аудио-ресурсы.
         * Обязателен к вызову в onDestroy() активности для предотвращения утечек.
         */
        public void cleanup() {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop(); // Останавливаем воспроизведение перед освобождением
                }
                mediaPlayer.release(); // Освобождаем системные ресурсы
                mediaPlayer = null;
            }
            initialized = false; // Сбрасываем флаг инициализации
        }

        /**
         * Устанавливает уровень громкости.
         *
         * @param volume Уровень громкости (0-100). Значения за пределами диапазона обрезаются.
         */
        public void setVolume(int volume) {
            this.volume = Math.max(0, Math.min(100, volume)); // Ограничение диапазона
            if (mediaPlayer != null) {
                float volumeLevel = this.volume / 100.0f; // Преобразуем в формат MediaPlayer (0.0-1.0)
                mediaPlayer.setVolume(volumeLevel, volumeLevel); // Монофоническое воспроизведение
            }
        }

        /**
         * Увеличивает громкость на 10 единиц.
         * Выполняет автоматическое ограничение максимума.
         */
        public void increaseVolume() {
            setVolume(volume + 10);
        }

        /**
         * Уменьшает громкость на 10 единиц.
         * Выполняет автоматическое ограничение минимума.
         */
        public void decreaseVolume() {
            setVolume(volume - 10);
        }

        /**
         * @return Текущий уровень громкости (0-100)
         */
        public int getVolume() {
            return volume;
        }

        /**
         * @return true - MediaPlayer успешно инициализирован и готов к воспроизведению
         */
        public boolean isInitialized() {
            return initialized;
        }
    }

    /**
     * Менеджер управления пользовательским интерфейсом активности TeamShowActivity.
     * Отвечает за создание и управление всеми UI-компонентами: контейнерами,
     * элементами управления звуком, анимациями и отображением ошибок.
     * Инкапсулирует логику работы с View для разделения ответственности.
     */
    private class TeamUIManager {
        private TeamShowActivity activity; // Ссылка на родительскую активность
        private FrameLayout container;     // Корневой контейнер всей активности
        private ScrollView mainScrollView; // Основной скроллируемый контейнер
        private LinearLayout teamContainer; // Контейнер для данных команды
        private TextView volumeUpText;     // Кнопка увеличения громкости
        private TextView volumeDownText;   // Кнопка уменьшения громкости
        private TextView volumeText;       // Текстовое отображение текущей громкости

        private TextView orientationButton;  // Кнопка переключения ориентации (для ландшафта)

        /**
         * Конструктор инициализирует менеджер с ссылкой на активность.
         *
         * @param activity Родительская активность TeamShowActivity
         */
        public TeamUIManager(TeamShowActivity activity) {
            this.activity = activity;
        }

        public TextView getOrientationButton() {
            return orientationButton;
        }

        /**
         * @return Корневой контейнер активности
         */
        public FrameLayout getContainer() {
            return container;
        }

        /**
         * @return Кнопка увеличения громкости
         */
        public TextView getVolumeUpText() {
            return volumeUpText;
        }

        /**
         * @return Кнопка уменьшения громкости
         */
        public TextView getVolumeDownText() {
            return volumeDownText;
        }

        /**
         * @return Текстовое отображение громкости
         */
        public TextView getVolumeText() {
            return volumeText;
        }

        /**
         * Основной метод настройки пользовательского интерфейса.
         * Создает иерархию View, устанавливает обработчики и запускает начальную анимацию.
         * Вызывается в onCreate() родительской активности.
         */
        public void setupUI() {
            container = createMainContainer();
            container.setAlpha(0f); // Начальная прозрачность для анимации появления
            setupTeamContainer();
            setupSoundControls(container);
            activity.setContentView(container);

        }

        /**
         * Создает и настраивает корневой контейнер активности.
         *
         * @return FrameLayout настроенный как корневой контейнер
         */
        private FrameLayout createMainContainer() {
            FrameLayout container = new FrameLayout(activity);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            container.setClickable(false);     // Отключаем обработку кликов на контейнере
            container.setFocusable(false);     // Отключаем фокус
            container.setBackgroundColor(Color.parseColor("#1a1a1a")); // Темный фон
            return container;
        }

        /**
         * Настраивает основную область контента с данными команды.
         * Создает ScrollView с LinearLayout для вертикального размещения элементов.
         */
        public void setupTeamContainer() {
            mainScrollView = new ScrollView(activity);
            FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            );
            mainScrollView.setLayoutParams(scrollParams);
            mainScrollView.setBackgroundColor(Color.parseColor("#1a1a1a"));

            teamContainer = new LinearLayout(activity);
            teamContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            teamContainer.setOrientation(LinearLayout.VERTICAL);
            teamContainer.setBackgroundColor(Color.parseColor("#1a1a1a"));

            // Адаптивные отступы
            DisplayMetrics displayMetrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int screenWidth = displayMetrics.widthPixels;
            int leftPadding = (int) (screenWidth * 0.06);  // 6% от ширины экрана
            int rightPadding = (int) (screenWidth * 0.05); // 5% от ширины экрана

            teamContainer.setPadding(leftPadding, dpToPx(16), rightPadding, dpToPx(16));

            mainScrollView.addView(teamContainer);
            container.addView(mainScrollView);
        }

        /**
         * Настраивает элементы управления звуком в правом нижнем углу экрана.
         *
         * @param parentContainer Родительский контейнер для размещения контролов
         */
        public void setupSoundControls(FrameLayout parentContainer) {
            FrameLayout soundControlsContainer = createSoundControlsContainer();
            setupVolumeText(soundControlsContainer);
            setupVolumeDownText(soundControlsContainer);
            setupVolumeUpText(soundControlsContainer);
            setupOrientationButton(soundControlsContainer);
            parentContainer.addView(soundControlsContainer);
            updateVolumeText(); // Инициализация текста текущей громкости
        }

        /** Создаем кнопку ориентации
         * @param container
         */
        private void setupOrientationButton(FrameLayout container) {
            orientationButton = new TextView(activity);
            configureTextAppearance(orientationButton, 16);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dpToPx(120), dpToPx(40),
                    Gravity.BOTTOM | Gravity.START  // Левая нижняя часть
            );
            params.setMargins(dpToPx(50), 0, 0, 0);
            orientationButton.setLayoutParams(params);
            orientationButton.setText("ЛИЧНОСТИ");

            orientationButton.setOnClickListener(v -> {
                // Переключаемся на портретную ориентацию
                activity.switchToPortraitOrientation();
            });

            container.addView(orientationButton);
        }


        /**
         * Создает контейнер для элементов управления звуком.
         * Позиционируется в правом нижнем углу с заданными отступами.
         */
        private FrameLayout createSoundControlsContainer() {
            FrameLayout container = new FrameLayout(activity);
            FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END
            );
            int margin = dpToPx(16);
            containerParams.setMargins(0, 0, margin, margin);
            container.setLayoutParams(containerParams);
            return container;
        }

        /**
         * Создает и настраивает кнопку увеличения громкости.
         *
         * @param container Контейнер для размещения кнопки
         */
        private void setupVolumeUpText(FrameLayout container) {
            volumeUpText = new TextView(activity);
            configureTextAppearance(volumeUpText, 16);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dpToPx(80), dpToPx(40),
                    Gravity.BOTTOM | Gravity.END
            );
            params.setMargins(0, 0, dpToPx(85), 0);
            volumeUpText.setLayoutParams(params);
            volumeUpText.setText("ЗВУК +");

            volumeUpText.setOnClickListener(v -> activity.handleVolumeIncrease());
            container.addView(volumeUpText);
        }

        /**
         * Создает и настраивает кнопку уменьшения громкости.
         *
         * @param container Контейнер для размещения кнопки
         */
        private void setupVolumeDownText(FrameLayout container) {
            volumeDownText = new TextView(activity);
            configureTextAppearance(volumeDownText, 16);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dpToPx(80), dpToPx(40),
                    Gravity.BOTTOM | Gravity.END
            );
            params.setMargins(0, 0, 0, 0);
            volumeDownText.setLayoutParams(params);
            volumeDownText.setText("ЗВУК -");

            volumeDownText.setOnClickListener(v -> activity.handleVolumeDecrease());
            container.addView(volumeDownText);
        }

        /**
         * Создает и настраивает текстовое отображение текущей громкости.
         * Текст появляется временно при изменении громкости.
         *
         * @param container Контейнер для размещения текста
         */
        private void setupVolumeText(FrameLayout container) {
            volumeText = new TextView(activity);
            volumeText.setTextSize(16);
            volumeText.setTextColor(Color.WHITE);
            volumeText.setBackgroundColor(Color.TRANSPARENT);
            volumeText.setGravity(Gravity.CENTER);
            volumeText.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
            volumeText.setTypeface(null, Typeface.BOLD);
            volumeText.setAlpha(0f); // Начально невидим

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dpToPx(120), dpToPx(40),
                    Gravity.BOTTOM | Gravity.END
            );
            params.setMargins(0, 0, dpToPx(170), 0);
            volumeText.setLayoutParams(params);
            updateVolumeText(); // Устанавливаем начальное значение

            container.addView(volumeText);
        }

        /**
         * Настраивает общий внешний вид текстовых элементов управления.
         *
         * @param textView TextView для настройки
         * @param textSize Размер текста в sp
         */
        private void configureTextAppearance(TextView textView, int textSize) {
            textView.setTextSize(textSize);
            textView.setTextColor(Color.WHITE);
            textView.setBackgroundColor(Color.TRANSPARENT);
            textView.setGravity(Gravity.CENTER);
            textView.setAlpha(0.5f); // Полупрозрачность для ненавязчивого отображения
            textView.setElevation(dpToPx(10)); // Тень для отделения от фона
            textView.setTypeface(null, Typeface.BOLD);
            textView.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        }

        /**
         * @return Контейнер для данных команды
         */
        public LinearLayout getTeamContainer() {
            return teamContainer;
        }

        /**
         * @return Основной ScrollView активности
         */
        public ScrollView getMainScrollView() {
            return mainScrollView;
        }

        /**
         * Отображает сообщение об ошибке в центре экрана.
         * Удаляет все текущие View и показывает текст ошибки.
         *
         * @param message Текст сообщения об ошибке
         */
        public void showErrorMessage(String message) {
            TextView errorText = new TextView(activity);
            errorText.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            errorText.setText(message);
            errorText.setTextColor(Color.WHITE);
            errorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            errorText.setGravity(Gravity.CENTER);
            errorText.setPadding(dpToPx(20), 0, dpToPx(20), 0);

            teamContainer.removeAllViews(); // Очищаем текущий контент
            teamContainer.addView(errorText);
        }

        /**
         * Скрывает все элементы управления звуком.
         * Используется при отсутствии звукового файла.
         */
        public void hideSoundControls() {
            if (volumeUpText != null) volumeUpText.setVisibility(View.GONE);
            if (volumeDownText != null) volumeDownText.setVisibility(View.GONE);
            if (volumeText != null) volumeText.setVisibility(View.GONE);

            if (orientationButton != null) orientationButton.setVisibility(View.GONE);
        }

        /**
         * Обновляет текстовое отображение текущей громкости.
         * Форматирует значение: "МАКСИМУМ", "БЕЗ ЗВУКА" или "X%".
         */
        public void updateVolumeText() {
            if (volumeText != null && activity.soundManager != null) {
                int volume = activity.soundManager.getVolume();
                String text = volume == 100 ? "МАКСИМУМ" :
                        volume == 0 ? "БЕЗ ЗВУКА" :
                                volume + "%";
                volumeText.setText(text);
            }
        }

        /**
         * Временно показывает текстовое отображение громкости.
         * Вызывается при изменении громкости для подтверждения действия.
         * Быстро появляется и медленно исчезает.
         */
        public void showVolumeTextTemporarily() {
            if (volumeText != null) {
                updateVolumeText(); // Обновляем текст перед показом

                volumeText.animate().cancel(); // Отменяем текущую анимацию

                volumeText.animate()
                        .alpha(0.5f)           // Быстро появляется
                        .setDuration(150)
                        .withEndAction(() -> volumeText.animate()
                                .alpha(0f)     // Медленно исчезает
                                .setDuration(800)
                                .start())
                        .start();
            }
        }

        /**
         * Преобразует значения из dp в пиксели с учетом плотности экрана.
         *
         * @param dp Значение в density-independent pixels
         * @return Значение в пикселях
         */
        private int dpToPx(int dp) {
            float density = activity.getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }
    }

    /**
     * Менеджер отображения данных команды.
     * Отвечает за загрузку, парсинг и визуализацию данных о сотрудниках из JSON.
     * Создает иерархию View для отображения групп сотрудников и их фотографий.
     * Обрабатывает различные форматы изображений (PNG, JPG, SVG).
     */
    private class DataDisplayManager {
        private TeamShowActivity activity;          // Ссылка на родительскую активность
        private TeamUIManager uiManager;            // Менеджер UI для взаимодействия с интерфейсом


        // Константы для конфигурации отображения
        private static final int PHOTOS_PER_ROW = 4;           // Количество фотографий в одном ряду
        private static final int EMPLOYEE_PHOTO_HEIGHT = 270;  // Высота фото в dp
        private static final int EMPLOYEE_ITEM_WIDTH = 180;    // Ширина элемента в dp

        // Порядок отображения групп сотрудников
        private final String[] GROUP_ORDER = {
                "team-1.svg", "team-2.svg", "team-3.svg",
                "team-4.svg", "team-5.svg", "team-6.svg"
        };

        // Массив для хранения перемешанных индексов сотрудников
        private int[] shuffledEmployeeIndices = null;

        /**
         * Конструктор инициализирует менеджер.
         *
         * @param activity  Родительская активность TeamShowActivity
         * @param uiManager Менеджер пользовательского интерфейса
         */
        public DataDisplayManager(TeamShowActivity activity, TeamUIManager uiManager) {
            this.activity = activity;
            this.uiManager = uiManager;
        }

        /**
         * Возвращает перемешанные индексы сотрудников
         */
        public int[] getShuffledEmployeeIndices() {
            return shuffledEmployeeIndices;
        }


        /**
         * Загружает и отображает данные о команде из JSON файла.
         * Выполняет проверку доступности файла, парсинг JSON и обработку ошибок.
         *
         * @param dataFilePath Абсолютный путь к JSON файлу с данными
         * @param fileStorage  Менеджер файлового хранилища
         */
        public void loadTeamData(String dataFilePath, FileStorageManager fileStorage) {
            if (dataFilePath == null || dataFilePath.isEmpty()) {
                uiManager.showErrorMessage("Нет данных о команде");
                return;
            }

            try {
                JSONObject response = fileStorage.getFileAsText(
                        FileStorageManager.STORAGE_WORKING,
                        new File(dataFilePath).getName(),
                        "team",
                        "UTF-8"
                );

                if (response != null && response.optBoolean("success", false)) {
                    String jsonText = response.optString("text", "");

                    if (!jsonText.isEmpty()) {
                        try {
                            JSONObject teamData = new JSONObject(jsonText);

                            JSONArray employeesArray = teamData.optJSONArray("data");
                            JSONObject groupsObject = teamData.optJSONObject("group");

                            // Сохраняем в активности для доступа из других методов
                            activity.teamData = teamData;
                            activity.allEmployees = employeesArray;
                            activity.groupsData = groupsObject;
                            activity.dataLoaded = true;

                            // Подготавливаем портретные данные сразу
                            prepareAndSetPortraitData();

                            // Отображаем данные НЕМЕДЛЕННО
                            displayTeamData(teamData);

                            // Сообщаем о готовности
                            Log.d("DataDisplayManager", "Данные команды загружены и отображены: " +
                                    employeesArray.length() + " сотрудников, " +
                                    (groupsObject != null ? groupsObject.length() : 0) + " групп");

                        } catch (JSONException e) {
                            Log.e("TeamShowActivity", "Ошибка парсинга JSON", e);
                            uiManager.showErrorMessage("Ошибка формата данных");
                        }
                    } else {
                        uiManager.showErrorMessage("Пустые данные");
                    }
                } else {
                    uiManager.showErrorMessage("Не удалось загрузить данные");
                }
            } catch (Exception e) {
                Log.e("TeamShowActivity", "Ошибка загрузки данных команды", e);
                uiManager.showErrorMessage("Ошибка загрузки данных: " + e.getMessage());
            }
        }

        /**
         * Подготавливает и устанавливает данные для портретного режима
         */
        public void prepareAndSetPortraitData() {
            if (activity.allEmployees == null) {
                Log.w("DataDisplayManager", "allEmployees = null, данные не загружены");
                return;
            }

            Log.d("DataDisplayManager", "Подготовка портретных данных из " +
                    activity.allEmployees.length() + " сотрудников");

            List<JSONObject> portraitList = preparePortraitEmployees(activity.allEmployees);

            // Дебаг: проверьте что в списке
            if (portraitList != null && !portraitList.isEmpty()) {
                JSONObject first = portraitList.get(0);
                Log.d("DataDisplayManager", "Первый портретный сотрудник: " +
                        first.optString("first_name", "?") + " " +
                        first.optString("second_name", "?") +
                        ", фото: " + first.optString("photo", "нет"));
            } else {
                Log.w("DataDisplayManager", "Портретный список ПУСТОЙ!");
            }

            activity.setPortraitEmployees(portraitList);
        }


        /**
         * Отображает данные команды в интерфейсе.
         * Создает иерархию View на основе структуры JSON данных.
         *
         * @param teamData JSON объект с данными о команде
         */
        private void displayTeamData(JSONObject teamData) {
            try {
                LinearLayout teamContainer = uiManager.getTeamContainer();

                if (teamContainer == null) {
                    Log.e("DataDisplayManager", "teamContainer = null!");
                    return;
                }

                teamContainer.removeAllViews();

                JSONObject groups = teamData.optJSONObject("group");
                JSONArray employees = teamData.optJSONArray("data");

                if (employees == null || employees.length() == 0) {
                    uiManager.showErrorMessage("Нет данных о сотрудниках");
                    return;
                }

                Log.d("DataDisplayManager", "Отображаем " + employees.length() + " сотрудников");

                // Группируем сотрудников по отделам
                Map<String, List<JSONObject>> groupedEmployees = groupEmployeesByDepartment(employees);

                // Отображаем группы в заданном порядке
                for (String groupKey : GROUP_ORDER) {
                    if (groups != null && groups.has(groupKey)) {
                        try {
                            JSONObject groupInfo = groups.getJSONObject(groupKey);
                            List<JSONObject> groupEmployees = groupedEmployees.get(groupKey);

                            if (groupEmployees != null && !groupEmployees.isEmpty()) {
                                addGroupHeader(groupInfo, groupKey);
                                addGroupEmployees(groupEmployees);
                            }
                        } catch (JSONException e) {
                            Log.e("DataDisplayManager", "Ошибка обработки группы " + groupKey, e);
                        }
                    }
                }

                // Отображаем сотрудников без группы
                List<JSONObject> ungroupedEmployees = new ArrayList<>();
                for (int i = 0; i < employees.length(); i++) {
                    try {
                        JSONObject employee = employees.getJSONObject(i);
                        String group = employee.optString("group", "");
                        if (group.isEmpty() || !groupedEmployees.containsKey(group)) {
                            ungroupedEmployees.add(employee);
                        }
                    } catch (JSONException e) {
                        Log.e("DataDisplayManager", "Ошибка обработки сотрудника", e);
                    }
                }

                if (!ungroupedEmployees.isEmpty()) {
                    // Создаем заголовок для сотрудников без группы
                    JSONObject ungroupedHeader = new JSONObject();
                    ungroupedHeader.put("name", "Другие сотрудники");
                    ungroupedHeader.put("desc", "");
                    ungroupedHeader.put("url", "");
                    addGroupHeader(ungroupedHeader, "other");
                    addGroupEmployees(ungroupedEmployees);
                }

            } catch (Exception e) {
                Log.e("TeamShowActivity", "Ошибка парсинга данных команды", e);
                uiManager.showErrorMessage("Ошибка формата данных");
            }
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView photoView;
            TextView nameTextView;

            ViewHolder(View itemView, ImageView photoView, TextView nameTextView) {
                super(itemView);
                this.photoView = photoView;
                this.nameTextView = nameTextView;
            }

            void bind(JSONObject employee) {
                try {
                    // Имя и фамилия
                    String firstName = employee.optString("first_name", "");
                    String secondName = employee.optString("second_name", "");
                    nameTextView.setText(firstName + "\n" + secondName);
                    nameTextView.setShadowLayer(
                            dpToPx(3), // Толщина обводки
                            0, 0,
                            Color.BLACK // Цвет обводки
                    );

                    // ДЕБАГ
                    Log.d("PortraitAdapter", "Отображение: " + firstName + " " + secondName);

                    // Загрузка фото
                    String photoPath = employee.optString("photo", "");
                    Log.d("PortraitAdapter", "Путь к фото: " + photoPath);

                    if (!photoPath.isEmpty()) {
                        File photoFile = new File(photoPath);
                        Log.d("PortraitAdapter", "Файл существует: " + photoFile.exists());

                        loadPortraitPhoto(photoPath, photoView, employee);
                    } else {
                        Log.w("PortraitAdapter", "Путь к фото ПУСТОЙ!");
                        setPortraitInitialsBackground(photoView, employee);
                    }
                } catch (Exception e) {
                    Log.e("PortraitAdapter", "Ошибка отображения сотрудника", e);
                    nameTextView.setText("Сотрудник");
                    setPortraitInitialsBackground(photoView, null);
                }
            }
        }


        /**
         * Подготавливает случайный порядок отображения сотрудников.
         * Создает массив перемешанных индексов для будущей функциональности карусели.
         *
         * @param employeeCount Общее количество сотрудников
         */
        private void prepareRandomEmployeeOrder(int employeeCount) {
            shuffledEmployeeIndices = new int[employeeCount];

            // Заполняем массив последовательными индексами
            for (int i = 0; i < employeeCount; i++) {
                shuffledEmployeeIndices[i] = i;
            }

            // Перемешиваем индексы для случайного порядка
            Random random = new Random();
            for (int i = employeeCount - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int temp = shuffledEmployeeIndices[i];
                shuffledEmployeeIndices[i] = shuffledEmployeeIndices[j];
                shuffledEmployeeIndices[j] = temp;
            }

            Log.d("TeamShowActivity", "Подготовлен случайный порядок для " + employeeCount + " сотрудников");
        }

        /**
         * Группирует сотрудников по отделам на основе поля "group" в JSON.
         *
         * @param employees JSON массив с данными сотрудников
         * @return Map где ключ - название группы, значение - список сотрудников
         * @throws JSONException при ошибке парсинга JSON
         */
        private Map<String, List<JSONObject>> groupEmployeesByDepartment(JSONArray employees)
                throws JSONException {
            Map<String, List<JSONObject>> grouped = new HashMap<>();

            if (employees == null) return grouped;

            for (int i = 0; i < employees.length(); i++) {
                JSONObject employee = employees.getJSONObject(i);
                String group = employee.optString("group", "");

                if (!group.isEmpty()) {
                    grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(employee);
                }
            }

            return grouped;
        }

        /**
         * Добавляет заголовок группы в интерфейс.
         * Включает иконку группы, название и описание.
         *
         * @param groupInfo JSON объект с информацией о группе
         * @param groupKey  Ключ группы
         * @throws JSONException при ошибке доступа к данным JSON
         */
        private void addGroupHeader(JSONObject groupInfo, String groupKey) throws JSONException {
            LinearLayout groupHeader = new LinearLayout(activity);
            groupHeader.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            groupHeader.setOrientation(LinearLayout.HORIZONTAL);
            groupHeader.setPadding(0, dpToPx(30), 0, dpToPx(15));
            groupHeader.setGravity(Gravity.BOTTOM);

            String iconUrl = groupInfo.optString("url", "");

            if (!iconUrl.isEmpty()) {
                addGroupIcon(groupHeader, iconUrl, groupKey);
            } else {
                Log.w("TeamShowActivity", "Нет URL иконки для группы: " + groupKey);
                ImageView placeholder = createColorPlaceholder(groupKey, "No");
                groupHeader.addView(placeholder);
            }

            addGroupTextContent(groupHeader, groupInfo);
            uiManager.getTeamContainer().addView(groupHeader);
            addGroupSeparator();
        }

        /**
         * Добавляет иконку группы с обработкой различных форматов изображений.
         *
         * @param groupHeader Контейнер заголовка группы
         * @param iconUrl     Путь к файлу иконки
         * @param groupKey    Ключ группы для идентификации
         */
        private void addGroupIcon(LinearLayout groupHeader, String iconUrl, String groupKey) {
            try {
                ImageView groupIcon = new ImageView(activity);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                        dpToPx(70), dpToPx(70)
                );
                iconParams.rightMargin = dpToPx(20);
                iconParams.gravity = Gravity.BOTTOM;
                groupIcon.setLayoutParams(iconParams);
                groupIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                groupIcon.setContentDescription("Иконка группы " + groupKey);

                File iconFile = new File(iconUrl);

                if (iconFile.exists() && iconFile.length() > 0) {
                    String fileName = iconFile.getName().toLowerCase();

                    if (fileName.endsWith(".png") || fileName.endsWith(".jpg") ||
                            fileName.endsWith(".jpeg") || fileName.endsWith(".webp")) {
                        loadBitmapImage(iconFile, groupIcon);
                    } else if (fileName.endsWith(".svg")) {
                        loadSvgImage(iconFile, groupIcon, groupKey);
                    } else {
                        showColorPlaceholder(groupIcon, groupKey, "?");
                    }
                } else {
                    showColorPlaceholder(groupIcon, groupKey, "!");
                }

                groupHeader.addView(groupIcon);

            } catch (Exception e) {
                ImageView errorIcon = createErrorPlaceholder(groupKey);
                groupHeader.addView(errorIcon);
            }
        }

        /**
         * Добавляет текстовую информацию группы (название и описание).
         *
         * @param groupHeader Контейнер заголовка группы
         * @param groupInfo   JSON объект с информацией о группе
         * @throws JSONException при ошибке доступа к данным JSON
         */
        private void addGroupTextContent(LinearLayout groupHeader, JSONObject groupInfo)
                throws JSONException {
            LinearLayout textContainer = new LinearLayout(activity);
            textContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            textContainer.setOrientation(LinearLayout.VERTICAL);
            textContainer.setGravity(Gravity.CENTER_VERTICAL);

            TextView groupName = new TextView(activity);
            groupName.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            groupName.setText(groupInfo.optString("name", ""));
            groupName.setTextColor(Color.WHITE);
            groupName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
            groupName.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            groupName.setPadding(0, dpToPx(0), 0, 0);
            textContainer.addView(groupName);

            String description = groupInfo.optString("desc", "");
            if (!description.isEmpty()) {
                TextView groupDesc = new TextView(activity);
                groupDesc.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                groupDesc.setText(description);
                groupDesc.setTextColor(Color.parseColor("#AAAAAA"));
                groupDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                groupDesc.setPadding(0, dpToPx(3), 0, 0);
                textContainer.addView(groupDesc);
            }

            textContainer.setGravity(Gravity.TOP);
            groupHeader.addView(textContainer);
        }

        /**
         * Добавляет разделительную линию после заголовка группы.
         */
        private void addGroupSeparator() {
            View separator = new View(activity);
            separator.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(2)
            ));
            separator.setBackgroundColor(Color.parseColor("#444444"));
            separator.setPadding(0, dpToPx(15), 0, dpToPx(15));
            uiManager.getTeamContainer().addView(separator);
        }

        /**
         * Загружает растровое изображение с оптимизацией памяти.
         *
         * @param imageFile Файл изображения
         * @param imageView ImageView для отображения
         */
        private void loadBitmapImage(File imageFile, ImageView imageView) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2; // Уменьшение размера для оптимизации памяти
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;

                Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                } else {
                    imageView.setBackgroundColor(Color.GRAY);
                }
            } catch (OutOfMemoryError e) {
                imageView.setBackgroundColor(Color.GRAY);
            } catch (Exception e) {
                imageView.setBackgroundColor(Color.GRAY);
            }
        }

        /**
         * Загружает и отображает SVG изображение.
         *
         * @param svgFile   Файл SVG изображения
         * @param imageView ImageView для отображения
         * @param groupKey  Ключ группы для создания fallback placeholder
         */
        private void loadSvgImage(File svgFile, ImageView imageView, String groupKey) {
            try {
                SVG svg = SVG.getFromInputStream(new FileInputStream(svgFile));
                Picture picture = svg.renderToPicture();
                PictureDrawable drawable = new PictureDrawable(picture);
                imageView.setImageDrawable(drawable);
            } catch (Exception e) {
                createSvgPlaceholder(imageView, groupKey);
            }
        }

        /**
         * Создает цветной placeholder с текстом для SVG изображений.
         *
         * @param imageView ImageView для установки placeholder
         * @param groupKey  Ключ группы для генерации цвета и текста
         */
        private void createSvgPlaceholder(ImageView imageView, String groupKey) {
            int color = getGroupColor(groupKey);
            String text = getGroupShortName(groupKey);

            Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Paint bgPaint = new Paint();
            bgPaint.setColor(color);
            bgPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, 80, 80, bgPaint);

            Paint textPaint = new Paint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(24);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setAntiAlias(true);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);

            float x = 40;
            float y = 40 - ((textPaint.descent() + textPaint.ascent()) / 2);
            canvas.drawText(text, x, y, textPaint);

            Paint borderPaint = new Paint();
            borderPaint.setColor(Color.WHITE);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2);
            borderPaint.setAntiAlias(true);
            canvas.drawRect(1, 1, 79, 79, borderPaint);

            imageView.setImageBitmap(bitmap);
        }

        /**
         * Создает цветной placeholder для изображений.
         *
         * @param imageView ImageView для установки placeholder
         * @param groupKey  Ключ группы для генерации цвета
         * @param text      Текст для отображения на placeholder
         */
        private void showColorPlaceholder(ImageView imageView, String groupKey, String text) {
            int color = getGroupColor(groupKey);

            Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Paint bgPaint = new Paint();
            bgPaint.setColor(color);
            canvas.drawRect(0, 0, 80, 80, bgPaint);

            if (text != null) {
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(20);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setAntiAlias(true);

                float x = 40;
                float y = 40 - ((textPaint.descent() + textPaint.ascent()) / 2);
                canvas.drawText(text, x, y, textPaint);
            }

            imageView.setImageBitmap(bitmap);
        }

        /**
         * Создает ImageView с placeholder для ошибок.
         *
         * @param groupKey Ключ группы для идентификации
         * @return ImageView с placeholder "Err"
         */
        private ImageView createErrorPlaceholder(String groupKey) {
            ImageView imageView = new ImageView(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dpToPx(80), dpToPx(80)
            );
            params.rightMargin = dpToPx(20);
            imageView.setLayoutParams(params);
            imageView.setScaleType(ImageView.ScaleType.CENTER);

            showColorPlaceholder(imageView, groupKey, "Err");
            return imageView;
        }

        /**
         * Создает ImageView с цветным placeholder.
         *
         * @param groupKey Ключ группы для генерации цвета
         * @param text     Текст для отображения
         * @return ImageView с цветным placeholder
         */
        private ImageView createColorPlaceholder(String groupKey, String text) {
            ImageView imageView = new ImageView(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dpToPx(80), dpToPx(80)
            );
            params.rightMargin = dpToPx(20);
            imageView.setLayoutParams(params);
            imageView.setScaleType(ImageView.ScaleType.CENTER);

            showColorPlaceholder(imageView, groupKey, text);
            return imageView;
        }

        /**
         * Генерирует уникальный цвет для группы на основе хэша ключа.
         *
         * @param groupKey Ключ группы
         * @return Цвет в формате ARGB
         */
        private int getGroupColor(String groupKey) {
            int hash = groupKey.hashCode();
            int r = (hash & 0xFF) % 128 + 100;
            int g = ((hash >> 8) & 0xFF) % 128 + 100;
            int b = ((hash >> 16) & 0xFF) % 128 + 100;
            return Color.rgb(r, g, b);
        }

        /**
         * Извлекает короткое название группы из ключа.
         *
         * @param groupKey Ключ группы
         * @return Сокращенное название группы
         */
        private String getGroupShortName(String groupKey) {
            if (groupKey.startsWith("team-") && groupKey.endsWith(".svg")) {
                try {
                    String num = groupKey.substring(5, groupKey.length() - 4);
                    return "T" + num;
                } catch (Exception e) {
                    return "GP";
                }
            }
            return "GP";
        }

        /**
         * Добавляет сотрудников группы в интерфейс.
         * Организует сотрудников в ряды по 4 человека с адаптивными размерами.
         *
         * @param employees Список сотрудников группы
         * @throws JSONException при ошибке парсинга данных сотрудников
         */
        private void addGroupEmployees(List<JSONObject> employees) throws JSONException {
            if (employees == null || employees.isEmpty()) {
                return;
            }

            LinearLayout employeesContainer = new LinearLayout(activity);
            employeesContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            employeesContainer.setOrientation(LinearLayout.VERTICAL);
            employeesContainer.setPadding(0, dpToPx(10), 0, dpToPx(30));

            int optimalMargin = getOptimalPhotoMargin();

            for (int i = 0; i < employees.size(); i += PHOTOS_PER_ROW) {
                LinearLayout row = createEmployeeRow(employees, i, optimalMargin);
                employeesContainer.addView(row);
            }

            uiManager.getTeamContainer().addView(employeesContainer);
        }

        /**
         * Создает ряд сотрудников.
         *
         * @param employees  Общий список сотрудников
         * @param startIndex Начальный индекс для ряда
         * @param margin     Отступ между элементами
         * @return LinearLayout с сотрудниками ряда
         * @throws JSONException при ошибке парсинга данных сотрудников
         */
        private LinearLayout createEmployeeRow(List<JSONObject> employees, int startIndex, int margin)
                throws JSONException {
            LinearLayout row = new LinearLayout(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dpToPx(20), 0, 0);
            row.setGravity(Gravity.CENTER_HORIZONTAL);

            int itemsInThisRow = Math.min(PHOTOS_PER_ROW, employees.size() - startIndex);

            for (int j = 0; j < itemsInThisRow; j++) {
                JSONObject employee = employees.get(startIndex + j);
                View employeeView = createEmployeeView(employee);

                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) employeeView.getLayoutParams();
                params.width = dpToPx(EMPLOYEE_ITEM_WIDTH);
                params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                params.leftMargin = margin;
                params.rightMargin = margin;
                params.weight = 0;

                employeeView.setLayoutParams(params);
                row.addView(employeeView);
            }

            row.setScaleX(0.99f);
            row.setScaleY(0.99f);
            row.setPivotX(0.5f);
            row.setPivotY(0.5f);

            return row;
        }

        /**
         * Создает View для отображения данных одного сотрудника.
         *
         * @param employee JSON объект с данными сотрудника
         * @return View с информацией о сотруднике
         * @throws JSONException при ошибке парсинга данных
         */
        private View createEmployeeView(JSONObject employee) throws JSONException {
            LinearLayout employeeContainer = new LinearLayout(activity);
            LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            employeeContainer.setLayoutParams(containerParams);
            employeeContainer.setOrientation(LinearLayout.VERTICAL);

            FrameLayout photoContainer = createPhotoContainer(employee);
            employeeContainer.addView(photoContainer);

            return employeeContainer;
        }

        /**
         * Создает контейнер с фотографией сотрудника и текстовой информацией.
         *
         * @param employee JSON объект с данными сотрудника
         * @return FrameLayout с фотографией и текстом
         * @throws JSONException при ошибке парсинга данных
         */
        private FrameLayout createPhotoContainer(JSONObject employee) throws JSONException {
            int photoHeight = dpToPx(EMPLOYEE_PHOTO_HEIGHT);

            FrameLayout photoContainer = new FrameLayout(activity);
            LinearLayout.LayoutParams photoContainerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    photoHeight
            );
            photoContainer.setLayoutParams(photoContainerParams);
            photoContainer.setBackgroundColor(Color.parseColor("#333333"));

            ImageView photoView = addEmployeePhoto(photoContainer, employee, photoHeight);

            // Добавляем полупрозрачную темную подложку под текст для улучшения читаемости
            View textOverlay = createTextOverlay(photoHeight);
            photoContainer.addView(textOverlay);

            addEmployeeName(photoContainer, employee, photoHeight);
            addEmployeeRole(photoContainer, employee, photoHeight);

            return photoContainer;
        }

        /**
         * Создает полупрозрачную темную подложку для текста поверх фотографии.
         * Улучшает читаемость текста на любом фоне фотографии.
         *
         * @param photoHeight Высота фотографии
         * @return View с полупрозрачной подложкой
         */
        private View createTextOverlay(int photoHeight) {
            LinearLayout overlay = new LinearLayout(activity);
            FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );

            // Подложка занимает нижние 60% фотографии (для имени и должности)
            overlayParams.gravity = Gravity.BOTTOM;
            overlayParams.height = (int) (photoHeight * 0.6);
            overlay.setLayoutParams(overlayParams);
            overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setBackgroundColor(Color.parseColor("#20000000")); // Черный с alpha 0.12
            overlay.setAlpha(0.2f);

            return overlay;
        }

        /**
         * Добавляет фотографию сотрудника в контейнер.
         *
         * @param photoContainer Контейнер для фотографии
         * @param employee       Данные сотрудника
         * @param photoHeight    Высота контейнера фотографии
         * @return ImageView с фотографией
         */
        private ImageView addEmployeePhoto(FrameLayout photoContainer, JSONObject employee, int photoHeight) {
            ImageView photoView = new ImageView(activity);
            FrameLayout.LayoutParams photoParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            );
            photoView.setLayoutParams(photoParams);
            photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);

            String photoPath = employee.optString("photo", "");
            if (!photoPath.isEmpty()) {
                loadEmployeePhoto(photoView, photoPath, employee);
            } else {
                setInitialsBackground(photoView, employee);
            }

            photoContainer.addView(photoView);
            return photoView;
        }

        /**
         * Загружает фотографию сотрудника из файла.
         *
         * @param photoView ImageView для отображения фотографии
         * @param photoPath Путь к файлу фотографии
         * @param employee  Данные сотрудника для fallback
         */
        private void loadEmployeePhoto(ImageView photoView, String photoPath, JSONObject employee) {
            try {
                File photoFile = new File(photoPath);
                if (photoFile.exists()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    Bitmap photoBitmap = BitmapFactory.decodeFile(photoPath, options);
                    if (photoBitmap != null) {
                        photoView.setImageBitmap(photoBitmap);
                    } else {
                        setInitialsBackground(photoView, employee);
                    }
                } else {
                    setInitialsBackground(photoView, employee);
                }
            } catch (Exception e) {
                setInitialsBackground(photoView, employee);
            }
        }

        /**
         * Добавляет имя и фамилию сотрудника поверх фотографии.
         *
         * @param photoContainer Контейнер фотографии
         * @param employee       Данные сотрудника
         * @param photoHeight    Высота фотографии
         */
        private void addEmployeeName(FrameLayout photoContainer, JSONObject employee, int photoHeight) {
            String firstName = employee.optString("first_name", "");
            String secondName = employee.optString("second_name", "");

            LinearLayout nameContainer = new LinearLayout(activity);
            FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(60)
            );
            nameParams.gravity = Gravity.BOTTOM;
            nameParams.bottomMargin = (int) (photoHeight * 0.15);
            nameParams.leftMargin = dpToPx(8);
            nameParams.rightMargin = dpToPx(8);
            nameContainer.setLayoutParams(nameParams);
            nameContainer.setOrientation(LinearLayout.VERTICAL);
            nameContainer.setGravity(Gravity.CENTER);

            if (!firstName.isEmpty()) {
                TextView firstNameText = createNameTextView(firstName);
                firstNameText.setShadowLayer(
                        dpToPx(3), // Толщина обводки
                        0, 0,
                        Color.BLACK // Цвет обводки
                );
                nameContainer.addView(firstNameText);
            }

            if (!secondName.isEmpty()) {
                TextView secondNameText = createNameTextView(secondName);
                secondNameText.setShadowLayer(
                        dpToPx(3), // Толщина обводки
                        0, 0,
                        Color.BLACK // Цвет обводки
                );
                nameContainer.addView(secondNameText);
            }

            photoContainer.addView(nameContainer);
        }

        /**
         * Создает TextView для отображения имени/фамилии сотрудника.
         *
         * @param text Текст для отображения
         * @return Настроенный TextView
         */
        private TextView createNameTextView(String text) {
            TextView textView = new TextView(activity);
            textView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            textView.setText(text);
            textView.setTextColor(Color.WHITE);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            textView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textView.setMaxLines(1);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity(Gravity.CENTER);
            return textView;
        }

        /**
         * Добавляет должность сотрудника поверх фотографии.
         *
         * @param photoContainer Контейнер фотографии
         * @param employee       Данные сотрудника
         * @param photoHeight    Высота фотографии
         */
        private void addEmployeeRole(FrameLayout photoContainer, JSONObject employee, int photoHeight) {
            String role = employee.optString("role", "");

            if (!role.isEmpty()) {
                TextView roleText = new TextView(activity);
                FrameLayout.LayoutParams roleParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                roleParams.gravity = Gravity.TOP;
                roleParams.topMargin = (int) (photoHeight * 0.85);
                roleParams.leftMargin = dpToPx(8);
                roleParams.rightMargin = dpToPx(8);
                roleText.setLayoutParams(roleParams);
                roleText.setText(role);
                roleText.setTextColor(Color.WHITE);
                roleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                roleText.setMaxLines(2);
                roleText.setEllipsize(TextUtils.TruncateAt.END);
                roleText.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                roleText.setLineSpacing(0, 0.9f);

                roleText.setShadowLayer(
                        dpToPx(3), // Толщина обводки
                        0, 0,
                        Color.BLACK // Цвет обводки
                );

                photoContainer.addView(roleText);
            }
        }

        /**
         * Устанавливает цветной фон с инициалами при отсутствии фотографии.
         *
         * @param imageView ImageView для установки фона
         * @param employee  Данные сотрудника
         */
        private void setInitialsBackground(ImageView imageView, JSONObject employee) {
            try {
                String name = employee.getString("first_name") + employee.getString("second_name");
                int color = generateColorFromName(name);
                imageView.setBackgroundColor(color);
            } catch (JSONException e) {
                imageView.setBackgroundColor(Color.GRAY);
            }
        }

        /**
         * Генерирует уникальный цвет на основе имени сотрудника.
         *
         * @param name Полное имя сотрудника
         * @return Цвет в формате ARGB
         */
        private int generateColorFromName(String name) {
            int hash = name.hashCode();
            int r = (hash & 0xFF) % 128 + 127;
            int g = ((hash >> 8) & 0xFF) % 128 + 127;
            int b = ((hash >> 16) & 0xFF) % 128 + 127;
            return Color.rgb(r, g, b);
        }

        /**
         * Рассчитывает оптимальный отступ между фотографиями.
         *
         * @return Отступ в пикселях
         */
        private int getOptimalPhotoMargin() {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            float screenWidth = metrics.widthPixels;

            if (screenWidth < 1200) {
                return dpToPx(2);
            } else if (screenWidth < 1600) {
                return dpToPx(3);
            } else {
                return dpToPx(4);
            }
        }

        /**
         * Преобразует значения из dp в пиксели.
         *
         * @param dp Значение в density-independent pixels
         * @return Значение в пикселях
         */
        private int dpToPx(int dp) {
            float density = activity.getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }

        /**
         * Очищает ресурсы и ссылки для предотвращения утечек памяти.
         */
        public void cleanup() {
            shuffledEmployeeIndices = null;
            activity = null;
            uiManager = null;
        }
    }

    /**
     * Менеджер автоматической прокрутки с центрированием на рядах сотрудников.
     * Обеспечивает плавную остановку скролла на центре ряда с тактильной обратной связью.
     * Отслеживает скорость, инерцию и автоматически центрирует при замедлении.
     */
    private class AutoScrollManager {
        private TeamShowActivity activity;
        private TeamUIManager uiManager;

        // Состояние прокрутки
        private boolean isAutoScrolling = false;
        private boolean isUserTouching = false;

        // Обработчики и задачи
        private android.os.Handler autoScrollHandler;
        private android.os.Handler checkerHandler;
        private Runnable autoScrollRunnable;
        private Runnable checkerRunnable;

        // Константы настройки
        private float CENTERING_DURATION_MS = 800f;
        private static final int CHECK_INTERVAL = 50;
        private static final int HISTORY_SIZE = 5;
        private float VELOCITY_THRESHOLD = 50f;
        private float MAX_SCROLL_VELOCITY = 500f;

        // Данные о рядах сотрудников
        private List<Integer> rowCenters = new ArrayList<>();
        private boolean rowsCalculated = false;
        private boolean initialCenteringDone = false;

        // Отслеживание движения и скорости
        private int lastScrollY = 0;
        private long lastScrollTime = 0;
        private boolean isScrollingByInertia = false;
        private float scrollVelocity = 0f;
        private List<Integer> scrollHistory = new ArrayList<>();

        // Отслеживание пользовательского скролла
        private float userScrollVelocity = 0f;
        private float lastUserVelocity = 0f;
        private boolean isUserScrolling = false;
        private long lastUserScrollTime = 0;
        private int lastUserScrollY = 0;

        // Плавное замедление
        private boolean isSlowingDown = false;
        private float slowDownStartVelocity = 0f;
        private long slowDownStartTime = 0;

        // Отладочные элементы
        private FrameLayout debugOverlay;
        private List<View> debugLineViews = new ArrayList<>();
        private boolean showDebugLines = false;

        /**
         * Флаг активности менеджера
         */
        private boolean isActive = true;

        public AutoScrollManager(TeamShowActivity activity, TeamUIManager uiManager) {
            this.activity = activity;
            this.uiManager = uiManager;
        }

        /**
         * Включает или отключает работу менеджера в зависимости от ориентации.
         *
         * @param orientation Ориентация экрана
         */
        public void setOrientation(int orientation) {
            boolean shouldBeActive = (orientation == TeamShowActivity.ORIENTATION_LANDSCAPE);

            if (isActive != shouldBeActive) {
                isActive = shouldBeActive;

                if (!isActive) {
                    // Отключаем все функции в портретной ориентации
                    stopAutoScroll();
                    stopChecking();
                    resetScrollState();
                } else {
                    // Включаем обратно в ландшафтной
                    if (uiManager != null && uiManager.getMainScrollView() != null) {
                        startContinuousChecking();
                    }
                }
            }
        }

        /**
         * Настраивает мониторинг касаний и скролла.
         */
        public void setupTouchAndScrollMonitoring() {
            ScrollView scrollView = uiManager.getMainScrollView();
            if (scrollView == null) return;

            // Инициализируем только если активен
            if (!isActive) return;

            initDebugLines();

            // Обработка касаний
            scrollView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!isActive) return false;
                    handleTouchEvent(event, scrollView);
                    return false;
                }
            });

            // Мониторинг скролла
            scrollView.getViewTreeObserver().addOnScrollChangedListener(
                    new ViewTreeObserver.OnScrollChangedListener() {
                        @Override
                        public void onScrollChanged() {
                            handleScrollChanged(scrollView);
                        }
                    }
            );

            calculateRowsInfo();

            if (isActive) {
                startContinuousChecking();
                scheduleInitialCentering(scrollView);
            }
        }

        /**
         * Обрабатывает события касания.
         */
        private void handleTouchEvent(MotionEvent event, ScrollView scrollView) {
            if (!isActive) return;

            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    resetScrollState();
                    stopAutoScroll();
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (isUserTouching && !isUserScrolling) {
                        isUserScrolling = true;
                        lastUserScrollY = scrollView.getScrollY();
                        lastUserScrollTime = System.currentTimeMillis();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isUserTouching = false;

                    if (isUserScrolling) {
                        isUserScrolling = false;
                        handleScrollEnd(scrollView.getScrollY());
                    }
                    break;
            }
        }

        /**
         * Сбрасывает состояние скролла.
         */
        private void resetScrollState() {
            isUserTouching = true;
            isUserScrolling = false;
            isScrollingByInertia = false;
            isSlowingDown = false;
            scrollHistory.clear();
            userScrollVelocity = 0f;
            lastUserVelocity = 0f;
        }

        /**
         * Обрабатывает завершение пользовательского скролла.
         */
        private void handleScrollEnd(int currentScrollY) {
            if (userScrollVelocity <= VELOCITY_THRESHOLD) {
                startIntelligentCentering(currentScrollY);
            } else {
                startWaitingForSlowdown(currentScrollY, userScrollVelocity);
            }
        }

        /**
         * Обрабатывает изменение позиции скролла.
         */
        private void handleScrollChanged(ScrollView scrollView) {
            if (!isActive) return;
            int currentScrollY = scrollView.getScrollY();
            long currentTime = System.currentTimeMillis();

            updateScrollHistory(currentScrollY);

            if (isUserScrolling) {
                calculateUserScrollVelocity(currentScrollY, currentTime);
            } else if (!isUserTouching) {
                calculateInertialScrollVelocity(currentScrollY, currentTime);
            }

            lastScrollY = currentScrollY;
            lastScrollTime = currentTime;
            updateDebugLines();
        }

        /**
         * Рассчитывает скорость пользовательского скролла.
         */
        private void calculateUserScrollVelocity(int currentScrollY, long currentTime) {
            if (lastUserScrollTime > 0) {
                long timeDelta = currentTime - lastUserScrollTime;
                if (timeDelta > 0) {
                    int scrollDelta = currentScrollY - lastUserScrollY;
                    float currentVelocity = Math.abs(scrollDelta * 1000f / (float) timeDelta);
                    userScrollVelocity = userScrollVelocity * 0.7f + currentVelocity * 0.3f;

                    detectSlowingDown(currentVelocity, currentTime);
                    checkVelocityThreshold(currentScrollY);
                }
            }

            lastUserScrollY = currentScrollY;
            lastUserScrollTime = currentTime;
        }

        /**
         * Обнаруживает замедление скролла.
         */
        private void detectSlowingDown(float currentVelocity, long currentTime) {
            if (lastUserVelocity > 0 && currentVelocity < lastUserVelocity) {
                if (!isSlowingDown) {
                    isSlowingDown = true;
                    slowDownStartVelocity = currentVelocity;
                    slowDownStartTime = currentTime;
                }

                if (currentVelocity <= VELOCITY_THRESHOLD) {
                    startIntelligentCentering(lastUserScrollY);
                }
            } else {
                isSlowingDown = false;
            }

            lastUserVelocity = currentVelocity;
        }

        /**
         * Проверяет порог скорости для центрирования.
         */
        private void checkVelocityThreshold(int currentScrollY) {
            if (userScrollVelocity <= VELOCITY_THRESHOLD) {
                startIntelligentCentering(currentScrollY);
            }
        }

        /**
         * Рассчитывает скорость инерционного скролла.
         */
        private void calculateInertialScrollVelocity(int currentScrollY, long currentTime) {
            if (lastScrollTime > 0) {
                long timeDelta = currentTime - lastScrollTime;
                if (timeDelta > 0) {
                    int scrollDelta = currentScrollY - lastScrollY;
                    float currentVelocity = Math.abs(scrollDelta * 1000f / (float) timeDelta);
                    scrollVelocity = scrollVelocity * 0.7f + currentVelocity * 0.3f;

                    if (Math.abs(scrollDelta) > 2) {
                        isScrollingByInertia = true;
                    }
                }
            }
        }

        /**
         * Запускает ожидание замедления скролла.
         */
        private void startWaitingForSlowdown(int currentScrollY, float currentVelocity) {
            if (isAutoScrolling) return;

            final ScrollView scrollView = uiManager.getMainScrollView();
            if (scrollView == null) return;

            new android.os.Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isUserTouching && !isAutoScrolling && scrollView != null) {
                        if (userScrollVelocity <= VELOCITY_THRESHOLD) {
                            startIntelligentCentering(scrollView.getScrollY());
                        } else {
                            startWaitingForSlowdown(scrollView.getScrollY(), userScrollVelocity);
                        }
                    }
                }
            }, 50);
        }

        /**
         * Запускает интеллектуальное центрирование на ближайший ряд.
         */
        private void startIntelligentCentering(int currentScrollY) {
            if (!isActive || isAutoScrolling || !rowsCalculated || rowCenters.isEmpty()) {
                return;
            }

            ScrollView scrollView = uiManager.getMainScrollView();
            if (scrollView == null) return;

            if (System.currentTimeMillis() - lastScrollTime < 100) {
                return;
            }

            int screenHeight = scrollView.getHeight();
            int screenCenter = currentScrollY + (screenHeight / 2);
            Integer targetRow = findNearestRow(screenCenter);

            if (targetRow != null) {
                int targetY = targetRow - (screenHeight / 2);
                targetY = Math.max(0, targetY);

                // Проверка границ скролла
                LinearLayout teamContainer = uiManager.getTeamContainer();
                if (teamContainer != null) {
                    int maxScroll = teamContainer.getHeight() - screenHeight;
                    targetY = Math.max(0, Math.min(maxScroll, targetY));
                }

                // Проверка близости к цели
                int distanceToTarget = Math.abs(currentScrollY - targetY);

                if (distanceToTarget > 10) {
                    float animationDuration = calculateAnimationDuration(userScrollVelocity);
                    startSmoothScroll(currentScrollY, targetY, animationDuration);
                }
            }
        }

        /**
         * Находит ближайший ряд к позиции.
         */
        private Integer findNearestRow(int screenCenter) {
            if (rowCenters.isEmpty()) {
                return null;
            }

            Integer nearestRow = rowCenters.get(0);
            int minDistance = Math.abs(screenCenter - nearestRow);

            for (int i = 1; i < rowCenters.size(); i++) {
                int rowCenter = rowCenters.get(i);
                int distance = Math.abs(screenCenter - rowCenter);

                if (distance < minDistance) {
                    minDistance = distance;
                    nearestRow = rowCenter;
                }
            }

            if (minDistance < 20) {
                return null;
            }

            return nearestRow;
        }

        /**
         * Рассчитывает длительность анимации центрирования.
         */
        private float calculateAnimationDuration(float currentVelocity) {
            float baseDuration = CENTERING_DURATION_MS;

            if (currentVelocity > VELOCITY_THRESHOLD) {
                float speedFactor = Math.min(2.0f, currentVelocity / VELOCITY_THRESHOLD);
                return Math.max(300f, baseDuration / speedFactor);
            } else {
                return baseDuration;
            }
        }

        /**
         * Запускает плавную прокрутку к целевой позиции.
         */
        private void startSmoothScroll(int startY, int targetY, float duration) {
            stopAutoScroll();

            if (Math.abs(startY - targetY) < 5) {
                return;
            }

            isAutoScrolling = true;
            final int startScrollY = startY;
            final int targetScrollY = targetY;
            final float scrollDuration = duration;
            final long startTime = System.currentTimeMillis();

            if (autoScrollHandler == null) {
                autoScrollHandler = new android.os.Handler();
            }

            autoScrollRunnable = new Runnable() {
                @Override
                public void run() {
                    ScrollView scrollView = uiManager.getMainScrollView();
                    if (!isAutoScrolling || scrollView == null) {
                        return;
                    }

                    long currentTime = System.currentTimeMillis();
                    float elapsed = currentTime - startTime;
                    float progress = Math.min(1.0f, elapsed / scrollDuration);

                    // Ease-out для плавности
                    progress = 1 - (1 - progress) * (1 - progress) * (1 - progress);

                    int currentY = startScrollY + (int) ((targetScrollY - startScrollY) * progress);
                    scrollView.scrollTo(0, currentY);

                    if (progress < 1.0f) {
                        autoScrollHandler.postDelayed(this, 16);
                    } else {
                        isAutoScrolling = false;
                        Log.d("AutoScroll", "Авто-скролл завершен");
                    }

                    updateDebugLines();
                }
            };

            autoScrollHandler.post(autoScrollRunnable);
            Log.d("AutoScroll", "Старт авто-скролла с " + startY + " до " + targetY);
        }

        /**
         * Планирует начальное центрирование.
         */
        private void scheduleInitialCentering(ScrollView scrollView) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    calculateRowsInfo();

                    scrollView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            centerToFirstRow();

                            scrollView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    adjustInitialPosition(scrollView);
                                }
                            }, 50);
                        }
                    }, 100);
                }
            });
        }

        /**
         * Корректирует начальную позицию.
         */
        private void adjustInitialPosition(ScrollView scrollView) {
            int currentY = scrollView.getScrollY();
            int screenCenter = currentY + (scrollView.getHeight() / 2);
            Integer nearestRow = findNearestRow(screenCenter);

            if (nearestRow != null) {
                int targetY = nearestRow - (scrollView.getHeight() / 2);
                if (Math.abs(currentY - targetY) > 5) {
                    scrollView.scrollTo(0, targetY);
                }
            }
        }

        /**
         * Центрирует на первый ряд при запуске.
         */
        private void centerToFirstRow() {
            if (initialCenteringDone || !rowsCalculated || rowCenters.isEmpty()) {
                return;
            }

            ScrollView scrollView = uiManager.getMainScrollView();
            if (scrollView == null) return;

            java.util.Collections.sort(rowCenters);
            Integer firstRowCenter = rowCenters.get(0);
            if (firstRowCenter == null) return;

            int[] scrollLocation = new int[2];
            scrollView.getLocationOnScreen(scrollLocation);
            int scrollViewTop = scrollLocation[1];

            int screenHeight = scrollView.getHeight();
            int screenCenter = screenHeight / 2;
            int rowCenterInScrollView = firstRowCenter - scrollViewTop;
            int targetY = Math.max(0, rowCenterInScrollView - screenCenter);

            LinearLayout teamContainer = uiManager.getTeamContainer();
            if (teamContainer != null) {
                int maxScroll = teamContainer.getHeight() - screenHeight;
                targetY = Math.max(0, Math.min(maxScroll, targetY));
            }

            scrollView.scrollTo(0, targetY);
            initialCenteringDone = true;
            updateDebugLines();
        }

        /**
         * Вычисляет позиции рядов.
         */
        private void calculateRowsInfo() {
            if (rowsCalculated) return;

            rowCenters.clear();
            LinearLayout teamContainer = uiManager.getTeamContainer();
            if (teamContainer == null) return;

            teamContainer.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        findPhotoRowsDirect(teamContainer);
                        findPhotoRowsRecursive(teamContainer, 0);
                        java.util.Collections.sort(rowCenters);
                        rowsCalculated = true;
                        updateDebugLines();
                    } catch (Exception e) {
                        Log.e("AutoScroll", "Ошибка вычисления рядов", e);
                    }
                }
            });
        }

        /**
         * Прямой поиск рядов.
         */
        private void findPhotoRowsDirect(ViewGroup container) {
            List<View> allViews = getAllViews(container);

            for (View view : allViews) {
                if (view instanceof LinearLayout) {
                    LinearLayout layout = (LinearLayout) view;

                    if (layout.getOrientation() == LinearLayout.HORIZONTAL) {
                        boolean hasPhotos = false;
                        for (int i = 0; i < layout.getChildCount(); i++) {
                            View child = layout.getChildAt(i);
                            if (child instanceof LinearLayout && isEmployeeContainer((LinearLayout) child)) {
                                hasPhotos = true;
                                break;
                            }
                        }

                        if (hasPhotos) {
                            int[] location = new int[2];
                            layout.getLocationOnScreen(location);

                            int rowCenter = location[1] + (layout.getHeight() / 2);
                            if (!rowCenters.contains(rowCenter)) {
                                rowCenters.add(rowCenter);
                            }
                        }
                    }
                }
            }
        }

        /**
         * Рекурсивный поиск рядов.
         */
        private void findPhotoRowsRecursive(ViewGroup container, int currentY) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);

                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }

                if (child instanceof LinearLayout) {
                    LinearLayout layout = (LinearLayout) child;

                    if (layout.getOrientation() == LinearLayout.HORIZONTAL) {
                        boolean hasPhotoContainers = false;
                        for (int j = 0; j < layout.getChildCount(); j++) {
                            View rowChild = layout.getChildAt(j);
                            if (rowChild instanceof LinearLayout && containsPhotoFrame((LinearLayout) rowChild)) {
                                hasPhotoContainers = true;
                                break;
                            }
                        }

                        if (hasPhotoContainers) {
                            int[] location = new int[2];
                            child.getLocationOnScreen(location);
                            int rowCenter = location[1] + (child.getHeight() / 2);
                            rowCenters.add(rowCenter);
                        }
                    }
                }

                if (child instanceof ViewGroup) {
                    findPhotoRowsRecursive((ViewGroup) child, currentY);
                }
            }
        }

        /**
         * Проверяет наличие FrameLayout (фото) в контейнере.
         */
        private boolean containsPhotoFrame(ViewGroup container) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);

                if (child instanceof FrameLayout) {
                    return true;
                }

                if (child instanceof ViewGroup && containsPhotoFrame((ViewGroup) child)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Проверяет, является ли контейнер контейнером сотрудника.
         */
        private boolean isEmployeeContainer(LinearLayout container) {
            if (container.getChildCount() > 0) {
                View firstChild = container.getChildAt(0);
                if (firstChild instanceof FrameLayout) {
                    return true;
                }

                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof ViewGroup && containsFrameLayout((ViewGroup) child)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Проверяет наличие FrameLayout.
         */
        private boolean containsFrameLayout(ViewGroup container) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);

                if (child instanceof FrameLayout) {
                    return true;
                }

                if (child instanceof ViewGroup && containsFrameLayout((ViewGroup) child)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Получает все View в контейнере.
         */
        private List<View> getAllViews(ViewGroup container) {
            List<View> views = new ArrayList<>();
            getAllViewsRecursive(container, views);
            return views;
        }

        private void getAllViewsRecursive(ViewGroup container, List<View> views) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                views.add(child);

                if (child instanceof ViewGroup) {
                    getAllViewsRecursive((ViewGroup) child, views);
                }
            }
        }

        /**
         * Запускает периодическую проверку состояния.
         */
        private void startContinuousChecking() {
            if (!isActive) return;
            if (checkerHandler == null) {
                checkerHandler = new android.os.Handler();
            }

            checkerRunnable = new Runnable() {
                @Override
                public void run() {
                    checkScrollState();
                    checkerHandler.postDelayed(this, CHECK_INTERVAL);
                }
            };

            checkerHandler.postDelayed(checkerRunnable, 1000);
        }

        /**
         * Проверяет состояние скролла.
         */
        private void checkScrollState() {
            if (isUserTouching || isAutoScrolling) {
                return;
            }

            ScrollView scrollView = uiManager.getMainScrollView();
            if (scrollView == null) return;

            if (isScrollingByInertia) {
                long timeSinceLastMove = System.currentTimeMillis() - lastScrollTime;
                if (timeSinceLastMove > 100) {
                    isScrollingByInertia = false;

                    if (scrollVelocity <= VELOCITY_THRESHOLD * 2) {
                        startIntelligentCentering(scrollView.getScrollY());
                    }
                }
            } else if (!isUserScrolling) {
                int currentScrollY = scrollView.getScrollY();
                int screenHeight = scrollView.getHeight();
                int screenCenter = currentScrollY + (screenHeight / 2);

                if (findCurrentRowCenter(screenCenter) == null) {
                    startIntelligentCentering(currentScrollY);
                }
            }
        }

        /**
         * Находит текущий ряд.
         */
        private Integer findCurrentRowCenter(int screenCenter) {
            if (rowCenters.isEmpty()) {
                return null;
            }

            for (Integer rowCenter : rowCenters) {
                int distance = Math.abs(screenCenter - rowCenter);
                if (distance < 50) {
                    return rowCenter;
                }
            }

            return null;
        }

        /**
         * Обновляет историю скролла.
         */
        private void updateScrollHistory(int currentScrollY) {
            scrollHistory.add(currentScrollY);
            if (scrollHistory.size() > HISTORY_SIZE) {
                scrollHistory.remove(0);
            }
        }

        /**
         * Инициализирует отладочные линии.
         */
        private void initDebugLines() {
            if (!showDebugLines) return;
            // Реализация отладочных линий
        }

        /**
         * Обновляет отладочные линии.
         */
        private void updateDebugLines() {
            if (!showDebugLines) return;
            // Обновление отладочных линий
        }

        /**
         * Очищает отладочные элементы.
         */
        public void cleanupDebug() {
            if (debugOverlay != null && uiManager != null && uiManager.container != null) {
                uiManager.container.removeView(debugOverlay);
                debugOverlay = null;
            }
            debugLineViews.clear();
        }

        /**
         * Останавливает автоматическую прокрутку.
         */
        public void stopAutoScroll() {
            isAutoScrolling = false;
            isSlowingDown = false;

            if (autoScrollHandler != null && autoScrollRunnable != null) {
                autoScrollHandler.removeCallbacks(autoScrollRunnable);
            }
        }

        /**
         * Останавливает проверку состояния.
         */
        public void stopChecking() {
            if (checkerHandler != null && checkerRunnable != null) {
                checkerHandler.removeCallbacks(checkerRunnable);
            }
        }

        /**
         * Устанавливает длительность центрирования.
         */
        public void setCenteringDuration(float durationMs) {
            this.CENTERING_DURATION_MS = durationMs;
        }

        /**
         * Очищает все ресурсы менеджера.
         */
        public void cleanupAll() {
            stopAutoScroll();
            stopChecking();

            if (rowCenters != null) {
                rowCenters.clear();
            }

            if (scrollHistory != null) {
                scrollHistory.clear();
            }

            if (autoScrollHandler != null) {
                autoScrollHandler.removeCallbacksAndMessages(null);
                autoScrollHandler = null;
            }

            if (checkerHandler != null) {
                checkerHandler.removeCallbacksAndMessages(null);
                checkerHandler = null;
            }

            cleanupDebug();
        }
    }

    /**
     * Менеджер управления настройками приложения.
     * <p>
     * Отвечает за загрузку и сохранение пользовательских настроек между сеансами работы приложения.
     * Обрабатывает следующие параметры:
     * - Путь к файлу данных о команде
     * - Путь к звуковому файлу фоновой музыки
     * - Уровень громкости звука
     * <p>
     * Данные сохраняются в формате JSON в файловой системе приложения и автоматически
     * восстанавливаются при повторном запуске.
     */
    private class SettingsManager {

        /**
         * Ссылка на родительскую активность TeamShowActivity.
         * Используется для доступа к её полям и методам.
         */
        private TeamShowActivity activity;

        /**
         * Конструктор менеджера настроек.
         *
         * @param activity Ссылка на родительскую активность TeamShowActivity
         */
        public SettingsManager(TeamShowActivity activity) {
            this.activity = activity;
        }

        /**
         * Загружает сохранённые настройки из файла или применяет настройки по умолчанию.
         * <p>
         * Метод выполняет следующие действия:
         * 1. Пытается загрузить файл настроек settings.json из локального хранилища
         * 2. При успешной загрузке применяет сохранённые параметры
         * 3. При ошибке загрузки или отсутствии файла применяет настройки по умолчанию
         *
         * @param fileStorage  Менеджер файлового хранилища для доступа к локальным файлам
         * @param soundManager Менеджер звука для применения уровня громкости
         */
        public void loadSettings(FileStorageManager fileStorage, SoundManager soundManager) {
            try {
                // Пытаемся загрузить файл настроек из директории "team" в рабочем хранилище
                JSONObject settings = fileStorage.getParams(
                        FileStorageManager.STORAGE_WORKING, // Используем рабочее хранилище
                        "settings.json",                    // Имя файла настроек
                        "team",                            // Категория/директория
                        null,                              // Входные параметры (не требуются для загрузки)
                        null                               // Коллбэк (не используется)
                );

                // Проверяем, удалось ли загрузить настройки и содержит ли файл данные
                if (settings != null && settings.length() > 0) {
                    // Применяем загруженные настройки
                    applyLoadedSettings(settings, soundManager);
                } else {
                    // Если файл пустой или не найден, применяем настройки по умолчанию
                    applyDefaultSettings(soundManager);
                }
            } catch (Exception e) {
                // При любой ошибке загрузки применяем настройки по умолчанию
                applyDefaultSettings(soundManager);
            }
        }

        /**
         * Применяет загруженные настройки из JSON объекта.
         * <p>
         * Извлекает следующие параметры:
         * - "path": путь к файлу данных о команде
         * - "sound": путь к звуковому файлу
         * - "sound_volume": уровень громкости (0-100)
         *
         * @param settings     JSON объект с загруженными настройками
         * @param soundManager Менеджер звука для установки уровня громкости
         */
        private void applyLoadedSettings(JSONObject settings, SoundManager soundManager) {
            // Извлекаем пути к файлам с данными
            // optString возвращает пустую строку, если ключ не найден
            activity.dataFilePath = settings.optString("path", "");
            activity.soundFilePath = settings.optString("sound", "");

            // Устанавливаем сохранённый уровень громкости
            if (soundManager != null) {
                int savedVolume = settings.optInt("sound_volume", 10); // По умолчанию 10
                soundManager.setVolume(savedVolume);
            }
        }

        /**
         * Применяет настройки по умолчанию.
         * <p>
         * Используется в следующих случаях:
         * 1. При первом запуске приложения
         * 2. При ошибке загрузки файла настроек
         * 3. Если файл настроек пустой или повреждён
         *
         * @param soundManager Менеджер звука для установки уровня громкости по умолчанию
         */
        private void applyDefaultSettings(SoundManager soundManager) {
            // Сбрасываем пути к файлам
            activity.dataFilePath = "";
            activity.soundFilePath = "";

            // Устанавливаем громкость по умолчанию
            if (soundManager != null) {
                soundManager.setVolume(10); // Уровень громкости по умолчанию
            }
        }

        /**
         * Сохраняет текущие настройки в файл для последующего восстановления.
         * <p>
         * Метод выполняет следующие действия:
         * 1. Создаёт JSON объект с текущими параметрами
         * 2. Сохраняет только уровень громкости (остальные параметры сохраняются в других частях приложения)
         * 3. Записывает данные в файл settings.json
         *
         * @param fileStorage  Менеджер файлового хранилища для сохранения данных
         * @param soundManager Менеджер звука для получения текущего уровня громкости
         */
        public void saveSettings(FileStorageManager fileStorage, SoundManager soundManager) {
            try {
                if (soundManager != null) {
                    // Создаём JSON объект с текущими параметрами
                    JSONObject param_in = new JSONObject();
                    // Сохраняем только уровень громкости, так как пути к файлам
                    // управляются другими компонентами приложения
                    param_in.put("sound_volume", soundManager.getVolume());

                    // Сохраняем настройки в файл
                    fileStorage.getInstance(activity).getParams(
                            FileStorageManager.STORAGE_WORKING, // Рабочее хранилище
                            "settings.json",                    // Имя файла настроек
                            "team",                            // Категория/директория
                            param_in,                           // Данные для сохранения
                            null                                // Коллбэк (не используется)
                    );
                }
            } catch (JSONException e) {
                // Логируем ошибку, но не прерываем работу приложения
                Log.e("SettingsManager", "Ошибка сохранения настроек", e);
            }
        }
    }

    /**
     * Менеджер инициализации активности TeamShowActivity.
     * <p>
     * Отвечает за начальную настройку активности перед отображением UI:
     * 1. Конфигурация ориентации и полноэкранного режима
     * 2. Настройка immersive режима (edge-to-edge)
     * 3. Инициализация менеджеров приложения
     * 4. Запуск фоновой музыки
     * <p>
     * Все методы этого класса вызываются из onCreate() родительской активности
     * для обеспечения правильного порядка инициализации.
     */
    private class ActivityInitializer {

        /**
         * Ссылка на родительскую активность TeamShowActivity.
         * Используется для доступа к её полям и методам конфигурации.
         */
        private TeamShowActivity activity;

        /**
         * Конструктор менеджера инициализации.
         *
         * @param activity Ссылка на родительскую активность TeamShowActivity
         */
        public ActivityInitializer(TeamShowActivity activity) {
            this.activity = activity;
        }

        /**
         * Настраивает базовую конфигурацию активности.
         * <p>
         * Выполняет следующие настройки:
         * 1. Устанавливает ландшафтную ориентацию экрана
         * 2. Скрывает панель действий (ActionBar)
         * 3. Включает полноэкранный режим
         * 4. Активирует edge-to-edge отображение
         * <p>
         * Вызывается в onCreate() перед созданием UI.
         */
        public void initializeActivityConfiguration() {
            // Фиксируем ландшафтную ориентацию для оптимального отображения команды
            // activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

            // Скрываем панель действий для полноэкранного отображения
            if (activity.getSupportActionBar() != null) {
                activity.getSupportActionBar().hide();
            }

            // Включаем полноэкранный режим (скрываем статус-бар и навигационную панель)
            activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Настраиваем edge-to-edge отображение (контент под системными панелями)
            enableEdgeToEdge();
        }

        /**
         * Настраивает edge-to-edge отображение (контент под системными панелями).
         * <p>
         * Реализует постепенное улучшение в зависимости от версии Android:
         * - Lollipop (5.0+): базовое прозрачное отображение системных панелей
         * - Marshmallow (6.0+): светлый статус-бар с тёмными иконками
         * - KitKat (4.4+): полупрозрачный статус-бар
         * - Pie (9.0+): поддержка вырезов в экране (notch)
         * <p>
         * Метод обеспечивает современный immersive опыт на всех версиях Android.
         */
        private void enableEdgeToEdge() {
            // Для Android Lollipop (5.0) и выше
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                // Устанавливаем флаги для отображения контента под системными панелями
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |           // Стабильный layout при изменении системных панелей
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | // Контент под навигационной панелью
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN        // Контент под статус-баром
                );

                // Делаем системные панели полностью прозрачными
                activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
                activity.getWindow().setStatusBarColor(Color.TRANSPARENT);

                // Для Android Marshmallow (6.0) и выше - настраиваем светлый статус-бар
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    activity.getWindow().getDecorView().setSystemUiVisibility(
                            activity.getWindow().getDecorView().getSystemUiVisibility() |
                                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR // Светлые иконки на статус-баре
                    );
                }
            }

            // Для Android KitKat (4.4) - альтернативная настройка полупрозрачного статус-бара
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                activity.getWindow().setFlags(
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                );
            }

            // Для Android Pie (9.0) и выше - поддержка вырезов в экране (notch)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                activity.getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        /**
         * Инициализирует все менеджеры приложения.
         * <p>
         * Создаёт и настраивает:
         * 1. FileStorageManager - для работы с файловой системой
         * 3. SoundManager - для воспроизведения фоновой музыки
         * <p>
         * Вызывается перед созданием UI для обеспечения доступности менеджеров.
         */
        public void initializeManagers() {
            // Получаем singleton-экземпляры менеджеров
            activity.fileStorage = FileStorageManager.getInstance(activity);

            // Создаём новый экземпляр SoundManager (не singleton)
            activity.soundManager = new SoundManager();
        }

        /**
         * Инициализирует и запускает фоновую музыку.
         * <p>
         * Выполняет следующие действия:
         * 1. Пытается загрузить звуковой файл по указанному пути
         * 2. При успешной загрузке запускает воспроизведение
         * 3. При неудаче скрывает элементы управления звуком
         *
         * @param soundFilePath Абсолютный путь к звуковому файлу для воспроизведения
         */
        public void initializeSound(String soundFilePath) {
            if (activity.soundManager != null) {
                boolean success = false;

                // Пытаемся инициализировать только если путь не пустой
                if (!soundFilePath.isEmpty()) {
                    success = activity.soundManager.initialize(soundFilePath);
                }

                if (success) {
                    // Успешно загружено - начинаем воспроизведение
                    activity.soundManager.start();
                } else {
                    // Не удалось загрузить звук - скрываем элементы управления
                    activity.uiManager.hideSoundControls();
                }
            }
        }
    }

    //endregion

    /**
     * Фоновая задача для загрузки данных о команде с сайта компании.
     * <p>
     * Выполняет комплексную загрузку и обработку данных:
     * 1. HTTP-запрос к сайту компании для получения HTML с информацией о команде
     * 2. Парсинг HTML с помощью JSoup для извлечения структурированных данных
     * 3. Загрузка изображений сотрудников и иконок групп
     * 4. Сохранение данных в локальное хранилище в формате JSON
     * 5. Обработка ошибок и использование резервных копий при недоступности данных
     * <p>
     * Особенности:
     * - Асинхронное выполнение с защитой от повторного запуска
     * - Поддержка резервных копий данных при ошибках загрузки
     * - Оптимизированная загрузка изображений с кешированием
     * - Корректировка данных для конкретных сотрудников
     */
    public static class DownloadTeamTask {
        // Тег для логирования
        private static final String TAG = "DownloadTeamTask";

        // Флаг для предотвращения одновременного выполнения нескольких экземпляров задачи
        private volatile boolean isRunning = false;

        // Зависимости для работы задачи
        private Context context;                 // Контекст приложения
        private SeanceDataStorage dataStorage;   // Хранилище для сохранения состояния загрузки
        private FileStorageManager fileStorage;  // Менеджер для работы с файловой системой


        /**
         * Конструктор задачи загрузки данных о команде.
         *
         * @param context     Контекст приложения (используется ApplicationContext для предотвращения утечек)
         * @param dataStorage Хранилище данных сеанса для сохранения прогресса и состояния
         * @param fileStorage Менеджер файлового хранилища для сохранения загруженных данных
         */
        public DownloadTeamTask(Context context, SeanceDataStorage dataStorage, FileStorageManager fileStorage) {
            this.context = context.getApplicationContext(); // Используем ApplicationContext для безопасности
            this.dataStorage = dataStorage;
            this.fileStorage = fileStorage;
        }

        /**
         * Основной метод выполнения задачи.
         * <p>
         * Запускает процесс загрузки данных о команде с защитой от повторного запуска.
         * Логирует время выполнения и обрабатывает исключения верхнего уровня.
         */
        public void execute() {
            // Защита от повторного запуска задачи
            if (isRunning) {
                Log.w(TAG, "Загрузка данных о команде уже выполняется - пропускаем");
                return;
            }

            isRunning = true;
            long startTime = System.currentTimeMillis();

            try {
                // Основной процесс загрузки данных
                performTeamDownload();
            } catch (Exception e) {
                // Обработка непредвиденных исключений
                Log.e(TAG, "Ошибка загрузки данных о команде", e);
                handleTeamError(6, "Критическая ошибка загрузки: " + e.getMessage());
            } finally {
                // Сбрасываем флаг выполнения и логируем время работы
                isRunning = false;
                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Завершение загрузки данных о команде, длительность: " + duration + "мс");
            }
        }

        /**
         * Основной процесс загрузки данных о команде.
         * <p>
         * Выполняет последовательность действий:
         * 1. Инициализация состояния загрузки в хранилище
         * 2. HTTP-запрос к сайту компании
         * 3. Парсинг полученного HTML
         * 4. Обработка и сохранение данных
         */
        private void performTeamDownload() {
            // Инициализация состояния загрузки в хранилище данных
            initializeDownloadState();

            try {
                // Выполняем HTTP-запрос для получения HTML страницы с данными о команде
                JSONObject response = requestTeamData();

                if (!response.optBoolean("result", false)) {
                    // Обработка ошибки при запросе
                    handleTeamError(1, response.optString("error"));
                    saveTeamToFile(new JSONObject());
                } else {
                    // Успешный запрос - парсим HTML и обрабатываем данные
                    processTeamHTML(response.optString("html", ""));
                    dataStorage.put("Progress_status", "Выводим данные");
                    updateMessage("Выводим данные");
                }
            } catch (Exception e) {
                // Обработка ошибок в процессе загрузки
                Log.e(TAG, "Ошибка в performTeamDownload", e);
                handleTeamError(7, "Ошибка выполнения загрузки: " + e.getMessage());
            } finally {
                // Фиксируем время окончания загрузки
                dataStorage.put("DownloadTeam_stop", System.currentTimeMillis());
            }
        }

        /**
         * Инициализирует состояние загрузки в хранилище данных.
         * Устанавливает начальные значения для отслеживания прогресса.
         */
        private void initializeDownloadState() {
            dataStorage.put("DownloadTeam_start", System.currentTimeMillis());
            dataStorage.put("DownloadTeam_stop", 0L);
            dataStorage.put("DownloadTeam_errorCode", 0);
            dataStorage.put("DownloadTeam_errorText", "");
            dataStorage.put("DownloadTeam_len", 0);
            dataStorage.put("DownloadTeam_cur", 0);
            dataStorage.put("DownloadTeam_minExpires", 0L);
            dataStorage.put("DownloadTeam_data", "");
            dataStorage.put("DownloadTeam_webView", "");
            dataStorage.put("DownloadTeam_needShow", false);

            // Настройка отображения прогресса в UI
            dataStorage.put("Progress_visible", true);
            dataStorage.put("Progress_status", "Запрос данных о команде");
            updateMessage("Запрос данных о команде");
            dataStorage.put("Progress", 0);
        }

        /**
         * Выполняет HTTP-запрос к сайту компании для получения данных о команде.
         *
         * @return JSONObject с результатом запроса (result, error, html)
         */
        private JSONObject requestTeamData() {
            String urlString = "https://decanter.ru/team";
            HttpURLConnection connection = null;
            boolean result = false;
            String errorText = "";
            String htmlContent = "";

            try {
                // Настройка HTTP-соединения
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);  // Таймаут подключения 15 секунд
                connection.setReadTimeout(15000);     // Таймаут чтения 15 секунд

                // Установка заголовков для имитации браузера
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                connection.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9");
                connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Успешный ответ - читаем содержимое
                    InputStream inputStream = connection.getInputStream();
                    BufferedInputStream bis = new BufferedInputStream(inputStream, 8192);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    // Чтение данных блоками по 8KB
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, bytesRead);
                    }

                    bos.close();
                    bis.close();
                    inputStream.close();

                    // Преобразование байтов в строку UTF-8
                    htmlContent = bos.toString("UTF-8");
                    result = true;
                    Log.d(TAG, "HTML успешно получен, размер: " + htmlContent.length() + " символов");
                } else {
                    // Обработка HTTP ошибок
                    errorText = "HTTP ошибка: " + responseCode;
                    Log.w(TAG, "HTTP ошибка при запросе: " + responseCode);
                }

            } catch (IOException e) {
                // Ошибки сети/соединения
                errorText = "Ошибка соединения: " + e.getMessage();
                Log.e(TAG, "Ошибка соединения с сайтом", e);
            } catch (Exception e) {
                // Прочие непредвиденные ошибки
                errorText = "Неизвестная ошибка: " + e.getMessage();
                Log.e(TAG, "Неизвестная ошибка при запросе", e);
            } finally {
                // Гарантированное закрытие соединения
                if (connection != null) {
                    connection.disconnect();
                }
            }

            // Формирование результата в JSON формате
            JSONObject jsonResult = new JSONObject();
            try {
                jsonResult.put("result", result);
                jsonResult.put("error", errorText);
                jsonResult.put("html", htmlContent);
            } catch (JSONException e) {
                try {
                    // Резервное создание JSON при ошибке
                    jsonResult.put("result", false);
                    jsonResult.put("error", "Ошибка формирования JSON: " + e.getMessage());
                    jsonResult.put("html", "");
                    Log.e(TAG, "Ошибка создания JSON ответа", e);
                } catch (JSONException e2) {
                    Log.e(TAG, "Критическая ошибка JSON", e2);
                }
            }

            return jsonResult;
        }

        /**
         * Парсит HTML страницу и извлекает данные о команде.
         *
         * @param htmlContent HTML контент страницы с информацией о команде
         */
        private void processTeamHTML(String htmlContent) {
            // Проверка валидности полученного контента
            if (htmlContent == null || htmlContent.length() < 100) {
                handleTeamError(2, "Пустой или слишком короткий ответ от сервера");
                saveTeamToFile(new JSONObject());
                return;
            }

            // Обновление прогресса
            dataStorage.put("Progress", 5);
            dataStorage.put("Progress_status", "Данные получены");
            updateMessage("Данные получены");

            JSONObject resultObject = new JSONObject();
            JSONArray teamArray = new JSONArray();
            JSONObject groupsObject = new JSONObject();
            boolean parseError = false;

            try {
                // Парсинг HTML с помощью JSoup
                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(htmlContent);

                // Поиск контейнеров с информацией о команде (исключаем вложенные блоки)
                org.jsoup.select.Elements teamBoxes = doc.select("div.b-info__box:not(div.b-info__block div.b-info__box)");

                dataStorage.put("DownloadTeam_len", teamBoxes.size());
                dataStorage.put("DownloadTeam_cur", 0);

                // Флаг для контроля добавления дублирующегося сотрудника
                boolean addNikita = false;
                int ind = 0;

                // Обработка каждой группы сотрудников
                for (org.jsoup.nodes.Element teamBox : teamBoxes) {
                    // Извлечение иконки группы
                    org.jsoup.nodes.Element groupIconElement = teamBox.selectFirst("div.b-info__caption-image img");

                    if (groupIconElement == null) {
                        org.jsoup.nodes.Element blockElement = teamBox.selectFirst("div.b-info__block");
                        if (blockElement != null) {
                            groupIconElement = blockElement.selectFirst("div.b-info__caption-image img");
                        }
                    }

                    String groupIconUrl = "";
                    String groupIconFileName = "";

                    if (groupIconElement != null) {
                        groupIconUrl = groupIconElement.attr("src");
                        if (!groupIconUrl.isEmpty()) {
                            // Дополнение относительных URL
                            if (!groupIconUrl.startsWith("http")) {
                                groupIconUrl = "https://decanter.ru" + groupIconUrl;
                            }

                            // Извлечение имени файла из URL
                            int lastSlashIndex = groupIconUrl.lastIndexOf('/');
                            if (lastSlashIndex != -1 && lastSlashIndex < groupIconUrl.length() - 1) {
                                groupIconFileName = groupIconUrl.substring(lastSlashIndex + 1);
                            }
                        }
                    }

                    // Извлечение названия группы
                    String groupName = "";
                    org.jsoup.nodes.Element groupTitleElement = teamBox.selectFirst("div.b-info__caption-title");
                    if (groupTitleElement == null) {
                        org.jsoup.nodes.Element blockElement = teamBox.selectFirst("div.b-info__block");
                        if (blockElement != null) {
                            groupTitleElement = blockElement.selectFirst("div.b-info__caption-title");
                        }
                    }
                    if (groupTitleElement != null) {
                        groupName = groupTitleElement.text().trim();
                    }

                    // Извлечение описания группы
                    String groupDescription = "";
                    org.jsoup.nodes.Element groupSubtitleElement = teamBox.selectFirst("div.b-info__caption-subtitle");
                    if (groupSubtitleElement == null) {
                        org.jsoup.nodes.Element blockElement = teamBox.selectFirst("div.b-info__block");
                        if (blockElement != null) {
                            groupSubtitleElement = blockElement.selectFirst("div.b-info__caption-subtitle");
                        }
                    }
                    if (groupSubtitleElement != null) {
                        groupDescription = groupSubtitleElement.text()
                                .replace("&nbsp;", " ")
                                .replace("&mdash;", "—")
                                .trim();
                    }

                    // Поиск элементов сотрудников внутри группы
                    org.jsoup.select.Elements teamItems = teamBox.select("div.b-info__team-item");
                    if (teamItems.isEmpty()) {
                        org.jsoup.nodes.Element blockElement = teamBox.selectFirst("div.b-info__block");
                        if (blockElement != null) {
                            teamItems = blockElement.select("div.b-info__team-item");
                        }
                    }

                    // Обработка группы только если есть и иконка, и сотрудники
                    if (!groupIconFileName.isEmpty() && !teamItems.isEmpty()) {
                        // Сохранение информации о группе
                        JSONObject groupInfo = new JSONObject();
                        groupInfo.put("url", groupIconUrl);
                        groupInfo.put("name", groupName);
                        groupInfo.put("desc", groupDescription);
                        groupsObject.put(groupIconFileName, groupInfo);

                        // Обработка каждого сотрудника в группе
                        for (org.jsoup.nodes.Element teamItem : teamItems) {
                            JSONObject employee = parseTeamItemElement(teamItem, groupIconFileName);
                            if (employee != null) {
                                String cur_group = employee.optString("group");
                                String cur_photo = employee.optString("photo");

                                // Фильтрация дублирующихся сотрудников из бухгалтерии (team-5.svg)
                                if (!(cur_group.contains("team-5.svg") &&
                                        (cur_photo.contains("polina.webp") ||
                                                cur_photo.contains("Tatyana.webp") ||
                                                cur_photo.contains("Oksana.webp")))) {

                                    // Контроль добавления дублирующегося сотрудника "Никита"
                                    if (cur_photo.contains("Nikita.webp")) {
                                        if (!addNikita) {
                                            addNikita = true;
                                            teamArray.put(employee);
                                            ind += 1;
                                        }
                                    } else {
                                        teamArray.put(employee);
                                        ind += 1;
                                    }
                                }
                            }
                        }
                    }
                }

                // Обновление прогресса парсинга
                dataStorage.put("Progress", 10);
                dataStorage.put("Progress_status", "Проверка изображений");
                updateMessage("Проверка изображений");

                // Формирование итогового объекта данных
                resultObject.put("data", teamArray);
                resultObject.put("group", groupsObject);

                Log.d(TAG, "Найдено сотрудников: " + teamArray.length());
                Log.d(TAG, "Найдено групп: " + groupsObject.length());

            } catch (Exception e) {
                Log.e(TAG, "Ошибка парсинга HTML", e);
                handleTeamError(3, "Ошибка парсинга данных о команде: " + e.getMessage());
                parseError = true;
                saveTeamToFile(new JSONObject());
            }

            // Корректировка данных для конкретных сотрудников
            adjustEmployeeData(teamArray);

            // Добавление новых сотрудников вручную
            teamArray = AdditionalEmployeesManager.addAdditionalEmployees(teamArray);


            if (!parseError && teamArray.length() > 0) {
                try {
                    // Загрузка изображений и сохранение финальных данных
                    teamArray = groupByRole(teamArray, "Кавист бутика");
                    teamArray = groupByRole(teamArray, "Менеджер корпоративного отдела");
                    teamArray = groupByRole(teamArray, "Менеджер интернет отдела");
                    teamArray = groupByRole(teamArray, "Ассистент");
                    downloadTeamImages(teamArray, groupsObject);
                    resultObject.put("data", teamArray);
                    saveTeamToFile(resultObject);
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обработки данных о команде", e);
                    handleTeamError(8, "Ошибка обработки данных о команде: " + e.getMessage());
                }
            } else if (!parseError) {
                handleTeamError(9, "Нет данных о сотрудниках для отображения");
            }
        }

        public static JSONArray groupByRole(JSONArray teamArray, String targetRole) {
            if (teamArray == null || targetRole == null || targetRole.isEmpty()) {
                return teamArray;
            }

            try {
                // Преобразуем JSONArray в List для удобства работы
                List<JSONObject> otherEmployees = new ArrayList<>();
                List<JSONObject> targetRoleEmployees = new ArrayList<>();

                // Разделяем сотрудников: с целевой ролью и остальных
                for (int i = 0; i < teamArray.length(); i++) {
                    JSONObject employee = teamArray.getJSONObject(i);
                    String role = employee.optString("role", "").trim();

                    if (role.equals(targetRole)) {
                        targetRoleEmployees.add(employee);
                    } else {
                        otherEmployees.add(employee);
                    }
                }

                Log.d(TAG, "Найдено сотрудников с ролью '" + targetRole + "': " + targetRoleEmployees.size());

                // Если нет сотрудников с целевой ролью, возвращаем исходный массив
                if (targetRoleEmployees.isEmpty()) {
                    return teamArray;
                }

                // Создаем новый отсортированный JSONArray
                JSONArray sortedArray = new JSONArray();

                // 1. Сначала добавляем остальных сотрудников
                for (JSONObject employee : otherEmployees) {
                    sortedArray.put(employee);
                }

                // 2. Затем добавляем всех сотрудников с целевой ролью
                for (JSONObject employee : targetRoleEmployees) {
                    sortedArray.put(employee);
                }

                Log.d(TAG, "Отсортировано: " + targetRoleEmployees.size() + " сотрудников с ролью '" + targetRole + "' перемещены в начало");

                return sortedArray;

            } catch (JSONException e) {
                Log.e(TAG, "Ошибка сортировки по роли: " + e.getMessage(), e);
                return teamArray;
            }
        }

        /**
         * Корректирует данные для конкретных сотрудников.
         * Устанавливает правильные должности и имена для сотрудников с известными фото.
         */
        private void adjustEmployeeData(JSONArray teamArray) {
            for (int i = 0; i < teamArray.length(); i++) {
                try {
                    JSONObject employee = teamArray.getJSONObject(i);
                    String firstName = employee.optString("first_name", "");
                    String secondName = employee.optString("second_name", "");
                    String photoUrl = employee.optString("photo", "");

                    // Корректировка данных на основе имени файла фото
                    if (photoUrl.contains("Oksana.webp")) {
                        // Оксана Леонидовна Роговская
                        employee.put("role", "Главный бухгалтер");
                        employee.put("first_name", "Оксана Леонидовна");
                        employee.put("second_name", "Роговская");
                    } else if (photoUrl.contains("polina.webp")) {
                        // Марина Ильинская
                        employee.put("role", "Помошник бухгалтера");
                        employee.put("second_name", "Прекрасная");
                    } else if (photoUrl.contains("sofia-pleskushkina-2.webp")) {
                        // Софья Плескушкина
                        employee.put("role", "Тестировщик");
                        employee.put("second_name", "Плескушкина");
                    } else if (photoUrl.contains("Tatyana.webp")) {
                        // Татьяна Здоровец
                        employee.put("role", "Бухгалтер");
                        employee.put("second_name", "Здоровец");
                    } else if (photoUrl.contains("Nikita.webp")) {
                        // Никита Кириллов
                        employee.put("role", "Руководитель склада и логистики");
                        employee.put("second_name", "Кириллов");
                    } else if (photoUrl.contains("Ira.webp")) {
                        // Ирина Евдокимова (Загудаева)
                        employee.put("role", "Руководитель IT отдела ");
                        employee.put("second_name", "Загудаева");
                    } else if (photoUrl.contains("Pavel.webp")) {
                        // Павел Куренков
                        employee.put("role", "Главный администратор");
                        employee.put("second_name", "Куренков");
                    } else if (photoUrl.contains("simakova-vika-2.webp")) {
                        // Симакова Виктория
                        employee.put("first_name", "Виктория");
                        employee.put("second_name", "Симакова");
                        employee.put("role", "Проджект менеджер");
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка при дополнении данных сотрудника " + i, e);
                }
            }
        }

        /**
         * Парсит элемент сотрудника из HTML.
         *
         * @param teamItem          HTML элемент с информацией о сотруднике
         * @param groupIconFileName Имя файла иконки группы
         * @return JSONObject с данными сотрудника или null при ошибке
         */
        private JSONObject parseTeamItemElement(org.jsoup.nodes.Element teamItem, String groupIconFileName) {
            try {
                JSONObject employee = new JSONObject();
                employee.put("group", groupIconFileName);

                // Извлечение URL фото из inline стилей
                String style = teamItem.attr("style");
                String photoUrl = extractUrlFromStyle(style);
                if (!photoUrl.isEmpty()) {
                    employee.put("photo", photoUrl);
                }

                // Извлечение имени и фамилии
                org.jsoup.nodes.Element nameElement = teamItem.selectFirst(".b-info__team-name");
                if (nameElement != null) {
                    String fullName = nameElement.text().replace("<br>", " ").replace("\n", " ").trim();
                    String[] nameParts = fullName.split("\\s+");

                    if (nameParts.length >= 1) {
                        String firstName = nameParts[0];
                        employee.put("first_name", firstName);

                        if (nameParts.length >= 2) {
                            employee.put("second_name", nameParts[1]);
                        } else {
                            // Извлечение фамилии из URL фото если не указана в HTML
                            String secondName = getSecondNameFromPhotoUrl(photoUrl, firstName);
                            employee.put("second_name", secondName);

                            // Специальная обработка для сотрудника Оксаны
                            if (firstName.equals("Оксана") && photoUrl.contains("Oksana.jpg")) {
                                employee.put("first_name", "Оксана Леонидовна");
                            }
                        }
                    }
                }

                // Извлечение должности
                org.jsoup.nodes.Element postElement = teamItem.selectFirst(".b-info__team-post");
                if (postElement != null) {
                    employee.put("role", postElement.text().trim());
                } else {
                    employee.put("role", "");
                }

                return employee;

            } catch (JSONException e) {
                Log.e(TAG, "Ошибка создания JSON для сотрудника", e);
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Ошибка парсинга элемента сотрудника", e);
                return null;
            }
        }

        /**
         * Определяет фамилию сотрудника по URL его фотографии.
         *
         * @param photoUrl  URL фотографии сотрудника
         * @param firstName Имя сотрудника (для дополнительной проверки)
         * @return Фамилия сотрудника или пустая строка если не определена
         */
        private String getSecondNameFromPhotoUrl(String photoUrl, String firstName) {
            if (photoUrl == null || photoUrl.isEmpty()) {
                return "";
            }

            // Сопоставление конкретных URL с фамилиями
            switch (photoUrl) {
                case "https://static.decanter.ru/local/templates/main2018/images/content/team/Oksana.jpg":
                    return "Роговская";

                case "https://static.decanter.ru/local/templates/main2018/images/content/team/Tatyana.jpg":
                    return "Здоровец";

                case "https://static.decanter.ru/local/templates/main2018/images/content/team/Nikita.jpg":
                    return "Кириллов";

                case "https://static.decanter.ru/local/templates/main2018/images/content/team/Pavel.jpg":
                    return "Куренков";

                case "https://static.decanter.ru/local/templates/main2018/images/content/team/Ira.jpg":
                    return "Евдокимова";

                default:
                    return "";
            }
        }

        /**
         * Извлекает URL из inline CSS стилей.
         *
         * @param style Строка CSS стилей
         * @return Извлеченный URL или пустая строка
         */
        private String extractUrlFromStyle(String style) {
            if (style == null || style.isEmpty()) {
                return "";
            }

            int startIdx = style.indexOf("background-image: url('");
            if (startIdx == -1) {
                startIdx = style.indexOf("background-image: url(\"");
            }

            if (startIdx != -1) {
                startIdx = style.indexOf("'", startIdx);
                if (startIdx == -1) {
                    startIdx = style.indexOf("\"", startIdx);
                }

                if (startIdx != -1) {
                    startIdx++;
                    int endIdx = style.indexOf("'", startIdx);
                    if (endIdx == -1) {
                        endIdx = style.indexOf("\"", startIdx);
                    }

                    if (endIdx != -1) {
                        String url = style.substring(startIdx, endIdx);
                        if (!url.startsWith("http")) {
                            url = "https://decanter.ru" + url;
                        }
                        return url;
                    }
                }
            }

            return "";
        }

        /**
         * Загружает изображения сотрудников и иконок групп.
         *
         * @param teamArray    Массив данных сотрудников
         * @param groupsObject Объект с данными групп
         */
        private void downloadTeamImages(JSONArray teamArray, JSONObject groupsObject) {
            // Установка времени жизни кеша (7 дней)
            long expires = System.currentTimeMillis() + 7 * 24 * 3600000;
            int downloadedCount = 0;
            int totalItems = teamArray.length() + groupsObject.length();

            if (totalItems == 0) {
                Log.w(TAG, "Нет данных для загрузки изображений");
                return;
            }

            dataStorage.put("DownloadTeam_cur", 0);

            try {
                updateMessage("Загрузка иконок (" + groupsObject.length() + ")");
                // Загрузка иконок групп
                Iterator<String> groupKeys = groupsObject.keys();
                while (groupKeys.hasNext()) {
                    String groupFileName = groupKeys.next();
                    JSONObject groupInfo = groupsObject.getJSONObject(groupFileName);

                    if (groupInfo.has("url")) {
                        String groupImageUrl = groupInfo.getString("url");

                        if (!groupImageUrl.isEmpty()) {
                            JSONObject groupFileResult = fileStorage.getOrCreateFile(
                                    groupImageUrl,
                                    FileStorageManager.STORAGE_WORKING,
                                    "team",
                                    expires,
                                    null
                            );

                            if (groupFileResult.optBoolean("success")) {
                                String localGroupPath = groupFileResult.getString("path");
                                groupInfo.put("url", localGroupPath);

                                downloadedCount++;
                                updateProgress(downloadedCount, totalItems, "иконок групп");
                                updateExpiresTime(groupFileResult);

                                Log.d(TAG, "Загружена иконка группы: " + groupImageUrl);
                            } else {
                                Log.w(TAG, "Не удалось загрузить иконку группы: " + groupImageUrl);
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Ошибка JSON при обработке групп", e);
            }

            updateMessage("Загрузка фотографий (" + teamArray.length() + ")");
            // Загрузка фотографий сотрудников
            for (int i = 0; i < teamArray.length(); i++) {
                try {
                    JSONObject employee = teamArray.getJSONObject(i);

                    if (employee.has("photo")) {
                        String imageUrl = employee.getString("photo");

                        JSONObject fileResult = fileStorage.getOrCreateFile(
                                imageUrl,
                                FileStorageManager.STORAGE_WORKING,
                                "team",
                                expires,
                                employee.has("file") ? employee.getString("file") : null
                        );

                        if (fileResult.optBoolean("success")) {
                            String localPath = fileResult.getString("path");
                            employee.put("photo", localPath);
                            downloadedCount++;

                            updateProgress(downloadedCount, totalItems, "данных");
                            updateExpiresTime(fileResult);

                            Log.d(TAG, "Загружена фотография " + downloadedCount + "/" + totalItems + ": " + imageUrl);
                        } else {
                            Log.w(TAG, "Не удалось загрузить фотографию: " + imageUrl);
                        }
                    }

                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка JSON при обработке сотрудника " + i, e);
                } catch (Exception e) {
                    Log.e(TAG, "Неизвестная ошибка при загрузке изображения сотрудника " + i, e);
                }
            }
        }

        /**
         * Обновляет отображение прогресса загрузки.
         */
        private void updateProgress(int downloadedCount, int totalItems, String type) {
            dataStorage.put("DownloadTeam_cur", downloadedCount);
            dataStorage.put("Progress_status",
                    "Загрузка " + type + " (" + downloadedCount + " из " + totalItems + ")");


            // Расчет прогресса: 10% за парсинг + 60% за загрузку изображений
            int progress = 10 + Math.round((float) downloadedCount / totalItems * 60);
            dataStorage.put("Progress", progress);
        }

        /**
         * Обновляет минимальное время истечения кеша.
         */
        private void updateExpiresTime(JSONObject fileResult) {
            long curExpires = fileResult.optLong("expires");
            long currentMinExpires = dataStorage.getLong("DownloadTeam_minExpires");
            if (currentMinExpires == 0L || currentMinExpires > curExpires) {
                dataStorage.put("DownloadTeam_minExpires", curExpires);
            }
        }

        /**
         * Сохраняет итоговые данные о команде в файл.
         *
         * @param resultObject Объект с данными о команде
         */
        private void saveTeamToFile(JSONObject resultObject) {
            try {
                String jsonString = "";
                long html_expires = System.currentTimeMillis() + 24 * 3600000; // 24 часа
                long minxExpires = dataStorage.getLong("DownloadTeam_minExpires");
                int downloadedCount = dataStorage.getInt("DownloadTeam_cur");

                // Проверка наличия данных
                if (!resultObject.has("data") || resultObject.getJSONArray("data").length() == 0) {
                    Log.w(TAG, "Нет данных о сотрудниках. Используем резервную копию.");

                    // Попытка восстановления из резервной копии
                    JSONObject restoreFile = fileStorage.getFileAsText(
                            fileStorage.STORAGE_WORKING,
                            "team.json",
                            "team",
                            "UTF-8"
                    );

                    if (restoreFile.optBoolean("success")) {
                        dataStorage.put("Progress_status", "Восстановили резервную копию");
                        updateMessage("Восстановили резервную копию");
                        handleTeamError(0, "");
                        jsonString = restoreFile.getString("text");

                        JSONObject path_response = fileStorage.getFile(
                                fileStorage.STORAGE_WORKING,
                                "team.json",
                                "team"
                        );
                        dataStorage.put("DownloadTeam_data", path_response.optString("path"));

                        Log.d(TAG, "Используем резервную копию данных о команде");
                    } else {
                        jsonString = new JSONObject().toString();
                        Log.w(TAG, "Резервная копия не найдена, создаем пустой объект");
                    }
                } else {
                    // Сохранение новых данных
                    jsonString = resultObject.toString();
                    JSONObject team_response = fileStorage.createFile(
                            fileStorage.STORAGE_WORKING,
                            "team",
                            "team.json",
                            minxExpires,
                            jsonString,
                            "UTF-8"
                    );

                    if (team_response.optBoolean("success")) {
                        dataStorage.put("DownloadTeam_data", team_response.optString("path"));
                        Log.d(TAG, "Новые данные о команде сохранены");
                    } else {
                        dataStorage.put("DownloadTeam_cur", 0);
                        handleTeamError(4, "Не удалось сохранить новые данные");
                        saveTeamToFile(new JSONObject());
                        return;
                    }
                }

                if (jsonString.isEmpty()) {
                    handleTeamError(5, "Не удалось получить данные для показа");
                    return;
                }

                // Завершающая стадия - подготовка данных для отображения
                dataStorage.put("Progress", 70);
                dataStorage.put("Progress_status", "Подготовка данных");
                updateMessage("Подготовка данных");

                // Генерация HTML для WebView (пустая в текущей реализации)
                dataStorage.put("DownloadTeam_needShow", true);

                Log.d(TAG, "Все данные о команде подготовлены");

            } catch (JSONException e) {
                Log.e(TAG, "Ошибка формата JSON при сохранении", e);
                handleTeamError(10, "Ошибка формата данных: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Неизвестная ошибка при сохранении", e);
                handleTeamError(8, "Ошибка сохранения данных: " + e.getMessage());
            }
        }

        /**
         * Обрабатывает ошибки загрузки данных.
         *
         * @param errorCode Код ошибки
         * @param errorText Текст ошибки
         */
        private void handleTeamError(int errorCode, String errorText) {
            dataStorage.put("DownloadTeam_errorCode", errorCode);
            dataStorage.put("DownloadTeam_errorText", errorText);
            dataStorage.put("Progress", 100);

            if (errorCode > 0) {
                Log.e(TAG, "Ошибка загрузки данных о команде [код " + errorCode + "]: " + errorText);
            } else {
                Log.i(TAG, "Восстановили резервную копию данных о команде");
            }
        }

        /**
         * Менеджер для добавления дополнительных сотрудников, отсутствующих на основном сайте.
         * Добавляет сотрудников, которые по каким-либо причинам не отображаются на сайте компании.
         * Использует алгоритм предотвращения дублирования при добавлении.
         */
        public static class AdditionalEmployeesManager {
            private static final String TAG = "AdditionalEmployeesManager";

            /**
             * Структура для хранения данных дополнительного сотрудника.
             * Используется для удобного хранения имени, фамилии и JSON-объекта с данными.
             */
            private static class EmployeeData {
                String firstName;
                String secondName;
                JSONObject data;

                EmployeeData(String firstName, String secondName, JSONObject data) {
                    this.firstName = firstName;
                    this.secondName = secondName;
                    this.data = data;
                }
            }

            /**
             * Список дополнительных сотрудников для добавления.
             * Каждый сотрудник представлен объектом EmployeeData с именем, фамилией и JSON-данными.
             */
            private static final List<EmployeeData> ADDITIONAL_EMPLOYEES = Arrays.asList(
                    new EmployeeData("Василий", "Воропай", createVasilyVoropay()),
                    new EmployeeData("Иван", "Оленев", createIvanOlenev()),
                    new EmployeeData("Эдуард", "Киль", createEduardKehl()),
                    new EmployeeData("Андрей", "Артемьев", createAndreyArtemyev()),
                    new EmployeeData("Алексей", "Грышко", createAlexeyGryshko())
            );

            /**
             * Интеллектуальное добавление дополнительных сотрудников в существующий массив.
             * Проверяет наличие сотрудников по имени и фамилии, предотвращая дублирование.
             *
             * @param existingEmployees Существующий JSONArray с данными сотрудников
             * @return Обновленный JSONArray с добавленными сотрудниками
             */
            public static JSONArray addAdditionalEmployees(JSONArray existingEmployees) {
                JSONArray result = new JSONArray();
                Set<String> existingEmployeeKeys = new HashSet<>();

                try {
                    // Собираем ключи существующих сотрудников для предотвращения дублирования
                    for (int i = 0; i < existingEmployees.length(); i++) {
                        JSONObject employee = existingEmployees.getJSONObject(i);
                        String key = createEmployeeKey(employee);
                        existingEmployeeKeys.add(key);
                        result.put(employee); // Сохраняем существующих сотрудников
                    }

                    // Добавляем дополнительных сотрудников, если их еще нет в списке
                    for (EmployeeData additional : ADDITIONAL_EMPLOYEES) {
                        String key = createKey(additional.firstName, additional.secondName);

                        if (!existingEmployeeKeys.contains(key)) {
                            result.put(additional.data);
                            Log.d(TAG, "Добавлен сотрудник: " + additional.firstName + " " + additional.secondName);
                        } else {
                            Log.d(TAG, "Сотрудник уже существует: " + additional.firstName + " " + additional.secondName);
                        }
                    }

                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка при добавлении дополнительных сотрудников", e);
                    return existingEmployees; // Возвращаем исходный массив при ошибке
                }

                return result;
            }

            /**
             * Создает уникальный ключ для сотрудника на основе имени и фамилии.
             * Используется для проверки наличия сотрудника в списке.
             *
             * @param employee JSON-объект с данными сотрудника
             * @return Уникальный ключ в формате "имя|фамилия"
             */
            private static String createEmployeeKey(JSONObject employee) throws JSONException {
                String firstName = employee.optString("first_name", "").trim().toLowerCase();
                String secondName = employee.optString("second_name", "").trim().toLowerCase();
                return createKey(firstName, secondName);
            }

            /**
             * Формирует ключ из имени и фамилии.
             * Приводит строки к нижнему регистру для регистронезависимого сравнения.
             */
            private static String createKey(String firstName, String secondName) {
                return firstName.toLowerCase() + "|" + secondName.toLowerCase();
            }

            /**
             * Создает JSON-объект для сотрудника Василий Воропай.
             * Кавист бутика в команде розничных продаж.
             */
            private static JSONObject createVasilyVoropay() {
                try {
                    JSONObject employee = new JSONObject();
                    employee.put("group", "team-2.svg");
                    employee.put("photo", "https://drive.usercontent.google.com/download?id=1L04w_3cw3SXyXU2GAOAjXca4XJN1rtxK&export=download&authuser=0");
                    employee.put("first_name", "Василий");
                    employee.put("file", "Vasily_Voropay.jpg");
                    employee.put("second_name", "Воропай");
                    employee.put("role", "Кавист бутика");
                    return employee;
                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка создания данных для Василий Воропай", e);
                    return new JSONObject();
                }
            }

            /**
             * Создает JSON-объект для сотрудника Иван Оленев.
             * Кавист бутика в команде розничных продаж.
             */
            private static JSONObject createIvanOlenev() {
                try {
                    JSONObject employee = new JSONObject();
                    employee.put("group", "team-2.svg");
                    employee.put("photo", "https://drive.usercontent.google.com/download?id=1NA5infWyF0HbQ2T7IfvRe9XDAHk0ngi9&export=download&authuser=0");
                    employee.put("first_name", "Иван");
                    employee.put("file", "Ivan_Olenev.jpg");
                    employee.put("second_name", "Оленев");
                    employee.put("role", "Кавист бутика");
                    return employee;
                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка создания данных для Иван Оленев", e);
                    return new JSONObject();
                }
            }

            /**
             * Создает JSON-объект для сотрудника Эдуард Киль.
             * Кавист бутика в команде розничных продаж.
             */
            private static JSONObject createEduardKehl() {
                try {
                    JSONObject employee = new JSONObject();
                    employee.put("group", "team-2.svg");
                    employee.put("photo", "https://drive.usercontent.google.com/download?id=1d04aWffQNVMprr5b_9crj1QmulGq5AOg&export=download&authuser=0");
                    employee.put("first_name", "Эдуард");
                    employee.put("file", "Eduard_Kehl.jpg");
                    employee.put("second_name", "Киль");
                    employee.put("role", "Кавист бутика");
                    return employee;
                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка создания данных для Эдуард Киль", e);
                    return new JSONObject();
                }
            }

            /**
             * Создает JSON-объект для сотрудника Андрей Артемьев.
             * Фронтенд разработчик в IT-отделе.
             */
            private static JSONObject createAndreyArtemyev() {
                try {
                    JSONObject employee = new JSONObject();
                    employee.put("group", "team-4.svg");
                    employee.put("photo", "https://drive.usercontent.google.com/download?id=1nts4NE1GsgYRGJJMyNHHiAPE7scIY4Mt&export=download&authuser=0");
                    employee.put("file", "Andrey_b24.jpg");
                    employee.put("first_name", "Андрей");
                    employee.put("second_name", "Артемьев");
                    employee.put("role", "Фронтенд разработчик");
                    return employee;
                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка создания данных для Андрей Артемьев", e);
                    return new JSONObject();
                }
            }

            /**
             * Создает JSON-объект для сотрудника Алексей Грышко.
             * Бэкенд разработчик в IT-отделе.
             */
            private static JSONObject createAlexeyGryshko() {
                try {
                    JSONObject employee = new JSONObject();
                    employee.put("group", "team-4.svg");
                    employee.put("photo", "https://drive.usercontent.google.com/download?id=185tJuV7WUCncwsaXEcNFuf4VpzFt6585&export=download&authuser=0");
                    employee.put("file", "Alexey_1c.jpg");
                    employee.put("first_name", "Алексей");
                    employee.put("second_name", "Грышко");
                    employee.put("role", "Бэкенд разработчик");
                    return employee;
                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка создания данных для Алексей Грышко", e);
                    return new JSONObject();
                }
            }
        }

        /** Логирование выполнения в сообщении
         *
         */
        private void updateMessage(String message) {

            dataStorage.put("MessageText", dataStorage.getString("MessageText") + "\n"  + message);
            dataStorage.put("MessageCloseIn", System.currentTimeMillis() + 60000L);

        }
    }
}