/** Активность для отображения слайд-шоу с HTML-контентом
 *
 * Основные возможности:
 * - Отображение HTML-файлов в полноэкранном режиме
 * - Воспроизведение фоновой музыки с управлением громкостью
 * - Горизонтальная ориентация экрана
 * - Сохранение и восстановление настроек между сеансами
 * - Edge-to-edge отображение контента
 *
 * Особенности:
 * - Управление звуком через текстовую панель в нижнем правом углу
 * - Независимая регулировка громкости приложения
 * - Визуальная индикация уровня громкости
 * - Автоматическое масштабирование контента
 *
 * Автор: Алексей smolpochta
 * Электронная почта: smolpochta@gmail.com
 * Дата: 2025
 */

//region Переменные

// Пакет приложения
package com.smolpochta.decanter;

// Android Framework
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

// AndroidX
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;

// JSON обработка
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

// Java стандартная библиотека
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

//endregion

/** Основная активность для отображения слайд-шоу
 * Реализует полноэкранный режим с HTML-контентом и фоновой музыкой
 */
public class SlideShowActivity extends AppCompatActivity {

    //region Переменные класса

    //region UI компоненты
    private FrameLayout container;
    //endregion

    //region Управляющие классы
    private WebViewManager webViewManager;
    private SoundManager soundManager;
    private FileStorageManager fileStorage;
    private VibrationManager vibrationManager;
    private SoundUIManager soundUIManager;
    private SettingsManager settingsManager;
    private UiUtils uiUtils;
    private AnimationManager animationManager;
    //endregion

    //region Конфигурация
    private String htmlFilePath = "";
    private String soundFilePath = "";
    //endregion

    //region Интерфейсы
    public interface OnPageReadyListener {
        void onPageReady();
    }

    public interface VolumeChangeCallback {
        void onVolumeChanged(int volume);
    }
    //endregion

    //endregion

    //region Обработчики жизненного цикла

    /**
     * Вызывается при создании активности
     * Инициализирует основные компоненты и настраивает интерфейс
     *
     * @param savedInstanceState сохраненное состояние активности
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Настройка прозрачности окна
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().setDimAmount(0f);

        initializeActivityConfiguration();
        initializeManagers();
        loadSettings();
        setupUI();
        initializeSound();
    }

    /**
     * Вызывается при приостановке активности
     * Сохраняет состояние и приостанавливает воспроизведение
     */
    @Override
    protected void onPause() {
        super.onPause();

        if (webViewManager != null) {
            webViewManager.pause();
        }

        if (soundManager != null) {
            soundManager.pause();
        }

        if (settingsManager != null && soundManager != null) {
            settingsManager.saveSettings(soundManager.getVolume());
        }
    }

    /**
     * Вызывается при возобновлении активности
     * Восстанавливает воспроизведение медиаконтента
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (webViewManager != null) {
            webViewManager.resume();
        }

        if (soundManager != null && soundManager.isInitialized()) {
            soundManager.start();
        }
    }

    /**
     * Вызывается при уничтожении активности
     * Освобождает все занятые ресурсы
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        deleteSeanceDataFile();

        if (soundManager != null) {
            soundManager.cleanup();
        }

        if (webViewManager != null) {
            webViewManager.destroy();
        }

        // Очистка менеджеров UI
        if (soundUIManager != null) {
            soundUIManager.cleanup();
        }
    }

    /**
     * Обрабатывает нажатие кнопки "Назад"
     * Сохраняет настройки и возвращает в MainActivity
     */
    @Override
    public void onBackPressed() {
        if (settingsManager != null && soundManager != null) {
            settingsManager.saveSettings(soundManager.getVolume());
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();

        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    //endregion

    //region Методы жизненного цикла (вспомогательные)

    /**
     * Инициализирует базовую конфигурацию активности
     */
    private void initializeActivityConfiguration() {
        // Установка горизонтальной ориентации
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Скрытие ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Включение полноэкранного режима
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        enableEdgeToEdge();
    }

    /**
     * Включает edge-to-edge отображение для современного внешнего вида
     * Позволяет контенту отображаться за системными барами
     */
    private void enableEdgeToEdge() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setStatusBarColor(Color.TRANSPARENT);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                getWindow().getDecorView().setSystemUiVisibility(
                        getWindow().getDecorView().getSystemUiVisibility() |
                                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            );
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /**
     * Инициализирует менеджеры активности
     */
    private void initializeManagers() {
        fileStorage = FileStorageManager.getInstance(this);
        vibrationManager = VibrationManager.getInstance(this);
        webViewManager = new WebViewManager();
        soundManager = new SoundManager();

        // Инициализация новых менеджеров
        uiUtils = new UiUtils(this);
        animationManager = new AnimationManager();
        settingsManager = new SettingsManager(fileStorage);
        soundUIManager = new SoundUIManager(this);
    }

    /**
     * Удаляет файл данных сеанса MainActivity
     */
    private void deleteSeanceDataFile() {
        try {
            String filename = "SeanceDataStorage_MainActivity.json";
            File file = new File(this.getFilesDir(), filename);

            if (file.exists()) {
                boolean deleted = file.delete();
                // Логирование результата удаления
            }
        } catch (SecurityException e) {
            // Обработка ошибки безопасности
        } catch (Exception e) {
            // Обработка общей ошибки
        }
    }

    //endregion

    //region UI Setup Methods

    /** Создает и настраивает весь пользовательский интерфейс активности
     */
    private void setupUI() {
        container = uiUtils.createMainContainer();
        container.setAlpha(0f);

        setupWebView(container);
        setupSoundControls();
        setContentView(container);

        loadHtmlContent();
    }

    /** Настраивает WebView для отображения HTML контента
     *
     * @param container контейнер для добавления WebView
     */
    private void setupWebView(FrameLayout container) {
        if (webViewManager != null) {
            webViewManager.setupWebViewWithListener(container, new OnPageReadyListener() {
                @Override
                public void onPageReady() {
                    animationManager.startEntranceAnimation(container);
                }
            });
        }
    }

    /** Создает и настраивает панель управления звуком
     */
    private void setupSoundControls() {
        FrameLayout soundControls = soundUIManager.createSoundControls();
        container.addView(soundControls);
        soundUIManager.updateVolumeDisplay(soundManager.getVolume());

        // Установка обработчиков кликов
        soundUIManager.setVolumeUpClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleVolumeIncrease();
            }
        });

