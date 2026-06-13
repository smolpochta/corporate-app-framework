/** Менеджер вибрации
 *
 * Copyright (c) 2025 Алексей smolpochta
 * Email: smolpochta@gmail.com
 *
 */

package com.smolpochta.decanter;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import androidx.annotation.IntRange;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VibrationManager {
    private static final String TAG = "VibrationManager";

    // Константы амплитуды вибрации
    public static final int AMPLITUDE_MIN = 1;
    public static final int AMPLITUDE_WEAK = 64;
    public static final int AMPLITUDE_MEDIUM = 128;
    public static final int AMPLITUDE_STRONG = 192;
    public static final int AMPLITUDE_MAX = 255;

    // Типы вибрационных эффектов
    public static final String TYPE_ACTIVATION = "activation";
    public static final String TYPE_DEACTIVATION = "deactivation";
    public static final String TYPE_SUCCESS = "success";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_WARNING = "warning";
    public static final String TYPE_NOTIFICATION = "notification";
    public static final String TYPE_MENU = "menu";
    public static final String TYPE_SOS = "sos";
    public static final String TYPE_GESTURE = "gesture";
    public static final String TYPE_CLICK = "click";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_RAMP_UP = "ramp_up";
    public static final String TYPE_RAMP_DOWN = "ramp_down";
    public static final String TYPE_CONFIRMATION = "confirmation";
    public static final String TYPE_CELEBRATION = "celebration";
    public static final String TYPE_ALERT = "alert";
    public static final String TYPE_CHIME = "chime";
    public static final String TYPE_PULSE = "pulse";
    public static final String TYPE_RHYTHM = "rhythm";

    public static final String TYPE_WORK_ALLOWED = "work_allowed";

    private static VibrationManager instance;
    private final Context context;
    private Vibrator vibrator;
    private final boolean hasVibrator;
    private final boolean hasAmplitudeControl;
    private final Map<String, VibrationPattern> patternLibrary;
    private final VibrationPreferences preferences;
    private boolean enabled = true;
    private float intensityMultiplier = 1.0f;

    // Singleton pattern
    public static synchronized VibrationManager getInstance(Context context) {
        if (instance == null) {
            instance = new VibrationManager(context.getApplicationContext());
        }
        return instance;
    }

    private VibrationManager(Context context) {
        this.context = context;
        this.preferences = new VibrationPreferences(context);
        this.patternLibrary = new ConcurrentHashMap<>();

        // Инициализация вибратора в зависимости от версии Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ использует VibratorManager
            VibratorManager vibratorManager = (VibratorManager)
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            this.vibrator = vibratorManager != null ?
                    vibratorManager.getDefaultVibrator() : null;
        } else {
            // Старые версии
            this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }

        // Проверка возможностей устройства
        this.hasVibrator = checkVibratorAvailability();
        this.hasAmplitudeControl = checkAmplitudeControl();

        // Загрузка настроек
        loadPreferences();

        // Инициализация библиотеки паттернов
        initializePatternLibrary();

        Log.i(TAG, "VibrationManager initialized - Available: " + hasVibrator +
                ", AmplitudeControl: " + hasAmplitudeControl);
    }

    /**
     * Проверка доступности вибрации
     */
    /**
     * Проверка доступности вибрации
     */
    private boolean checkVibratorAvailability() {
        if (vibrator == null) {
            return false;
        }

        try {
            // Для Android 8.0+ используем стандартный метод
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return vibrator.hasVibrator();
            } else {
                // Для старых версий используем рефлексию или просто проверяем вибратор
                // Вместо PackageManager.FEATURE_VIBRATOR используем более надежный подход
                try {
                    // Пытаемся вызвать вибрацию с нулевой длительностью для проверки
                    vibrator.vibrate(0);
                    return true;
                } catch (Exception e) {
                    // Если возникла ошибка - вибратор недоступен
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking vibrator availability", e);
            return false;
        }
    }

    /**
     * Проверка поддержки контроля амплитуды
     */
    private boolean checkAmplitudeControl() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return vibrator != null && vibrator.hasAmplitudeControl();
            } catch (Exception e) {
                Log.e(TAG, "Error checking amplitude control", e);
                return false;
            }
        }
        return false;
    }

    /**
     * Загрузка пользовательских настроек
     */
    private void loadPreferences() {
        this.enabled = preferences.isVibrationEnabled();
        this.intensityMultiplier = preferences.getIntensityMultiplier();
    }

    /**
     * Инициализация библиотеки вибрационных паттернов
     */
    private void initializePatternLibrary() {
        // Базовые тактильные отклики
        patternLibrary.put(TYPE_CLICK, new VibrationPattern(
                new long[]{0, 20}, new int[]{0, AMPLITUDE_MEDIUM}
        ));

        patternLibrary.put(TYPE_ACTIVATION, new VibrationPattern(
                new long[]{0, 60}, new int[]{0, AMPLITUDE_STRONG}
        ));

        patternLibrary.put(TYPE_DEACTIVATION, new VibrationPattern(
                new long[]{0, 30}, new int[]{0, AMPLITUDE_WEAK}
        ));

        patternLibrary.put(TYPE_SUCCESS, new VibrationPattern(
                new long[]{0, 80, 50, 60},
                new int[]{0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_STRONG}
        ));

        patternLibrary.put(TYPE_ERROR, new VibrationPattern(
                new long[]{0, 100, 50, 100, 50, 150},
                new int[]{0, AMPLITUDE_STRONG, 0, AMPLITUDE_STRONG, 0, AMPLITUDE_MAX}
        ));

        patternLibrary.put(TYPE_WARNING, new VibrationPattern(
                new long[]{0, 200, 100, 100},
                new int[]{0, AMPLITUDE_STRONG, 0, AMPLITUDE_MEDIUM}
        ));

        patternLibrary.put(TYPE_NOTIFICATION, new VibrationPattern(
                new long[]{0, 100, 50, 100},
                new int[]{0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_WEAK}
        ));

        patternLibrary.put(TYPE_MENU, new VibrationPattern(
                new long[]{0, 80}, new int[]{0, AMPLITUDE_MEDIUM}
        ));

        patternLibrary.put(TYPE_GESTURE, new VibrationPattern(
                new long[]{0, 40}, new int[]{0, AMPLITUDE_WEAK}
        ));

        patternLibrary.put(TYPE_CONFIRMATION, new VibrationPattern(
                new long[]{0, 50, 30, 50},
                new int[]{0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_STRONG}
        ));

        // Сложные паттерны
        patternLibrary.put(TYPE_SOS, new VibrationPattern(
                new long[]{0, 150, 80, 150, 80, 150, 200, 450, 80, 450, 80, 450, 200, 150, 80, 150, 80, 150},
                new int[]{0, AMPLITUDE_STRONG, 0, AMPLITUDE_STRONG, 0, AMPLITUDE_STRONG, 0,
                        AMPLITUDE_MAX, 0, AMPLITUDE_MAX, 0, AMPLITUDE_MAX, 0,
                        AMPLITUDE_STRONG, 0, AMPLITUDE_STRONG, 0, AMPLITUDE_STRONG}
        ));

        patternLibrary.put(TYPE_HEARTBEAT, new VibrationPattern(
                new long[]{0, 100, 100, 100, 200, 100, 100, 100},
                new int[]{0, AMPLITUDE_STRONG, 0, AMPLITUDE_WEAK, 0, AMPLITUDE_STRONG, 0, AMPLITUDE_WEAK}
        ));

        patternLibrary.put(TYPE_RAMP_UP, new VibrationPattern(
                new long[]{0, 100, 100, 100, 100, 100},
                new int[]{0, AMPLITUDE_WEAK, 0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_STRONG}
        ));

        patternLibrary.put(TYPE_RAMP_DOWN, new VibrationPattern(
                new long[]{0, 100, 100, 100, 100, 100},
                new int[]{0, AMPLITUDE_STRONG, 0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_WEAK}
        ));

        patternLibrary.put(TYPE_CELEBRATION, new VibrationPattern(
                new long[]{0, 50, 30, 50, 30, 50, 30, 50, 30, 100},
                new int[]{0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_STRONG, 0, AMPLITUDE_MEDIUM,
                        0, AMPLITUDE_STRONG, 0, AMPLITUDE_MAX}
        ));

        patternLibrary.put(TYPE_ALERT, new VibrationPattern(
                new long[]{0, 300, 200, 300},
                new int[]{0, AMPLITUDE_MAX, 0, AMPLITUDE_MAX}
        ));

        patternLibrary.put(TYPE_CHIME, new VibrationPattern(
                new long[]{0, 30, 40, 30, 40, 30, 40, 50},
                new int[]{0, AMPLITUDE_WEAK, 0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_STRONG, 0, AMPLITUDE_WEAK}
        ));

        patternLibrary.put(TYPE_PULSE, new VibrationPattern(
                new long[]{0, 80, 120, 80, 120, 80},
                new int[]{0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_MEDIUM}
        ));

        patternLibrary.put(TYPE_RHYTHM, new VibrationPattern(
                new long[]{0, 100, 50, 150, 50, 100, 50, 200},
                new int[]{0, AMPLITUDE_STRONG, 0, AMPLITUDE_MEDIUM, 0, AMPLITUDE_STRONG, 0, AMPLITUDE_MAX}
        ));

        patternLibrary.put(TYPE_WORK_ALLOWED, new VibrationPattern(
                new long[]{0, 50},
                new int[]{0, AMPLITUDE_STRONG}
        ));

    }

    /**
     * Основной метод воспроизведения вибрации
     */
    public void vibrate(String patternType) {
        if (!enabled || !hasVibrator || vibrator == null) {
            return;
        }

        try {
            VibrationPattern pattern = patternLibrary.get(patternType);
            if (pattern == null) {
                Log.w(TAG, "Pattern not found: " + patternType);
                return;
            }

            // Применяем множитель интенсивности
            VibrationPattern adjustedPattern = pattern.adjustIntensity(intensityMultiplier);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = adjustedPattern.createVibrationEffect();
                vibrator.vibrate(effect);
            } else {
                // Для старых версий Android
                vibrator.vibrate(adjustedPattern.timings, -1);
            }

            // Логирование для отладки
            Log.d(TAG, "Vibration played: " + patternType);

        } catch (Exception e) {
            Log.e(TAG, "Error playing vibration pattern: " + patternType, e);
            // В случае ошибки пытаемся воспроизвести простую вибрацию
            safeFallbackVibration();
        }
    }

    /**
     * Воспроизведение кастомного паттерна
     */
    public void vibrateCustom(long[] timings, int[] amplitudes) {
        if (!enabled || !hasVibrator) return;

        try {
            VibrationPattern customPattern = new VibrationPattern(timings, amplitudes)
                    .adjustIntensity(intensityMultiplier);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = customPattern.createVibrationEffect();
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(customPattern.timings, -1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing custom vibration", e);
            safeFallbackVibration();
        }
    }

    /**
     * Одиночная вибрация с контролем амплитуды
     */
    public void vibrateSingle(long milliseconds, @IntRange(from = 1, to = 255) int amplitude) {
        if (!enabled || !hasVibrator) return;

        try {
            int adjustedAmplitude = adjustAmplitude(amplitude);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createOneShot(milliseconds, adjustedAmplitude);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(milliseconds);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in single vibration", e);
            safeFallbackVibration();
        }
    }

    /**
     * Безопасная fallback-вибрация
     */
    private void safeFallbackVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createOneShot(50, AMPLITUDE_MEDIUM);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(50);
            }
        } catch (Exception fallbackException) {
            Log.e(TAG, "Even fallback vibration failed", fallbackException);
        }
    }

    /**
     * Корректировка амплитуды с учетом множителя интенсивности
     */
    private int adjustAmplitude(int amplitude) {
        return (int) Math.max(AMPLITUDE_MIN,
                Math.min(AMPLITUDE_MAX, amplitude * intensityMultiplier));
    }

    /**
     * Включение/выключение вибрации
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        preferences.setVibrationEnabled(enabled);

        if (!enabled) {
            cancel();
        }
    }

    /**
     * Установка множителя интенсивности (0.0 - 2.0)
     */
    public void setIntensityMultiplier(float multiplier) {
        this.intensityMultiplier = Math.max(0.0f, Math.min(2.0f, multiplier));
        preferences.setIntensityMultiplier(this.intensityMultiplier);
    }

    /**
     * Добавление кастомного паттерна в библиотеку
     */
    public void addCustomPattern(String name, long[] timings, int[] amplitudes) {
        patternLibrary.put(name, new VibrationPattern(timings, amplitudes));
    }

    /**
     * Удаление паттерна из библиотеки
     */
    public void removePattern(String name) {
        patternLibrary.remove(name);
    }

    /**
     * Отмена текущей вибрации
     */
    public void cancel() {
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception e) {
                Log.e(TAG, "Error canceling vibration", e);
            }
        }
    }

    /**
     * Проверка доступности вибрации
     */
    public boolean isVibrationAvailable() {
        return hasVibrator;
    }

    /**
     * Проверка поддержки контроля амплитуды
     */
    public boolean hasAmplitudeControl() {
        return hasAmplitudeControl;
    }

    /**
     * Получение текущего статуса вибрации
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Получение текущего множителя интенсивности
     */
    public float getIntensityMultiplier() {
        return intensityMultiplier;
    }

    /**
     * Сброс к настройкам по умолчанию
     */
    public void resetToDefaults() {
        this.enabled = true;
        this.intensityMultiplier = 1.0f;
        preferences.resetToDefaults();
    }

    /**
     * Класс для представления вибрационного паттерна
     */
    private static class VibrationPattern {
        final long[] timings;
        final int[] amplitudes;

        VibrationPattern(long[] timings, int[] amplitudes) {
            this.timings = timings;
            this.amplitudes = amplitudes;
        }

        VibrationPattern adjustIntensity(float multiplier) {
            if (multiplier == 1.0f) {
                return this;
            }

            int[] adjustedAmplitudes = new int[amplitudes.length];
            for (int i = 0; i < amplitudes.length; i++) {
                adjustedAmplitudes[i] = (int) Math.max(AMPLITUDE_MIN,
                        Math.min(AMPLITUDE_MAX, amplitudes[i] * multiplier));
            }

            return new VibrationPattern(timings.clone(), adjustedAmplitudes);
        }

        VibrationEffect createVibrationEffect() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Для Android 8.0+ создаем эффект с амплитудами
                if (amplitudes != null && amplitudes.length == timings.length &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ поддерживает точный контроль амплитуды
                    return VibrationEffect.createWaveform(timings, amplitudes, -1);
                } else {
                    // Без контроля амплитуды
                    return VibrationEffect.createWaveform(timings, -1);
                }
            }
            throw new IllegalStateException("VibrationEffect not available");
        }
    }

    /**
     * Класс для управления настройками вибрации
     */
    private static class VibrationPreferences {
        private static final String PREFS_NAME = "vibration_preferences";
        private static final String KEY_ENABLED = "vibration_enabled";
        private static final String KEY_INTENSITY = "vibration_intensity";

        private final Context context;

        VibrationPreferences(Context context) {
            this.context = context;
        }

        boolean isVibrationEnabled() {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true);
        }

        void setVibrationEnabled(boolean enabled) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .apply();
        }

        float getIntensityMultiplier() {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getFloat(KEY_INTENSITY, 1.0f);
        }

        void setIntensityMultiplier(float multiplier) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(KEY_INTENSITY, multiplier)
                    .apply();
        }

        void resetToDefaults() {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();
        }
    }
}