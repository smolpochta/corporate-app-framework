/**  Модуль для работы с погодой
 * - Загрузка данных с Gismeteo
 * - Отображение погоды в UI
 * - Управление кэшированием иконок
 *
 * Copyright (c) 2025 Алексей smolpochta
 * Email: smolpochta@gmail.com
 */

package com.smolpochta.decanter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeatherModule {
    private static final String TAG = "WeatherModule";

    private final SeanceDataStorage dataStorage;
    private final FileStorageManager fileStorage;
    private final Context context;
    private volatile boolean isRunning = false;

    public WeatherModule(Context context, SeanceDataStorage dataStorage, FileStorageManager fileStorage) {
        this.context = context;
        this.dataStorage = dataStorage;
        this.fileStorage = fileStorage;
    }

    /**
     * Основной метод выполнения задачи по обновлению погоды
     */
    public void execute() {
        Log.d(TAG, "Вызов execute() | isRunning: " + isRunning +
                " | поток: " + Thread.currentThread().getName());

        if (isRunning) {
            Log.w(TAG, "Обновление уже выполняется - пропускаем");
            return;
        }

        isRunning = true;
        long startTime = System.currentTimeMillis();
        dataStorage.put("Weather_start", 0L);

        try {
            performWeatherUpdate();
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обновления погоды", e);
            handleWeatherError(1, "Ошибка выполнения: " + e.getMessage());
        } finally {
            isRunning = false;
            long duration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Завершение обновления погоды | длительность: " + duration + "мс");
        }
    }

    /**
     * Основная логика обновления погоды
     */
    private void performWeatherUpdate() {
        boolean canWork = dataStorage.getBoolean("Can_work");
        if (!canWork) {
            dataStorage.put("Progress_status", "Запрашиваем погоду");
        }

        String weatherUrl = "https://www.gismeteo.ru/weather-moscow-4368/now";
        JSONObject response = requestWeatherData(weatherUrl);

        if (!response.optBoolean("result", false)) {
            handleWeatherError(1, "Не удалось получить данные погоды: " + response.optString("error"));
        } else {
            processWeatherHTML(response.optString("html", ""));
        }

        if (!canWork) {
            dataStorage.put("Progress_status", "Данные о погоде получены");
        }
        dataStorage.put("Weather_stop", System.currentTimeMillis());
    }

    /**
     * Отправка запроса на Gismeteo
     */
    private JSONObject requestWeatherData(String urlString) {
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

            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Connection", "keep-alive");

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
                htmlContent = bos.toString("UTF-8");
                bis.close();
                bos.close();
                inputStream.close();
                result = true;
            } else {
                errorText = "HTTP ошибка: " + responseCode;
            }

        } catch (Exception e) {
            errorText = "Ошибка соединения: " + e.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        JSONObject jsonResult = new JSONObject();
        try {
            jsonResult.put("result", result);
            jsonResult.put("error", errorText);
            jsonResult.put("html", htmlContent);
        } catch (JSONException e) {
            try {
                jsonResult.put("result", false);
                jsonResult.put("error", "JSON Error: " + e.getMessage());
                jsonResult.put("html", "");
            } catch (JSONException e2) {
                // Критическая ошибка JSON
            }
        }

        return jsonResult;
    }

    /**
     * Парсинг HTML и извлечение данных о погоде
     */
    private void processWeatherHTML(String htmlContent) {
        if (htmlContent == null || htmlContent.length() < 100) {
            handleWeatherError(2, "Пустой ответ от сервера погоды");
            return;
        }

        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(htmlContent);
            org.jsoup.nodes.Element weatherTab = doc.selectFirst(".weathertab.is-active");

            String weatherGrad = "";
            String weatherGradE = "";
            String weatherDesc = "";
            String weatherTop = "";
            String weatherBottom = "";
            String weatherSvg = "";

            // Новые поля
            String weatherWind = "";
            String weatherPress = "";
            String weatherHum = "";
            String weatherGm = "";

            if (weatherTab != null) {
                org.jsoup.nodes.Element tempElement = weatherTab.selectFirst(".weather-value temperature-value");
                weatherGrad = tempElement != null ? tempElement.attr("value") : "";
                org.jsoup.nodes.Element feelElement = weatherTab.selectFirst(".weather-feel temperature-value");
                weatherGradE = feelElement != null ? feelElement.attr("value") : "";
                weatherDesc = weatherTab.attr("data-tooltip");
                org.jsoup.nodes.Element topIcon = weatherTab.selectFirst(".top-layer use");
                weatherTop = topIcon != null ? topIcon.attr("href").replace("#", "") : "";
                org.jsoup.nodes.Element bottomIcon = weatherTab.selectFirst(".bottom-layer use");
                weatherBottom = bottomIcon != null ? bottomIcon.attr("href").replace("#", "") : "";
                weatherSvg = extractSvgSpriteUrl(doc);
            }

            // Парсинг виджета now для получения дополнительных данных
            org.jsoup.nodes.Element nowWidget = doc.selectFirst(".widget.now[data-widget='weather-now']");
            if (nowWidget != null) {
                // Ветер
                org.jsoup.nodes.Element windElement = nowWidget.selectFirst("div.now-info-item:has(div.item-title:contains(Ветер))");
                if (windElement != null) {
                    org.jsoup.nodes.Element windValue = windElement.selectFirst("speed-value[value]");
                    org.jsoup.nodes.Element windDir = windElement.selectFirst(".item-measure");
                    if (windDir != null) {
                        String dirText = windDir.text().trim();
                        // Извлекаем направление (последние 2 символа после переноса строки)
                        String[] lines = dirText.split("\\n");
                        String direction = lines.length > 0 ? lines[0].trim() : "";
                        String windSpeed = windValue != null ? windValue.attr("value") : "";
                        String windUnit = "м/с";
                        weatherWind = (direction + " " + windSpeed + " " + windUnit).trim();
                    }
                }

                // Давление
                org.jsoup.nodes.Element pressElement = nowWidget.selectFirst("div.now-info-item:has(div.item-title:contains(Давление))");
                if (pressElement != null) {
                    org.jsoup.nodes.Element pressValue = pressElement.selectFirst("pressure-value[value]");
                    if (pressValue != null) {
                        weatherPress = pressValue.attr("value") + " мм рт. ст.";
                    }
                }

                // Влажность
                org.jsoup.nodes.Element humElement = nowWidget.selectFirst("div.now-info-item:has(div.item-title:contains(Влажность))");
                if (humElement != null) {
                    org.jsoup.nodes.Element humValue = humElement.selectFirst(".item-value");
                    if (humValue != null) {
                        weatherHum = humValue.text().trim() + "%";
                    }
                }

                // Геомагнитная активность (Г/м)
                org.jsoup.nodes.Element gmElement = nowWidget.selectFirst("div.now-info-item:has(div.item-title:contains(Г/м))");
                if (gmElement != null) {
                    org.jsoup.nodes.Element gmValue = gmElement.selectFirst(".item-value");
                    org.jsoup.nodes.Element gmUnit = gmElement.selectFirst(".item-measure span");
                    if (gmValue != null && gmUnit != null) {
                        String gmText = gmValue.text().trim();
                        String unitText = gmUnit.text().trim().replace("\n", " ").replaceAll("\\s+", " ").replace("балла ", "");
                        unitText = unitText.replace("балл ", "");
                        unitText = unitText.replace("баллов ", "");
                        weatherGm = gmText + " " + unitText;
                    }
                }

                // Если описание погоды не найдено в weatherTab, берем из now-widget
                if (weatherDesc.isEmpty()) {
                    org.jsoup.nodes.Element descElement = nowWidget.selectFirst(".now-desc");
                    if (descElement != null) {
                        weatherDesc = descElement.text().trim();
                    }
                }

                // Если температура не найдена в weatherTab, берем из now-widget
                if (weatherGrad.isEmpty()) {
                    org.jsoup.nodes.Element nowTemp = nowWidget.selectFirst(".now-weather temperature-value");
                    if (nowTemp != null) {
                        weatherGrad = nowTemp.attr("value");
                    }
                }

                // Если температура по ощущению не найдена в weatherTab, берем из now-widget
                if (weatherGradE.isEmpty()) {
                    org.jsoup.nodes.Element nowFeel = nowWidget.selectFirst(".now-feel temperature-value");
                    if (nowFeel != null) {
                        weatherGradE = nowFeel.attr("value");
                    }
                }
            }

            if (weatherGrad.isEmpty() || weatherSvg.isEmpty()) {
                handleWeatherError(2, "Не удалось получить данные");
                return;
            }

            JSONObject downloadResponse = fileStorage.getOrCreateFile(weatherSvg,
                    FileStorageManager.STORAGE_WORKING, "weather",
                    System.currentTimeMillis() + 24*3600*1000L, null);

            if (!downloadResponse.optBoolean("success")) {
                handleWeatherError(3, "Не удалось получить файл спрайта");
                return;
            } else {
                weatherSvg = downloadResponse.optString("path");
            }

            JSONObject checkSvg = getIconSvg(weatherSvg, weatherTop, weatherBottom);
            if (!checkSvg.optBoolean("success")) {
                handleWeatherError(4, "Не удалось найти svg в соответствующих спрайтах погоды");
                return;
            }

            if (!weatherGrad.isEmpty() && !weatherGrad.startsWith("-")) {
                weatherGrad = "+" + weatherGrad;
            }
            if (!weatherGradE.isEmpty() && !weatherGradE.startsWith("-")) {
                weatherGradE = "+" + weatherGradE;
            }

            dataStorage.put("Weather_grad", weatherGrad);
            dataStorage.put("Weather_grad_e", weatherGradE);
            dataStorage.put("Weather_wind", weatherWind);
            dataStorage.put("Weather_press", weatherPress);
            dataStorage.put("Weather_hum", weatherHum);
            dataStorage.put("Weather_gm", weatherGm);
            dataStorage.put("Weather_desc", weatherDesc.toLowerCase());
            dataStorage.put("Weather_top", weatherTop);
            dataStorage.put("Weather_bottom", weatherBottom);
            dataStorage.put("Weather_svg", weatherSvg);
            dataStorage.put("Weather_needShow", true);

            Log.i(TAG, "Данные погоды успешно обновлены: " + weatherGrad + ", " + weatherDesc);
            Log.i(TAG, "Дополнительные данные - Ветер: " + weatherWind + ", Давление: " + weatherPress);

        } catch (Exception e) {
            handleWeatherError(2, "Ошибка парсинга погоды: " + e.getMessage());
        }
    }

    /**
     * Извлекаем ссылку на спрайт с иконками погоды
     */
    private String extractSvgSpriteUrl(org.jsoup.nodes.Document doc) {
        String defaultSprite = "https://st.gismeteo.st/assets/sprite/sprite-weather-v4.2.svg";

        try {
            org.jsoup.select.Elements scripts = doc.select("script");

            for (org.jsoup.nodes.Element script : scripts) {
                String scriptContent = script.html();

                if (scriptContent.contains("const sprites =")) {
                    Pattern weatherSpritePattern = Pattern.compile("['\"](sprite-weather[^'\"]*)['\"]");
                    Matcher matcher = weatherSpritePattern.matcher(scriptContent);

                    if (matcher.find()) {
                        String spriteName = matcher.group(1);
                        return "https://st.gismeteo.st/assets/sprite/" + spriteName + ".svg";
                    }
                }
            }

            return defaultSprite;
        } catch (Exception e) {
            return defaultSprite;
        }
    }

    /**
     * Получить SVG иконки из спрайта (копия метода из WeatherDisplayHelper)
     */
    public static JSONObject getIconSvg(String weatherSvg, String id1, String id2) {
        JSONObject result = new JSONObject();

        try {
            String svg1 = "";
            String svg2 = "";
            boolean success1 = false;
            boolean success2 = false;
            int width1 = 0;
            int height1 = 0;
            int width2 = 0;
            int height2 = 0;

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

                    if (id1 != null && !id1.isEmpty()) {
                        String pattern1 = "<g id=\"" + id1 + "\"\\s+viewBox=\"([^\"]+)\"[^>]*>(.*?)</g>";
                        Pattern r1 = Pattern.compile(pattern1, Pattern.DOTALL);
                        Matcher m1 = r1.matcher(svgContent);

                        if (m1.find()) {
                            String viewBox = m1.group(1);
                            String gContent = m1.group(2);

                            String[] viewBoxParts = viewBox.split(" ");
                            if (viewBoxParts.length == 4) {
                                width1 = (int)Float.parseFloat(viewBoxParts[2]);
                                height1 = (int)Float.parseFloat(viewBoxParts[3]);

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

                    if (id2 != null && !id2.isEmpty()) {
                        String pattern2 = "<g id=\"" + id2 + "\"\\s+viewBox=\"([^\"]+)\"[^>]*>(.*?)</g>";
                        Pattern r2 = Pattern.compile(pattern2, Pattern.DOTALL);
                        Matcher m2 = r2.matcher(svgContent);

                        if (m2.find()) {
                            String viewBox = m2.group(1);
                            String gContent = m2.group(2);

                            String[] viewBoxParts = viewBox.split(" ");
                            if (viewBoxParts.length == 4) {
                                width2 = (int)Float.parseFloat(viewBoxParts[2]);
                                height2 = (int)Float.parseFloat(viewBoxParts[3]);

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
            Log.e(TAG, "Error reading SVG file: " + e.getMessage());
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
                Log.e(TAG, "JSON error: " + ex.getMessage());
            }
        }

        return result;
    }

    /**
     * Обработка ошибки получения погоды
     */
    private void handleWeatherError(int errorCode, String errorText) {
        dataStorage.put("Weather_errorText", errorText);
        dataStorage.put("Weather_errorCode", errorCode);
        if (!dataStorage.getBoolean("Can_work")) {
            dataStorage.put("Progress_status", "Ошибка получения погоды");
        }
        Log.e(TAG, "Ошибка погоды [код " + errorCode + "]: " + errorText);
    }

    /**
     * Обновление отображения погоды в шапке
     */
    public static void updateWeatherDisplay(ImageView weatherBottom, ImageView weatherTop,
                                            TextView weatherGrad, TextView weatherNoData,
                                            SeanceDataStorage dataStorage, Context context) {

        dataStorage.put("Weather_needShow", false);

        if (weatherBottom != null && weatherTop != null && weatherGrad != null &&
                weatherNoData != null && dataStorage != null) {
            try {
                String weatherTopId = dataStorage.getString("Weather_top");
                String weatherBottomId = dataStorage.getString("Weather_bottom");
                String temperature = dataStorage.getString("Weather_grad");
                String svgSpritePath = dataStorage.getString("Weather_svg");

                if (temperature == null || temperature.isEmpty()) {
                    weatherGrad.setVisibility(View.GONE);
                    weatherTop.setVisibility(View.GONE);
                    weatherBottom.setVisibility(View.GONE);
                    weatherNoData.setVisibility(View.VISIBLE);
                    return;
                }

                weatherNoData.setVisibility(View.GONE);
                weatherGrad.setVisibility(View.VISIBLE);
                weatherGrad.setText(temperature);

                JSONObject svgResult = getIconSvg(svgSpritePath, weatherTopId, weatherBottomId);
                boolean hasTopSvg = svgResult.optBoolean("success1", false);
                boolean hasBottomSvg = svgResult.optBoolean("success2", false);
                String topSvgContent = svgResult.optString("svg1", "");
                String bottomSvgContent = svgResult.optString("svg2", "");
                int topWidth = svgResult.optInt("width1", 0);
                int topHeight = svgResult.optInt("height1", 0);
                int bottomWidth = svgResult.optInt("width2", 0);
                int bottomHeight = svgResult.optInt("height2", 0);

                if (hasTopSvg && hasBottomSvg) {
                    weatherTop.setVisibility(View.VISIBLE);
                    weatherBottom.setVisibility(View.VISIBLE);

                    setImageViewSize(weatherTop, topWidth, topHeight, context);
                    setImageViewSize(weatherBottom, bottomWidth, bottomHeight, context);

                    displaySvgInImageView(weatherTop, topSvgContent);
                    displaySvgInImageView(weatherBottom, bottomSvgContent);

                } else if (hasTopSvg) {
                    weatherTop.setVisibility(View.VISIBLE);
                    weatherBottom.setVisibility(View.GONE);

                    setImageViewSize(weatherTop, topWidth, topHeight, context);
                    displaySvgInImageView(weatherTop, topSvgContent);
                } else {
                    weatherTop.setVisibility(View.GONE);
                    weatherBottom.setVisibility(View.GONE);
                }

            } catch (Exception e) {
                weatherGrad.setVisibility(View.GONE);
                weatherTop.setVisibility(View.GONE);
                weatherBottom.setVisibility(View.GONE);
                weatherNoData.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Устанавливает размеры ImageView
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
     * Выводит SVG в поле на форме
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
            Log.e(TAG, "Error parsing SVG", e);
            imageView.setImageDrawable(null);
        }
    }

    /**
     * Кастомный Drawable для отображения SVG
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
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}