        soundUIManager.setVolumeDownClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleVolumeDecrease();
            }
        });
    }

    /** Загружает HTML контент через WebViewManager
     */
    private void loadHtmlContent() {
        if (webViewManager != null) {
            webViewManager.loadHtmlContent(htmlFilePath, fileStorage);
        }
    }

    //endregion

    //region Event Handlers

    /** Обрабатывает действие увеличения громкости
     */
    private void handleVolumeIncrease() {
        vibrationManager.vibrate(VibrationManager.TYPE_WORK_ALLOWED);
        if (soundManager != null) {
            soundManager.increaseVolume();
            soundUIManager.showVolumeTemporarily();
            settingsManager.saveSettings(soundManager.getVolume());
        }
    }

    /** Обрабатывает действие уменьшения громкости
     */
    private void handleVolumeDecrease() {
        vibrationManager.vibrate(VibrationManager.TYPE_WORK_ALLOWED);
        if (soundManager != null) {
            soundManager.decreaseVolume();
            soundUIManager.showVolumeTemporarily();
            settingsManager.saveSettings(soundManager.getVolume());
        }
    }

    //endregion

    //region Initialization Methods

    /** Инициализирует звуковую систему
     */
    private void initializeSound() {
        if (soundManager != null) {
            boolean success = false;
            if (!soundFilePath.isEmpty()) {
                success = soundManager.initialize(soundFilePath);
            }
            if (success) {
                soundManager.start();
            } else {
                soundUIManager.hideSoundControls();
            }
        }
    }

    /** Загружает настройки приложения из файла
     */
    private void loadSettings() {
        settingsManager.loadSettings();
        htmlFilePath = settingsManager.getHtmlFilePath();
        soundFilePath = settingsManager.getSoundFilePath();

        if (soundManager != null) {
            soundManager.setVolume(settingsManager.getSoundVolume());
        }
    }

    //endregion

    /**
     * Менеджер для управления UI элементами звука
     * Отвечает за создание и управление элементами управления звуком
     */
    private class SoundUIManager {
        private FrameLayout soundControlsContainer;
        private TextView volumeUpText;
        private TextView volumeDownText;
        private TextView volumeText;
        private Context context;

        /**
         * Конструктор менеджера UI звука
         *
         * @param context контекст приложения
         */
        public SoundUIManager(Context context) {
            this.context = context;
        }

        /**
         * Создает контейнер для управления звуком
         *
         * @return FrameLayout контейнер для элементов управления звуком
         */
        public FrameLayout createSoundControls() {
            soundControlsContainer = new FrameLayout(context);
            FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END
            );
            int margin = uiUtils.dpToPx(16);
            containerParams.setMargins(0, 0, margin, margin);
            soundControlsContainer.setLayoutParams(containerParams);

            setupVolumeText(soundControlsContainer);
            setupVolumeDownText(soundControlsContainer);
            setupVolumeUpText(soundControlsContainer);

            return soundControlsContainer;
        }

        /**
         * Создает и настраивает кнопку увеличения громкости
         *
         * @param container контейнер для добавления кнопки
         */
        private void setupVolumeUpText(FrameLayout container) {
            volumeUpText = new TextView(context);
            uiUtils.configureTextViewAppearance(volumeUpText, 16);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    uiUtils.dpToPx(80), uiUtils.dpToPx(40),
                    Gravity.BOTTOM | Gravity.END
            );
            params.setMargins(0, 0, uiUtils.dpToPx(85), 0);
            volumeUpText.setLayoutParams(params);
            volumeUpText.setText("ЗВУК +");

            container.addView(volumeUpText);
        }

        /**
         * Создает и настраивает кнопку уменьшения громкости
         *
         * @param container контейнер для добавления кнопки
         */
        private void setupVolumeDownText(FrameLayout container) {
            volumeDownText = new TextView(context);
            uiUtils.configureTextViewAppearance(volumeDownText, 16);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    uiUtils.dpToPx(80), uiUtils.dpToPx(40),
                    Gravity.BOTTOM | Gravity.END
            );
            params.setMargins(0, 0, 0, 0);
            volumeDownText.setLayoutParams(params);
            volumeDownText.setText("ЗВУК -");

            container.addView(volumeDownText);
        }

        /**
         * Создает и настраивает текстовое поле для отображения уровня громкости
         *
         * @param container контейнер для добавления текстового поля
         */
        private void setupVolumeText(FrameLayout container) {
            volumeText = new TextView(context);
            volumeText.setTextSize(16);
            volumeText.setTextColor(Color.WHITE);
            volumeText.setBackgroundColor(Color.TRANSPARENT);
            volumeText.setGravity(Gravity.CENTER);
            volumeText.setPadding(uiUtils.dpToPx(8), uiUtils.dpToPx(4), uiUtils.dpToPx(8), uiUtils.dpToPx(4));
            volumeText.setTypeface(null, Typeface.BOLD);
            volumeText.setAlpha(0f);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    uiUtils.dpToPx(120), uiUtils.dpToPx(40),
                    Gravity.BOTTOM | Gravity.END
            );
            params.setMargins(0, 0, uiUtils.dpToPx(170), 0);
            volumeText.setLayoutParams(params);
            container.addView(volumeText);
        }

        /**
         * Устанавливает обработчик клика для кнопки увеличения громкости
         *
         * @param listener слушатель кликов
         */
        public void setVolumeUpClickListener(View.OnClickListener listener) {
            if (volumeUpText != null) {
                volumeUpText.setOnClickListener(listener);
            }
        }

        /**
         * Устанавливает обработчик клика для кнопки уменьшения громкости
         *
         * @param listener слушатель кликов
         */
        public void setVolumeDownClickListener(View.OnClickListener listener) {
            if (volumeDownText != null) {
                volumeDownText.setOnClickListener(listener);
            }
        }

        /**
         * Обновляет отображение уровня громкости
         *
         * @param volume уровень громкости от 0 до 100
         */
        public void updateVolumeDisplay(int volume) {
            if (volumeText != null) {
                String text = volume == 100 ? "МАКСИМУМ" : volume == 0 ? "БЕЗ ЗВУКА" : volume + "%";
                volumeText.setText(text);
            }
        }

        /**
         * Временно отображает текстовое поле с уровнем громкости
         */
        public void showVolumeTemporarily() {
            if (volumeText != null) {
                animationManager.showTemporaryVolumeText(volumeText);
                updateVolumeDisplay(soundManager.volume);
            }
        }

        /**
         * Скрывает все элементы управления звуком
         */
        public void hideSoundControls() {
            if (volumeUpText != null) {
                volumeUpText.setVisibility(View.GONE);
            }
            if (volumeDownText != null) {
                volumeDownText.setVisibility(View.GONE);
            }
            if (volumeText != null) {
                volumeText.setVisibility(View.GONE);
            }
        }

        /**
         * Освобождает ресурсы
         */
        public void cleanup() {
            volumeUpText = null;
            volumeDownText = null;
            volumeText = null;
            soundControlsContainer = null;
        }
    }

    /**
     * Менеджер для управления настройками приложения
     * Отвечает за загрузку и сохранение настроек
     */
    private class SettingsManager {
        private FileStorageManager fileStorage;
        private String htmlFilePath = "";
        private String soundFilePath = "";
        private int soundVolume = 10;

        /**
         * Конструктор менеджера настроек
         *
         * @param fileStorage менеджер файлового хранилища
         */
        public SettingsManager(FileStorageManager fileStorage) {
            this.fileStorage = fileStorage;
        }

        /**
         * Загружает настройки приложения из файла
         */
        public void loadSettings() {
            try {
                JSONObject settings = fileStorage.getParams(
                        FileStorageManager.STORAGE_WORKING,
                        "settings.json",
                        "banners",
                        null,
                        null
                );

                if (settings != null && settings.length() > 0) {
                    applyLoadedSettings(settings);
                } else {
                    applyDefaultSettings();
                }

            } catch (Exception e) {
                applyDefaultSettings();
            }
        }

        /**
         * Применяет загруженные настройки
         *
         * @param settings JSON объект с настройками
         */
        private void applyLoadedSettings(JSONObject settings) {
            htmlFilePath = settings.optString("path", "");
            soundFilePath = settings.optString("sound", "");
            soundVolume = settings.optInt("sound_volume", 10);
        }

        /**
         * Применяет настройки по умолчанию
         */
        private void applyDefaultSettings() {
            htmlFilePath = "";
            soundFilePath = "";
            soundVolume = 10;
        }

        /**
         * Сохраняет текущие настройки в файл
         *
         * @param soundVolume уровень громкости для сохранения
         */
        public void saveSettings(int soundVolume) {
            try {
                JSONObject param_in = new JSONObject();
                param_in.put("sound_volume", soundVolume);

                fileStorage = fileStorage.getInstance(SlideShowActivity.this);
                fileStorage.getParams(
                        FileStorageManager.STORAGE_WORKING,
                        "settings.json",
                        "banners",
                        param_in,
                        null
                );
            } catch (JSONException e) {
                // Обработка ошибки сохранения настроек
            }
        }

        /**
         * Возвращает путь к HTML файлу
         *
         * @return путь к HTML файлу
         */
        public String getHtmlFilePath() {
            return htmlFilePath;
        }

        /**
         * Возвращает путь к звуковому файлу
         *
         * @return путь к звуковому файлу
         */
        public String getSoundFilePath() {
            return soundFilePath;
        }

        /**
         * Возвращает уровень громкости
         *
         * @return уровень громкости от 0 до 100
         */
        public int getSoundVolume() {
            return soundVolume;
        }
    }

    /**
     * Утилиты для работы с UI
     * Содержит общие методы для работы с пользовательским интерфейсом
     */
    private class UiUtils {
        private Context context;

        /**
         * Конструктор утилит UI
         *
         * @param context контекст приложения
         */
        public UiUtils(Context context) {
            this.context = context;
        }

        /**
         * Конвертирует значения из dp в пиксели
         *
         * @param dp значение в density-independent pixels
         * @return значение в пикселях
         */
        public int dpToPx(int dp) {
            float density = context.getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }

        /**
         * Настраивает внешний вид текстовых элементов управления
         *
         * @param textView текстовый элемент для настройки
         * @param textSize размер текста в sp
         */
        public void configureTextViewAppearance(TextView textView, int textSize) {
            textView.setTextSize(textSize);
            textView.setTextColor(Color.WHITE);
            textView.setBackgroundColor(Color.TRANSPARENT);
            textView.setGravity(Gravity.CENTER);
            textView.setAlpha(0.5f);
            textView.setElevation(dpToPx(10));
            textView.setTypeface(null, Typeface.BOLD);
            textView.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        }

        /**
         * Создает основной контейнер для размещения всех элементов интерфейса
         *
         * @return FrameLayout основной контейнер
         */
        public FrameLayout createMainContainer() {
            FrameLayout container = new FrameLayout(context);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            container.setClickable(false);
            container.setFocusable(false);
            return container;
        }
    }

    /**
     * Менеджер для управления анимациями
     * Отвечает за все анимационные эффекты в приложении
     */
    private class AnimationManager {

        /**
         * Запускает анимацию плавного появления активности
         *
         * @param view вид для анимации
         */
        public void startEntranceAnimation(View view) {
            if (view != null) {
                view.animate()
                        .alpha(1f)
                        .setDuration(5000)
                        .setStartDelay(100)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
        }

        /**
         * Временно отображает текстовое поле с уровнем громкости с анимацией
         *
         * @param volumeText текстовое поле для отображения громкости
         */
        public void showTemporaryVolumeText(TextView volumeText) {
            if (volumeText != null) {
                volumeText.animate().cancel();

                volumeText.animate()
                        .alpha(0.5f)
                        .setDuration(150)
                        .withEndAction(() -> volumeText.animate()
                                .alpha(0f)
                                .setDuration(800)
                                .start())
                        .start();
            }
        }
    }

    /**
     * Менеджер для управления воспроизведением фоновой музыки
     * Обеспечивает загрузку, воспроизведение и регулировку громкости
     */
    private class SoundManager {
        private MediaPlayer mediaPlayer;
        private int volume = 10;
        private boolean initialized = false;

        /**
         * Инициализирует медиаплеер с указанным звуковым файлом
         *
         * @param filePath абсолютный путь к звуковому файлу
         * @return true - инициализация успешна, false - произошла ошибка
         */
        public boolean initialize(String filePath) {
            cleanup();

            try {
                if (filePath != null && !filePath.isEmpty()) {
                    return initializeFromFile(filePath);
                }
                return false;
            } catch (Exception e) {
                cleanup();
                return false;
            }
        }

        /**
         * Инициализирует медиаплеер из локального файла
         *
         * @param filePath путь к звуковому файлу
         * @return true если файл успешно загружен
         */
        private boolean initializeFromFile(String filePath) {
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(filePath);
                mediaPlayer.prepare();
                configureMediaPlayer();
                initialized = true;
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Настраивает основные параметры медиаплеера
         * Устанавливает зацикливание и начальный уровень громкости
         */
        private void configureMediaPlayer() {
            if (mediaPlayer != null) {
                mediaPlayer.setLooping(true);
                setVolume(volume);
            }
        }

        /**
         * Запускает воспроизведение звука
         * Если плеер уже воспроизводит звук, метод ничего не делает
         */
        public void start() {
            if (mediaPlayer != null && !mediaPlayer.isPlaying() && initialized) {
                mediaPlayer.start();
            }
        }

        /**
         * Приостанавливает воспроизведение звука
         * Сохраняет текущую позицию воспроизведения
         */
        public void pause() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        }

        /**
         * Освобождает ресурсы медиаплеера
         * Вызывается при завершении работы активности
         */
        public void cleanup() {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
            initialized = false;
        }

        /**
         * Устанавливает уровень громкости
         *
         * @param volume уровень громкости в диапазоне от 0 до 100
         */
        public void setVolume(int volume) {
            this.volume = Math.max(0, Math.min(100, volume));
            if (mediaPlayer != null) {
                float volumeLevel = this.volume / 100.0f;
                mediaPlayer.setVolume(volumeLevel, volumeLevel);
            }
        }

        /**
         * Увеличивает громкость на 10 единиц
         * Ограничивает максимальное значение 100
         */
        public void increaseVolume() {
            setVolume(volume + 10);
        }

        /**
         * Уменьшает громкость на 10 единиц
         * Ограничивает минимальное значение 0
         */
        public void decreaseVolume() {
            setVolume(volume - 10);
        }

        /**
         * Возвращает текущий уровень громкости
         *
         * @return уровень громкости от 0 до 100
         */
        public int getVolume() {
            return volume;
        }

        /**
         * Проверяет, инициализирован ли звуковой менеджер
         *
         * @return true если звук успешно инициализирован
         */
        public boolean isInitialized() {
            return initialized;
        }

        /**
         * Устанавливает громкость с обратным вызовом
         *
         * @param volume уровень громкости
         * @param callback callback для уведомления об изменении громкости
         */
        public void setVolumeWithCallback(int volume, VolumeChangeCallback callback) {
            setVolume(volume);
            if (callback != null) {
                callback.onVolumeChanged(volume);
            }
        }
    }

    /**
     * Менеджер для управления WebView и отображения HTML-контента
     * Обеспечивает загрузку, масштабирование и управление настройками WebView
     */
    private class WebViewManager {
        private WebView webView;
        private OnPageReadyListener onPageReadyListener;

        /**
         * Устанавливает слушатель для отслеживания готовности страницы
         *
         * @param listener экземпляр слушателя
         */
        public void setOnPageReadyListener(OnPageReadyListener listener) {
            this.onPageReadyListener = listener;
        }

        /**
         * Создает и настраивает экземпляр WebView с слушателем
         *
         * @param container контейнер для размещения WebView
         * @param listener слушатель готовности страницы
         */
        public void setupWebViewWithListener(FrameLayout container, OnPageReadyListener listener) {
            setOnPageReadyListener(listener);
            createWebView(container);
        }

        /**
         * Создает и настраивает экземпляр WebView
         *
         * @param container контейнер для размещения WebView
         * @return настроенный экземпляр WebView
         */
        public WebView createWebView(FrameLayout container) {
            webView = new WebView(SlideShowActivity.this);
            setupWebView(container);
            return webView;
        }

        /**
         * Выполняет базовую настройку WebView
         *
         * @param container контейнер для размещения WebView
         */
        private void setupWebView(FrameLayout container) {
            webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
            configureWebViewSettings();
            setupWebViewClients();
            setupWebViewLayout(container);
        }

        /**
         * Настраивает layout параметры WebView
         *
         * @param container контейнер для размещения WebView
         */
        private void setupWebViewLayout(FrameLayout container) {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int screenWidth = displayMetrics.widthPixels;
            int screenHeight = displayMetrics.heightPixels;

            // Увеличиваем размер WebView для лучшего заполнения экрана
            int webViewWidth = (int)(screenWidth * 1.15);
            int webViewHeight = (int)(screenHeight * 1.15);

            FrameLayout.LayoutParams webViewParams = new FrameLayout.LayoutParams(
                    webViewWidth,
                    webViewHeight,
                    Gravity.CENTER
            );
            webView.setLayoutParams(webViewParams);
            container.addView(webView);
        }

        /**
         * Настраивает параметры WebSettings для оптимального отображения контента
         */
        private void configureWebViewSettings() {
            WebSettings webSettings = webView.getSettings();

            // Настройки доступа к файлам
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setAllowFileAccessFromFileURLs(false);
            webSettings.setAllowUniversalAccessFromFileURLs(false);

            // Настройки кэширования
            webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

            // Настройки JavaScript и DOM
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);

            // Настройки viewport
            webSettings.setLoadWithOverviewMode(true);
            webSettings.setUseWideViewPort(true);

            // Отключение масштабирования
            webSettings.setSupportZoom(false);
            webSettings.setBuiltInZoomControls(false);
            webSettings.setDisplayZoomControls(false);

            // Отключение scrollbar
            webView.setVerticalScrollBarEnabled(false);
            webView.setHorizontalScrollBarEnabled(false);
            webSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

            // Настройки безопасности
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                webSettings.setSafeBrowsingEnabled(false);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            }
        }

        /**
         * Настраивает клиенты WebView для обработки событий
         */
        private void setupWebViewClients() {
            webView.setWebViewClient(new WebViewClient() {
                /**
                 * Обрабатывает попытку загрузки URL в WebView
                 *
                 * @param view WebView в котором происходит загрузка
                 * @param url URL для загрузки
                 * @return true если загрузка перехвачена, false для стандартной обработки
                 */
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    if (url != null) {
                        if (url.startsWith("https://decanter.ru")) {
                            openWithCustomTabs(url);
                            return true;
                        }

                        if (isYouTubeUrl(url)) {
                            return true;
                        }
                    }
                    return false;
                }

                /**
                 * Вызывается после завершения загрузки страницы
                 *
                 * @param view WebView в котором завершилась загрузка
                 * @param url URL загруженной страницы
                 */
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    scaleContentToFit();

                    if (onPageReadyListener != null) {
                        onPageReadyListener.onPageReady();
                    }
                }

                /**
                 * Проверяет, является ли URL ссылкой на YouTube
                 *
                 * @param url URL для проверки
                 * @return true если URL ведет на YouTube
                 */
                private boolean isYouTubeUrl(String url) {
                    return url != null && (
                            url.contains("youtube.com") ||
                                    url.contains("youtu.be") ||
                                    url.contains("youtube-nocookie.com") ||
                                    url.contains("youtube.googleapis.com")
                    );
                }
            });

            webView.setWebChromeClient(new WebChromeClient());
        }

        /**
         * Открывает ссылку с помощью Chrome Custom Tabs
         *
         * @param url URL для открытия
         */
        private void openWithCustomTabs(String url) {
            try {
                CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();

                // Настройка цветовой схемы
                builder.setColorScheme(CustomTabsIntent.COLOR_SCHEME_LIGHT);
                builder.setNavigationBarColor(Color.WHITE);
                builder.setToolbarColor(Color.WHITE);

                // Настройка анимаций
                try {
                    builder.setStartAnimations(SlideShowActivity.this,
                            android.R.anim.fade_in, android.R.anim.fade_out);
                    builder.setExitAnimations(SlideShowActivity.this,
                            android.R.anim.fade_in, android.R.anim.fade_out);
                } catch (Exception e) {
                    // Используем стандартные анимации
                }

                CustomTabsIntent customTabsIntent = builder.build();
                customTabsIntent.intent.setPackage("com.android.chrome");
                customTabsIntent.launchUrl(SlideShowActivity.this, Uri.parse(url));
            } catch (Exception e) {
                openWithStandardIntent(url);
            }
        }

        /**
         * Открывает ссылку стандартным способом
         *
         * @param url URL для открытия
         */
        private void openWithStandardIntent(String url) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY |
                        Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e2) {
                // Обработка ошибки открытия ссылки
            }
        }

        /**
         * Загружает HTML контент из указанного файла
         *
         * @param filePath путь к HTML файлу
         * @param fileStorage менеджер файлового хранилища
         */
        public void loadHtmlContent(String filePath, FileStorageManager fileStorage) {
            if (filePath != null && !filePath.isEmpty()) {
                loadHtmlFromFile(filePath, fileStorage);
            } else {
                loadDefaultContent();
            }
        }

        /**
         * Загружает HTML контент из файла
         *
         * @param filePath путь к HTML файлу
         * @param fileStorage менеджер файлового хранилища
         */
        private void loadHtmlFromFile(String filePath, FileStorageManager fileStorage) {
            try {
                webView.loadUrl("file://" + filePath);
            } catch (Exception e) {
                try {
                    String htmlContent = fileStorage.getFileAsText(
                            FileStorageManager.STORAGE_WORKING,
                            new File(filePath).getName(),
                            "banners",
                            "UTF-8").getString("text");
                    webView.loadDataWithBaseURL("file://" + new File(filePath).getParent() + "/",
                            htmlContent, "text/html", "UTF-8", null);
                } catch (Exception e2) {
                    loadDefaultContent();
                }
            }
        }

        /**
         * Загружает контент по умолчанию при отсутствии основного файла
         */
        private void loadDefaultContent() {
            String defaultHtml = "<html>" +
                    "<head>" +
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover\">" +
                    "<style>" +
                    "html, body { margin: 0; padding: 0; height: 100vh; width: 100vw; overflow: hidden; background: #FFFFFF; }" +
                    "body { display: flex; justify-content: center; align-items: center; background: #FFFFFF; }" +
                    "h1 { color: #000000 !important; font-family: Arial, sans-serif; text-align: center; padding: 20px; font-size: 60px; font-weight: bold; }" +
                    "</style>" +
                    "</head>" +
                    "<body><h1>Нет доступных слайдов</h1></body>" +
                    "</html>";
            webView.loadDataWithBaseURL(null, defaultHtml, "text/html", "UTF-8", null);
        }

        /**
         * Масштабирует контент WebView для оптимального отображения на экране
         */
        public void scaleContentToFit() {
            String js = "javascript: (function() { " +
                    "var meta = document.querySelector('meta[name=viewport]');" +
                    "if (!meta) {" +
                    "    meta = document.createElement('meta');" +
                    "    meta.name = 'viewport';" +
                    "    document.head.appendChild(meta);" +
                    "}" +
                    "meta.content = 'width=device-width, initial-scale=0.73, maximum-scale=0.73, user-scalable=no';" +
                    "document.body.style.margin = '0';" +
                    "document.body.style.padding = '0';" +
                    "})()";

            if (webView != null) {
                webView.evaluateJavascript(js, null);
            }
        }

        /**
         * Приостанавливает работу WebView
         */
        public void pause() {
            if (webView != null) {
                webView.onPause();
                webView.pauseTimers();
            }
        }

        /**
         * Возобновляет работу WebView
         */
        public void resume() {
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
        }

        /**
         * Освобождает ресурсы WebView и предотвращает утечки памяти
         */
        public void destroy() {
            if (webView != null) {
                ViewParent parent = webView.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(webView);
                }
                webView.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null);
                webView.clearFormData();
                webView.clearMatches();
                parent = webView.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(webView);
                }
                webView.clearHistory();
                webView.clearCache(true);
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
                webView = null;
            }
        }
    }

    /**
     * Класс для загрузки и подготовки слайдов в фоновом режиме
     * Обеспечивает загрузку данных с сервера, обработку изображений
     * и создание HTML для отображения
     */
    public static class DownloadSlideTask {
        private static final String TAG = "DownloadSlideTask";
        private volatile boolean isRunning = false;

        private Context context;
        private SeanceDataStorage dataStorage;
        private FileStorageManager fileStorage;

        /**
         * Конструктор задачи загрузки слайдов
         *
         * @param context контекст приложения
         * @param dataStorage хранилище данных сеанса
         * @param fileStorage менеджер файлового хранилища
         */
        public DownloadSlideTask(Context context, SeanceDataStorage dataStorage, FileStorageManager fileStorage) {
            this.context = context.getApplicationContext();
            this.dataStorage = dataStorage;
            this.fileStorage = fileStorage;
        }

        /**
         * Основной метод выполнения задачи
         * Запускает процесс загрузки слайдов с защитой от повторного запуска
         */
        public void execute() {
            // Проверка на уже выполняющуюся задачу
            if (isRunning) {
                Log.w(TAG, "Загрузка слайдов уже выполняется - пропускаем");
                return;
            }

            isRunning = true;
            long startTime = System.currentTimeMillis();

            try {
                performSlideDownload();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка загрузки слайдов", e);
                handleSlideError(6, "Критическая ошибка загрузки: " + e.getMessage());
            } finally {
                isRunning = false;
                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Завершение загрузки слайдов, длительность: " + duration + "мс");
            }
        }

        /** Логирование выполнения в сообщении
         *
         */
        private void updateMessage(String message) {

            dataStorage.put("MessageText", dataStorage.getString("MessageText") + "\n"  + message);
            dataStorage.put("MessageCloseIn", System.currentTimeMillis() + 60000L);

        }


        /**
         * Основная логика загрузки слайдов
         * Выполняет все этапы: от запроса данных до сохранения результатов
         */
        private void performSlideDownload() {
            // Инициализация параметров загрузки
            dataStorage.put("DownloadSlide_start", System.currentTimeMillis());
            dataStorage.put("DownloadSlide_stop", 0L);
            dataStorage.put("DownloadSlide_errorCode", 0);
            dataStorage.put("DownloadSlide_errorText", "");
            dataStorage.put("DownloadSlide_len", 0);
            dataStorage.put("DownloadSlide_cur", 0);
            dataStorage.put("DownloadSlide_minExpires", 0L);
            dataStorage.put("DownloadSlide_data", "");
            dataStorage.put("DownloadSlide_webView", "");
            dataStorage.put("DownloadSlide_needShow", false);

            // Настройка отображения прогресса
            dataStorage.put("Progress_visible", true);
            dataStorage.put("Progress_status", "Запрос данных по акциям");
            updateMessage("Запрос данных по акциям");

            dataStorage.put("Progress", 0);

            try {
                JSONObject response = requestSlidesData();

                if (!response.optBoolean("result", false)) {
                    handleSlideError(1, response.optString("error"));
                    saveSlidesToFile(new JSONArray());
                } else {
                    processSlidesHTML(response.optString("html", ""));
                    dataStorage.put("Progress_status", "Выводим данные");
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка в performSlideDownload", e);
                handleSlideError(7, "Ошибка выполнения загрузки: " + e.getMessage());
            } finally {
                dataStorage.put("DownloadSlide_stop", System.currentTimeMillis());
            }
        }

        /**
         * Отправка HTTP GET запроса для получения данных слайдов
         *
         * @return JSON объект с результатом запроса
         */
        private JSONObject requestSlidesData() {
            String urlString = "https://decanter.ru";
            HttpURLConnection connection = null;
            boolean result = false;
            String errorText = "";
            String htmlContent = "";

            try {
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                // Установка заголовков запроса
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                connection.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9");
                connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream inputStream = connection.getInputStream();
                    BufferedInputStream bis = new BufferedInputStream(inputStream, 8192);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    while ((bytesRead = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, bytesRead);
                    }

                    bos.close();
                    bis.close();
                    inputStream.close();

                    htmlContent = bos.toString("UTF-8");
                    result = true;
                    Log.d(TAG, "HTML успешно получен, размер: " + htmlContent.length() + " символов");
                } else {
                    errorText = "HTTP ошибка: " + responseCode;
                    Log.w(TAG, "HTTP ошибка при запросе: " + responseCode);
                }

            } catch (IOException e) {
                errorText = "Ошибка соединения: " + e.getMessage();
                Log.e(TAG, "Ошибка соединения с сайтом", e);
            } catch (Exception e) {
                errorText = "Неизвестная ошибка: " + e.getMessage();
                Log.e(TAG, "Неизвестная ошибка при запросе", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            // Формирование структурированного ответа
            JSONObject jsonResult = new JSONObject();
            try {
                jsonResult.put("result", result);
                jsonResult.put("error", errorText);
                jsonResult.put("html", htmlContent);
            } catch (JSONException e) {
                try {
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
         * Парсинг HTML и извлечение данных об акциях
         *
         * @param htmlContent HTML содержимое для парсинга
         */
        private void processSlidesHTML(String htmlContent) {
            if (htmlContent == null || htmlContent.length() < 100) {
                handleSlideError(2, "Пустой или слишком короткий ответ от сервера");
                saveSlidesToFile(new JSONArray());
                return;
            }

            dataStorage.put("Progress", 20);
            dataStorage.put("Progress_status", "Данные получены");
            updateMessage("Данные получены");

            JSONArray slidesArray = new JSONArray();
            boolean parseError = false;

            try {
                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(htmlContent);
                org.jsoup.select.Elements bannerElements = doc.select("div.banner-main__item");

                dataStorage.put("DownloadSlide_len", bannerElements.size());
                dataStorage.put("DownloadSlide_cur", 0);

                // Парсинг каждого баннера на странице
                for (org.jsoup.nodes.Element bannerElement : bannerElements) {
                    JSONObject slide = parseBannerElement(bannerElement);
                    if (slide != null) {
                        slidesArray.put(slide);
                    }
                }

                dataStorage.put("Progress", 30);
                dataStorage.put("Progress_status", "Проверка изображений");
                updateMessage("Проверка изображений");

                Log.d(TAG, "Найдено баннеров: " + bannerElements.size());

            } catch (Exception e) {
                Log.e(TAG, "Ошибка парсинга HTML", e);
                handleSlideError(3, "Ошибка парсинга акций: " + e.getMessage());
                parseError = true;
                saveSlidesToFile(new JSONArray());
            }

            if (!parseError && slidesArray.length() > 0) {
                try {
                    downloadSlideImages(slidesArray);
                    saveSlidesToFile(slidesArray);
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обработки слайдов", e);
                    handleSlideError(8, "Ошибка обработки слайдов: " + e.getMessage());
                }
            } else if (!parseError) {
                handleSlideError(9, "Нет баннеров для отображения");
            }
        }

        /**
         * Парсинг отдельного баннера и извлечение его данных
         *
         * @param bannerElement элемент DOM с данными баннера
         * @return JSON объект с данными баннера
         */
        private JSONObject parseBannerElement(org.jsoup.nodes.Element bannerElement) {
            try {
                JSONObject slide = new JSONObject();

                // Извлечение заголовка
                org.jsoup.nodes.Element titleElement = bannerElement.selectFirst(".banner-main__title");
                if (titleElement != null) {
                    slide.put("title", titleElement.text().trim());
                }

                // Извлечение текста
                org.jsoup.nodes.Element textElement = bannerElement.selectFirst(".banner-main__text");
                if (textElement != null) {
                    slide.put("text", textElement.text().trim());
                }

                // Извлечение URL изображения
                String imageSrc = bannerElement.attr("data-src");
                if (!imageSrc.isEmpty()) {
                    String absoluteImageUrl = imageSrc.startsWith("http") ?
                            imageSrc : "https://decanter.ru" + imageSrc;
                    slide.put("image", absoluteImageUrl);
                }

                // Извлечение данных кнопки
                org.jsoup.nodes.Element buttonElement = bannerElement.selectFirst(".banner-main__button");
                if (buttonElement != null) {
                    JSONObject button = new JSONObject();
                    button.put("text", buttonElement.text().trim());

                    String buttonHref = buttonElement.attr("href");
                    String absoluteButtonUrl = buttonHref.startsWith("http") ?
                            buttonHref : "https://decanter.ru" + buttonHref;
                    button.put("link", absoluteButtonUrl);

                    slide.put("button", button);
                }

                return slide;

            } catch (JSONException e) {
                Log.e(TAG, "Ошибка создания JSON для баннера", e);
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Ошибка парсинга элемента баннера", e);
                return null;
            }
        }

        /**
         * Загрузка изображений для каждого слайда
         *
         * @param slidesArray массив слайдов с URL изображений
         */
        private void downloadSlideImages(JSONArray slidesArray) {
            long expires = System.currentTimeMillis() + 7 * 24 * 3600000;
            int downloadedCount = 0;
            int slidecount = slidesArray.length();

            if (slidecount == 0) {
                Log.w(TAG, "Нет слайдов для загрузки изображений");
                return;
            }

            updateMessage("Загрузка изображений (" + slidecount + ")");

            for (int i = 0; i < slidecount; i++) {
                try {
                    JSONObject slide = slidesArray.getJSONObject(i);
                    if (slide.has("image")) {
                        String imageUrl = slide.getString("image");

                        // Использование FileStorageManager для скачивания
                        JSONObject fileResult = fileStorage.getOrCreateFile(
                                imageUrl,
                                FileStorageManager.STORAGE_WORKING,
                                "banners",
                                expires,
                                null
                        );

                        if (fileResult.optBoolean("success")) {
                            String localPath = fileResult.getString("path");
                            slide.put("image", localPath);

                            cropImageIfNeeded(localPath);
                            downloadedCount++;

                            // Обновление прогресса
                            dataStorage.put("DownloadSlide_cur", downloadedCount);
                            dataStorage.put("Progress_status",
                                    "Загрузка изображений (" + downloadedCount + " из " + slidecount + ")");

                            int progress = 30 + Math.round((float) downloadedCount / slidecount * 40);
                            dataStorage.put("Progress", progress);

                            // Обновление срока хранения
                            long curExpires = fileResult.optLong("expires");
                            long currentMinExpires = dataStorage.getLong("DownloadSlide_minExpires");
                            if (currentMinExpires == 0L || currentMinExpires > curExpires) {
                                dataStorage.put("DownloadSlide_minExpires", curExpires);
                            }

                            Log.d(TAG, "Загружено изображение " + downloadedCount + "/" + slidecount);
                        } else {
                            Log.w(TAG, "Не удалось загрузить изображение: " + imageUrl);
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Ошибка JSON при обработке слайда " + i, e);
                } catch (Exception e) {
                    Log.e(TAG, "Неизвестная ошибка при загрузке изображения " + i, e);
                }
            }
        }

        /**
         * Обрезает изображение по горизонтали для соответствия соотношению сторон экрана
         *
         * @param imagePath путь к изображению
         */
        private void cropImageIfNeeded(String imagePath) {
            try {
                android.graphics.BitmapFactory.Options options =
                        new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(imagePath, options);

                int originalWidth = options.outWidth;
                int originalHeight = options.outHeight;

                // Получение соотношения сторон экрана
                android.util.DisplayMetrics displayMetrics =
                        context.getResources().getDisplayMetrics();
                float screenRatio = (float) displayMetrics.heightPixels / displayMetrics.widthPixels;

                float imageRatio = (float) originalWidth / originalHeight;

                // Обрезка если изображение шире чем нужно
                if (imageRatio > screenRatio) {
                    int targetWidth = (int) (originalHeight * screenRatio);
                    int cropAmount = originalWidth - targetWidth;

                    // Специальная обработка для конкретного изображения
                    double leftCropDouble = imagePath.endsWith("cd3f5826136e372b6b517591eaa5f72f.jpg") ?
                            800 : cropAmount * 0.2;
                    int leftCrop = (int) leftCropDouble;

                    options.inJustDecodeBounds = false;
                    android.graphics.Bitmap originalBitmap =
                            android.graphics.BitmapFactory.decodeFile(imagePath);

                    if (originalBitmap != null) {
                        try {
                            android.graphics.Bitmap croppedBitmap = android.graphics.Bitmap.createBitmap(
                                    originalBitmap,
                                    leftCrop,
                                    0,
                                    targetWidth,
                                    originalHeight
                            );

                            saveCroppedBitmap(croppedBitmap, imagePath);
                            croppedBitmap.recycle();
                            originalBitmap.recycle();

                            Log.d(TAG, "Изображение обрезано: " + imagePath);
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Некорректные параметры обрезки для: " + imagePath, e);
                            originalBitmap.recycle();
                        } catch (OutOfMemoryError e) {
                            Log.e(TAG, "Недостаточно памяти для обрезки: " + imagePath, e);
                            originalBitmap.recycle();
                        }
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Неизвестная ошибка обрезки: " + imagePath, e);
            }
        }

        /**
         * Сохраняет обрезанное изображение обратно в файл
         *
         * @param croppedBitmap обрезанное изображение
         * @param imagePath путь для сохранения файла
         */
        private void saveCroppedBitmap(android.graphics.Bitmap croppedBitmap, String imagePath) {
            java.io.FileOutputStream out = null;
            try {
                out = new java.io.FileOutputStream(imagePath);

                String extension = imagePath.substring(imagePath.lastIndexOf(".") + 1).toLowerCase();
                android.graphics.Bitmap.CompressFormat format = android.graphics.Bitmap.CompressFormat.JPEG;
                if ("png".equals(extension)) {
                    format = android.graphics.Bitmap.CompressFormat.PNG;
                }

                croppedBitmap.compress(format, 100, out);
                Log.d(TAG, "Обрезанное изображение сохранено: " + imagePath);

            } catch (java.io.FileNotFoundException e) {
                Log.e(TAG, "Файл не найден для сохранения: " + imagePath, e);
            } catch (java.io.IOException e) {
                Log.e(TAG, "Ошибка записи в файл: " + imagePath, e);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (java.io.IOException e) {
                        Log.e(TAG, "Ошибка закрытия потока: " + imagePath, e);
                    }
                }
            }
        }

        /**
         * Сохраняет данные слайдов в файлы на устройстве
         *
         * @param slidesArray массив слайдов для сохранения
         */
        private void saveSlidesToFile(JSONArray slidesArray) {
            try {
                String jsonString = "";
                long html_expires = System.currentTimeMillis() + 24 * 3600000;
                long minxExpires = dataStorage.getLong("DownloadSlide_minExpires");

                int downloadedCount = dataStorage.getInt("DownloadSlide_cur");
                int totalCount = dataStorage.getInt("DownloadSlide_len");

                // Проверка успешности загрузки всех изображений
                if (downloadedCount == 0 || downloadedCount != totalCount) {
                    Log.w(TAG, "Не все изображения загружены (" + downloadedCount +
                            "/" + totalCount + "). Используем резервную копию.");

                    JSONObject restoreFile = fileStorage.getFileAsText(
                            fileStorage.STORAGE_WORKING,
                            "slide.json",
                            "banners",
                            "UTF-8"
                    );

                    if (restoreFile.optBoolean("success")) {
                        dataStorage.put("Progress_status", "Восстановили резервную копию");
                        updateMessage("Восстановили резервную копию");
                        handleSlideError(0, "");
                        jsonString = restoreFile.getString("text");

                        JSONObject path_response = fileStorage.getFile(
                                fileStorage.STORAGE_WORKING,
                                "slide.json",
                                "banners"
                        );
                        dataStorage.put("DownloadSlide_data", path_response.optString("path"));

                        Log.d(TAG, "Используем резервную копию слайдов");
                    } else {
                        jsonString = new JSONArray().toString();
                        Log.w(TAG, "Резервная копия не найдена, создаем пустой массив");
                    }
                } else {
                    // Сохранение новых данных
                    jsonString = slidesArray.toString();
                    JSONObject slide_response = fileStorage.createFile(
                            fileStorage.STORAGE_WORKING,
                            "banners",
                            "slide.json",
                            minxExpires,
                            jsonString,
                            "UTF-8"
                    );

                    if (slide_response.optBoolean("success")) {
                        dataStorage.put("DownloadSlide_data", slide_response.optString("path"));
                        Log.d(TAG, "Новые данные слайдов сохранены");
                    } else {
                        dataStorage.put("DownloadSlide_cur", 0);
                        handleSlideError(4, "Не удалось сохранить новые данные");
                        saveSlidesToFile(new JSONArray());
                        return;
                    }
                }

                if (jsonString.isEmpty()) {
                    handleSlideError(5, "Не удалось получить данные для показа");
                    return;
                }

                dataStorage.put("Progress", 70);
                dataStorage.put("Progress_status", "Подготовка слайдов");
                updateMessage("Подготовка слайдов");

                // Создание HTML файла для отображения
                String downloadSlideWebView = createSlideHtml(jsonString, html_expires);

                if (downloadSlideWebView.isEmpty()) {
                    handleSlideError(6, "Не удалось создать HTML для слайдов");
                    return;
                }

                dataStorage.put("DownloadSlide_webView", downloadSlideWebView);
                dataStorage.put("DownloadSlide_needShow", true);

                Log.d(TAG, "Все данные слайдов подготовлены и готовы к показу");

            } catch (JSONException e) {
                Log.e(TAG, "Ошибка формата JSON при сохранении", e);
                handleSlideError(10, "Ошибка формата данных: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Неизвестная ошибка при сохранении", e);
                handleSlideError(8, "Ошибка сохранения данных: " + e.getMessage());
            }
        }

        /**
         * Создает HTML файл для отображения слайдов
         *
         * @param jsonString JSON данные слайдов
         * @param expires срок хранения файла
         * @return путь к созданному HTML файлу
         */
        private String createSlideHtml(String jsonString, long expires) {
            try {
                JSONObject template_response = fileStorage.getAssetFileAsText("slide_template.html", "UTF-8");
                if (template_response.optBoolean("success")) {
                    String template_html = template_response.getString("text");
                    String final_html = template_html.replace("###slides###", jsonString);

                    JSONObject final_response = fileStorage.createFile(
                            fileStorage.STORAGE_WORKING,
                            "banners",
                            "slide.html",
                            expires,
                            final_html,
                            "UTF-8"
                    );

                    if (final_response.optBoolean("success")) {
                        Log.d(TAG, "HTML для слайдов успешно создан");
                        return final_response.getString("path");
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Ошибка JSON при создании HTML", e);
            } catch (Exception e) {
                Log.e(TAG, "Неизвестная ошибка создания HTML", e);
            }

            return "";
        }

        /**
         * Обработка ошибок загрузки слайдов
         *
         * @param errorCode код ошибки
         * @param errorText текст ошибки
         */
        private void handleSlideError(int errorCode, String errorText) {
            dataStorage.put("DownloadSlide_errorCode", errorCode);
            dataStorage.put("DownloadSlide_errorText", errorText);
            dataStorage.put("Progress", 100);

            if (errorCode > 0) {
                Log.e(TAG, "Ошибка слайдов [код " + errorCode + "]: " + errorText);
            } else {
                Log.i(TAG, "Восстановили резервную копию слайдов");
            }
        }
    }
}