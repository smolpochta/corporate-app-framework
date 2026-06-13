/**
 * Получения информации о версии приложения
 * Использует PackageManager для получения актуальных данных установленного APK
 *
 * Автор: Алексей @ smolpochta@gmail.com
 * 2025
 */

package com.smolpochta.decanter;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public class AppVersionUtils {

    /**
     * Получает отображаемое имя версии (например: "1.05a")
     * Использует PackageManager для гарантии получения актуальной версии установленного приложения
     *
     * @param context контекст приложения (достаточно передать Activity или Application)
     * @return строку с именем версии или "Unknown" в случае ошибки
     *
     * Пример использования:
     * String version = AppVersion.getName(MainActivity.this);
     */
    public static String getName(Context context) {
        try {
            // Получаем информацию о пакете через PackageManager
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // Возвращаем значение по умолчанию если пакет не найден
            // Это маловероятно, так как мы запрашиваем информацию о самом приложении
            return "Unknown";
        }
    }

    /**
     * Получает числовой код версии (например: 5)
     * Используется системой для сравнения версий (больше = новее)
     *
     * @param context контекст приложения
     * @return целочисленный код версии или 0 в случае ошибки
     *
     * Пример использования:
     * int code = AppVersion.getCode(MainActivity.this);
     */
    public static int getCode(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * Получает строку с полной информацией о версии в формате "Имя (Код)"
     * Полезно для отладки, логов или отображения технической информации
     *
     * @param context контекст приложения
     * @return строку в формате "1.05a (5)" или "Unknown (0)" при ошибке
     *
     * Пример использования:
     * String fullVersion = AppVersion.getFullInfo(MainActivity.this);
     */
    public static String getFullInfo(Context context) {
        return String.format("%s (%d)", getName(context), getCode(context));
    }
}