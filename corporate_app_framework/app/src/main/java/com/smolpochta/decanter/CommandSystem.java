/** Единая система управления командами приложения.
 * Содержит все компоненты: интерфейсы, контекст, команды, свайпы и реестр.
 *
 * Copyright (c) 2025 Алексей smolpochta
 * Email: smolpochta@gmail.com
 *
 */

package com.smolpochta.decanter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandSystem {

    private static final String TAG = "CommandSystem";

    /**
     * Интерфейс для доступа к View элементам команды
     */
    public interface CommandViews {
        ConstraintLayout getItemView();
        ImageView getIconView();
        TextView getTextView();

        default boolean isValid() {
            return getItemView() != null && getIconView() != null && getTextView() != null;
        }
    }

    /**
     * Интерфейс обратного вызова для команд
     */
    public interface CommandCallback {
        void onCommandExecuted(String commandId);
        void onVibrationRequested(String patternType);
        void onShowMessage(String message, Integer interval);
        void onShowError(String error, Integer interval, Boolean appClose);
        boolean isActionPermitted();
    }

    /**
     * Контекст выполнения команд со всеми зависимостями
     */
    public static class CommandContext {
        public Context context;
        public SeanceDataStorage dataStorage;
        public FileStorageManager fileStorage;
        public BackgroundTaskManager backgroundTaskManager;
        public VibrationManager vibrationManager;
        public CommandCallback callback;

        public CommandContext(Context context, SeanceDataStorage dataStorage,
                              FileStorageManager fileStorage,
                              BackgroundTaskManager backgroundTaskManager,
                              VibrationManager vibrationManager,
                              CommandCallback callback) {
            this.context = context;
            this.dataStorage = dataStorage;
            this.fileStorage = fileStorage;
            this.backgroundTaskManager = backgroundTaskManager;
            this.vibrationManager = vibrationManager;
            this.callback = callback;
        }
    }

    /**
     * Абстрактный базовый класс для всех команд
     */
    public abstract static class BaseCommand {
        protected String id;
        protected String name;
        protected int layoutId;

        public String getId() { return id; }
        public String getName() { return name; }
        public int getLayoutId() { return layoutId; }

        public abstract CommandViews getCommandViews(View rootView);
        public abstract void execute(CommandContext context);
        public boolean isAvailable(SeanceDataStorage dataStorage) { return true; }
    }

    /**
     * Обработчик свайпа для команд
     */
    public static class SwipeHandler {
        private ConstraintLayout itemView;
        private String commandId;
        private CommandCallback callback;
        private Context context;

        // Переменные для обработки свайпа
        private float swipeStartX;
        private boolean isSwiping = false;
        private boolean wasActivated = false;
        private float lastProgress = 0f;
        private ValueAnimator swipeResetAnimator;

        // Константы
        private static final float SWIPE_ACTIVATION_THRESHOLD = 0.5f;
        private static final float SWIPE_SCALE_REDUCTION = 0.02f;
        private static final float SWIPE_TRANSLATION_MULTIPLIER = 0.15f;

        // Переменные для мерцания лепестка
        private ValueAnimator pulseAnimator;
        private boolean isPulsing = false;
        private static final float PULSE_MIN_ALPHA = 0.3f;
        private static final float PULSE_MAX_ALPHA = 1.0f;
        private static final long PULSE_DURATION = 500;



        public SwipeHandler(ConstraintLayout itemView, String commandId,
                            CommandCallback callback, Context context) {
            this.itemView = itemView;
            this.commandId = commandId;
            this.callback = callback;
            this.context = context;

            setupSwipeGesture();
        }

        /**
         * Настройка жеста свайпа для элемента команды
         */
        private void setupSwipeGesture() {
            if (itemView == null) return;

            itemView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            if (!callback.isActionPermitted()) return false;

                            swipeStartX = event.getX();
                            isSwiping = true;
                            wasActivated = false;
                            lastProgress = 0f;
                            stopPulsing();

                            startSwipeAnimation(0f);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            if (isSwiping) {
                                float currentX = event.getX();
                                float deltaX = currentX - swipeStartX;

                                if (deltaX > 0) {
                                    float progress = Math.min(deltaX / (itemView.getWidth() * 0.5f), 1.0f);
                                    updateSwipeProgress(progress);

                                    boolean isIncreasing = progress > lastProgress;

                                    // Проверка порога активации
                                    if (isIncreasing && progress >= SWIPE_ACTIVATION_THRESHOLD && !wasActivated) {
                                        callback.onVibrationRequested(VibrationManager.TYPE_ACTIVATION);
                                        wasActivated = true;
                                        startPulsing();
                                    } else if (!isIncreasing && progress < SWIPE_ACTIVATION_THRESHOLD && wasActivated) {
                                        callback.onVibrationRequested(VibrationManager.TYPE_DEACTIVATION);
                                        wasActivated = false;


                                        stopPulsing();
                                    }


                                    lastProgress = progress;
                                } else {
                                    if (wasActivated) {
                                        callback.onVibrationRequested(VibrationManager.TYPE_DEACTIVATION);
                                        wasActivated = false;
                                    }
                                    updateSwipeProgress(0f);
                                    lastProgress = 0f;
                                    stopPulsing();
                                }
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (isSwiping) {
                                float currentX = event.getX();
                                float deltaX = currentX - swipeStartX;
                                float progress = 0f;

                                if (deltaX > 0) {
                                    progress = Math.min(deltaX / (itemView.getWidth() * 0.5f), 1.0f);
                                }

                                resetSwipeBackgroundImmediately();

                                if (progress >= SWIPE_ACTIVATION_THRESHOLD && wasActivated) {
                                    // Успешный свайп - запускаем команду
                                    callback.onCommandExecuted(commandId);
                                    resetSwipeAnimation();
                                } else {
                                    // Неудачный свайп
                                    if (wasActivated) {
                                        callback.onVibrationRequested(VibrationManager.TYPE_DEACTIVATION);
                                    }
                                    resetSwipeAnimationWithSpring();
                                }
                                stopPulsing();

                                isSwiping = false;
                                wasActivated = false;
                                lastProgress = 0f;
                            }
                            return true;
                    }
                    return false;
                }
            });
        }







        /** Запускаем мерцание
         *
         */
        private void startPulsing() {
            if (isPulsing || itemView == null) return;

            isPulsing = true;

            pulseAnimator = ValueAnimator.ofFloat(PULSE_MIN_ALPHA, PULSE_MAX_ALPHA);
            pulseAnimator.setDuration(PULSE_DURATION);
            pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());

            pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (itemView != null) {
                        float alpha = (float) animation.getAnimatedValue();
                        itemView.setAlpha(alpha);
                    }
                }
            });

            pulseAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    if (itemView != null) {
                        itemView.setAlpha(1.0f);
                    }
                    isPulsing = false;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (itemView != null) {
                        itemView.setAlpha(1.0f);
                    }
                    isPulsing = false;
                }
            });

            pulseAnimator.start();
        }

        /** Останавливаем мерцание
         *
         */
        private void stopPulsing() {
            if (pulseAnimator != null && pulseAnimator.isRunning()) {
                pulseAnimator.cancel();
            }
            pulseAnimator = null;
            isPulsing = false;

            if (itemView != null) {
                itemView.setAlpha(1.0f);
            }
        }



        /**
         * Запуск анимации свайпа
         */
        private void startSwipeAnimation(float progress) {
            if (swipeResetAnimator != null && swipeResetAnimator.isRunning()) {
                swipeResetAnimator.cancel();
            }

            itemView.setAlpha(0.98f);
            updateSwipeProgress(progress);
        }

        /**
         * Обновление визуального прогресса свайпа
         */
        private void updateSwipeProgress(float progress) {
            if (Float.isNaN(progress)) {
                progress = 0f;
            }

            float scale = 1.0f - SWIPE_SCALE_REDUCTION * progress;
            safeSetScale(itemView, scale, scale);

            float translationX = progress * itemView.getWidth() * SWIPE_TRANSLATION_MULTIPLIER;
            itemView.setTranslationX(translationX);

            itemView.setAlpha(0.98f - 0.05f * progress);
            updateSwipeBackground(progress);
        }

        /**
         * Обновление цвета фона свайпа
         */
        private void updateSwipeBackground(float progress) {
            int baseColor = Color.parseColor("#FFF5F5");
            int targetColor = Color.parseColor("#FFD6D5");

            GradientDrawable gradientDrawable = new GradientDrawable();

            int[] colors = {
                    Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                    blendColors(baseColor, targetColor, progress),
                    blendColors(baseColor, targetColor, progress),
                    Color.argb(0, Color.red(targetColor), Color.green(targetColor), Color.blue(targetColor))
            };

            gradientDrawable.setColors(colors);
            gradientDrawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
            gradientDrawable.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);

            float intensity = progress * 1.5f;
            if (intensity > 1.0f) intensity = 1.0f;

            int currentMainColor = blendColors(baseColor, targetColor, intensity);
            colors[1] = currentMainColor;
            colors[2] = currentMainColor;
            gradientDrawable.setColors(colors);

            float radiusInPx = dpToPx(context, 15);
            gradientDrawable.setCornerRadii(new float[]{
                    0, 0,
                    radiusInPx, radiusInPx,
                    radiusInPx, radiusInPx,
                    0, 0
            });

            itemView.setBackground(gradientDrawable);
        }

        /**
         * Смешивание цветов
         */
        private int blendColors(int color1, int color2, float ratio) {
            final float inverseRatio = 1f - ratio;
            float r = (Color.red(color1) * inverseRatio) + (Color.red(color2) * ratio);
            float g = (Color.green(color1) * inverseRatio) + (Color.green(color2) * ratio);
            float b = (Color.blue(color1) * inverseRatio) + (Color.blue(color2) * ratio);
            return Color.rgb((int) r, (int) g, (int) b);
        }

        /**
         * Конвертация dp в px
         */
        private float dpToPx(Context context, float dp) {
            return android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP,
                    dp,
                    context.getResources().getDisplayMetrics()
            );
        }

        /**
         * Безопасное установление масштаба
         */
        private void safeSetScale(View view, float scaleX, float scaleY) {
            if (Float.isNaN(scaleX)) scaleX = 1.0f;
            if (Float.isNaN(scaleY)) scaleY = 1.0f;
            view.setScaleX(scaleX);
            view.setScaleY(scaleY);
        }

        /**
         * Мгновенный сброс фона свайпа
         */
        private void resetSwipeBackgroundImmediately() {
            stopPulsing();
            itemView.setBackgroundResource(R.drawable.command_item_background);
        }

        /**
         * Сброс анимации свайпа
         */
        private void resetSwipeAnimation() {

            stopPulsing();
            float currentScale = itemView.getScaleX();
            if (Float.isNaN(currentScale)) {
                currentScale = 1.0f;
            }

            ValueAnimator resetAnim = ValueAnimator.ofFloat(currentScale, 1.0f);
            resetAnim.setDuration(150);
            resetAnim.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                safeSetScale(itemView, value, value);
                itemView.setAlpha(0.8f + 0.2f * value);
                itemView.setTranslationX(0f);
            });
            resetAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    safeSetScale(itemView, 1.0f, 1.0f);
                    itemView.setAlpha(1.0f);
                    itemView.setTranslationX(0f);
                }
            });
            resetAnim.start();
        }

        /**
         * Сброс анимации с пружинным эффектом
         */
        private void resetSwipeAnimationWithSpring() {
            stopPulsing();
            swipeResetAnimator = ValueAnimator.ofFloat(itemView.getScaleX(), 1.0f);
            swipeResetAnimator.setDuration(300);
            swipeResetAnimator.setInterpolator(new OvershootInterpolator(1.5f));
            swipeResetAnimator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                safeSetScale(itemView, value, value);
                itemView.setAlpha(0.8f + 0.2f * value);
                itemView.setTranslationX(0f);
            });
            swipeResetAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    safeSetScale(itemView, 1.0f, 1.0f);
                    itemView.setAlpha(1.0f);
                    itemView.setTranslationX(0f);
                }
            });
            swipeResetAnimator.start();
        }
    }

    /**
     * Реестр для управления всеми командами
     */
    public static class CommandRegistry {
        private static CommandRegistry instance;
        private final Map<String, BaseCommand> commands = new HashMap<>();
        private final Map<String, CommandViews> commandViews = new HashMap<>();
        private final List<SwipeHandler> swipeHandlers = new ArrayList<>();
        private CommandContext commandContext;

        public static synchronized CommandRegistry getInstance() {
            if (instance == null) {
                instance = new CommandRegistry();
            }
            return instance;
        }

        private CommandRegistry() {}

        public void initialize(CommandContext context) {
            this.commandContext = context;
            registerAllCommands();
        }

        private void registerAllCommands() {
            // Регистрация всех команд
            registerCommand(new PriceCheckCommand());
            registerCommand(new ReceiptCommand());
            registerCommand(new SalesCommand());
            registerCommand(new TeamCommand());
            registerCommand(new AboutCommand());

            Log.i(TAG, "Зарегистрировано команд: " + commands.size());
        }

        private void registerCommand(BaseCommand command) {
            commands.put(command.getId(), command);
        }

        public BaseCommand getCommand(String id) {
            return commands.get(id);
        }

        public Collection<BaseCommand> getAllCommands() {
            return commands.values();
        }

        public CommandViews getViewsForCommand(String commandId, View rootView) {
            if (commandViews.containsKey(commandId)) {
                return commandViews.get(commandId);
            }

            BaseCommand command = getCommand(commandId);
            if (command == null) return null;

            CommandViews views = command.getCommandViews(rootView);
            if (views != null && views.isValid()) {
                commandViews.put(commandId, views);
                return views;
            }

            return null;
        }

        /**
         * Инициализация всех обработчиков свайпа
         */
        public void initializeAllSwipeHandlers(View rootView) {
            // Очищаем существующие обработчики
            swipeHandlers.clear();

            for (BaseCommand command : getAllCommands()) {
                CommandViews views = getViewsForCommand(command.getId(), rootView);
                if (views != null && views.getItemView() != null) {
                    SwipeHandler swipeHandler = new SwipeHandler(
                            (ConstraintLayout) views.getItemView(),
                            command.getId(),
                            new CommandCallback() {
                                @Override
                                public void onCommandExecuted(String commandId) {
                                    executeCommand(commandId);
                                }

                                @Override
                                public void onVibrationRequested(String patternType) {
                                    if (commandContext != null && commandContext.vibrationManager != null) {
                                        commandContext.vibrationManager.vibrate(patternType);
                                    }
                                }

                                @Override
                                public void onShowMessage(String message, Integer interval) {
                                    if (commandContext != null && commandContext.callback != null) {
                                        commandContext.callback.onShowMessage(message, interval);
                                    }
                                }

                                @Override
                                public void onShowError(String error, Integer interval, Boolean appClose) {
                                    if (commandContext != null && commandContext.callback != null) {
                                        commandContext.callback.onShowError(error, interval, appClose);
                                    }
                                }

                                @Override
                                public boolean isActionPermitted() {
                                    if (commandContext != null && commandContext.dataStorage != null) {
                                        long currentTime = System.currentTimeMillis();
                                        return currentTime >= commandContext.dataStorage.getLong("AppCloseIn")
                                                && currentTime >= commandContext.dataStorage.getLong("MessageCloseIn")
                                                && !commandContext.dataStorage.getBoolean("AppBlock")
                                                && !commandContext.dataStorage.getBoolean("Progress_visible");
                                    }
                                    return false;
                                }
                            },
                            commandContext.context
                    );

                    swipeHandlers.add(swipeHandler);
                }
            }

            Log.i(TAG, "Инициализировано обработчиков свайпа: " + swipeHandlers.size());
        }

        /**
         * Выполнение команды
         */
        public void executeCommand(String commandId) {
            BaseCommand command = getCommand(commandId);
            if (command != null && commandContext != null &&
                    command.isAvailable(commandContext.dataStorage)) {
                command.execute(commandContext);
                Log.i(TAG, "Выполнена команда: " + command.getName());
            }
        }

        /**
         * Применение стилей ко всем командам
         */
        public void applyStylesToAllCommands(int fontColor, int elementColor, float iconAlpha, View rootView) {
            for (BaseCommand command : getAllCommands()) {
                CommandViews views = getViewsForCommand(command.getId(), rootView);
                if (views != null) {
                    applyStyleToCommand(views, fontColor, elementColor, iconAlpha);
                }
            }
        }

        private void applyStyleToCommand(CommandViews views, int fontColor, int elementColor, float iconAlpha) {
            if (views.getTextView() != null) {
                views.getTextView().setTextColor(fontColor);
            }
            if (views.getIconView() != null) {
                // views.getIconView().setColorFilter(elementColor);
                views.getIconView().setAlpha(iconAlpha);
            }
        }

        /**
         * Очистка ресурсов
         */
        public void cleanup() {
            commands.clear();
            commandViews.clear();
            swipeHandlers.clear();
            commandContext = null;
            Log.i(TAG, "Реестр команд очищен");
        }
    }

    /**
     * Команда "Сверка ценников"
     */
    public static class PriceCheckCommand extends BaseCommand {
        public PriceCheckCommand() {
            this.id = "price_check";
            this.name = "Сверка ценников";
            this.layoutId = R.id.activeOption3;
        }

        @Override
        public CommandViews getCommandViews(View rootView) {
            return new CommandViews() {
                @Override
                public ConstraintLayout getItemView() {
                    return rootView.findViewById(R.id.priceCheckItem);
                }

                @Override
                public ImageView getIconView() {
                    return rootView.findViewById(R.id.priceCheckIcon);
                }

                @Override
                public TextView getTextView() {
                    return rootView.findViewById(R.id.priceCheckText);
                }


            };
        }

        @Override
        public void execute(CommandContext context) {
            if (context.dataStorage.getBoolean("AccessSuccess_demo")) {
                context.callback.onShowError("Нужна авторизация", 5, false);
            } else {
                context.callback.onShowMessage("В планах", 5);
            }
        }
    }

    /**
     * Команда "Прием накладных"
     */
    public static class ReceiptCommand extends BaseCommand {
        public ReceiptCommand() {
            this.id = "receipt";
            this.name = "Прием накладных";
            this.layoutId = R.id.activeOption4;
        }

        @Override
        public CommandViews getCommandViews(View rootView) {
            return new CommandViews() {
                @Override
                public ConstraintLayout getItemView() {
                    return rootView.findViewById(R.id.receiptItem);
                }

                @Override
                public ImageView getIconView() {
                    return rootView.findViewById(R.id.receiptIcon);
                }

                @Override
                public TextView getTextView() {
                    return rootView.findViewById(R.id.receiptText);
                }


            };
        }

        @Override
        public void execute(CommandContext context) {
            if (context.dataStorage.getBoolean("AccessSuccess_demo")) {
                context.callback.onShowError("Нужна авторизация", 5, false);
            } else {
                context.callback.onShowMessage("В разработке", 5);
            }
        }
    }

    /**
     * Команда "Акции"
     */
    public static class SalesCommand extends BaseCommand {
        public SalesCommand() {
            this.id = "sales";
            this.name = "Акции";
            this.layoutId = R.id.activeOption5;
        }

        @Override
        public CommandViews getCommandViews(View rootView) {
            return new CommandViews() {
                @Override
                public ConstraintLayout getItemView() {
                    return rootView.findViewById(R.id.salesItem);
                }

                @Override
                public ImageView getIconView() {
                    return rootView.findViewById(R.id.salesIcon);
                }

                @Override
                public TextView getTextView() {
                    return rootView.findViewById(R.id.salesText);
                }



            };
        }

        @Override
        public void execute(CommandContext context) {
            if (context != null && context.backgroundTaskManager != null) {
                context.callback.onShowMessage("Ожидайте.. ⏳", 60);
                context.backgroundTaskManager.addBackgroundTask("downloadSlideTask", null, 0);
            } else {
                context.callback.onShowError("Системная ошибка: менеджер задач не доступен", 3, false);
            }
        }
    }

    /**
     * Команда "Команда"
     */
    public static class TeamCommand extends BaseCommand {
        public TeamCommand() {
            this.id = "team";
            this.name = "Команда";
            this.layoutId = R.id.activeOption5;
        }

        @Override
        public CommandViews getCommandViews(View rootView) {
            return new CommandViews() {
                @Override
                public ConstraintLayout getItemView() {
                    return rootView.findViewById(R.id.teamItem);
                }

                @Override
                public ImageView getIconView() {
                    return rootView.findViewById(R.id.teamIcon);
                }

                @Override
                public TextView getTextView() {
                    return rootView.findViewById(R.id.teamText);
                }


            };
        }

        @Override
        public void execute(CommandContext context) {
            if (context != null && context.backgroundTaskManager != null) {
                context.callback.onShowMessage("Ожидайте.. ⏳", 60);
                context.backgroundTaskManager.addBackgroundTask("downloadTeamTask", null, 0);

            } else {
                context.callback.onShowError("Системная ошибка: менеджер задач не доступен", 3, false);
            }
        }
    }

    /**
     * Команда "Справка"
     */
    public static class AboutCommand extends BaseCommand {
        public AboutCommand() {
            this.id = "about";
            this.name = "Справка";
            this.layoutId = R.id.activeOption5;
        }

        @Override
        public CommandViews getCommandViews(View rootView) {
            return new CommandViews() {
                @Override
                public ConstraintLayout getItemView() {
                    return rootView.findViewById(R.id.aboutItem);
                }

                @Override
                public ImageView getIconView() {
                    return rootView.findViewById(R.id.aboutIcon);
                }

                @Override
                public TextView getTextView() {
                    return rootView.findViewById(R.id.aboutText);
                }



            };
        }

        @Override
        public void execute(CommandContext context) {
            String clientId = context.dataStorage.getString("AccessSuccess_clientId");
            String versionCode = String.valueOf(AppVersionUtils.getCode(context.context));

            StringBuilder message = new StringBuilder();
            message.append(versionCode + " / " + clientId);

            context.callback.onShowMessage(message.toString(), 5);
            copyClientIdToClipboard(context.context, clientId);
        }

        private void copyClientIdToClipboard(Context context, String clientId) {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip =
                        android.content.ClipData.newPlainText("AccessSuccess_clientId", clientId);
                clipboard.setPrimaryClip(clip);
            } catch (Exception e) {
                Log.e("AboutCommand", "Ошибка копирования", e);
            }
        }
    }
}