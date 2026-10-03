package app.morphe.extension.youtube.patches.general;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Forces caption (timedtext) requests to return captions auto-translated into the
 * chosen language, regardless of which languages the player's auto-translate menu offers.
 * <p>
 * The YouTube app builds the caption URL from the caption track it was given and only
 * adds {@code tlang} when the user picks a language from the auto-translate menu, which on
 * mobile lists just a handful of languages. The server accepts {@code tlang} for any
 * language, so rewriting the URL here translates every caption track.
 */
@SuppressWarnings("unused")
public final class CaptionTranslationPatch {

    private static final String TIMEDTEXT_PATH = "api/timedtext";
    private static final String LANGUAGE_PARAMETER = "lang";
    private static final String TRANSLATION_PARAMETER = "tlang";

    private CaptionTranslationPatch() {
    }

    /**
     * Injection point.
     * <p>
     * Called with the URL of every caption request, right before the request is built.
     *
     * @return The URL to request instead, or the original URL if nothing should change.
     */
    @NonNull
    public static String rewriteUrl(@NonNull String url) {
        try {
            if (!Settings.CAPTION_TRANSLATION_ENABLED.get() || !url.contains(TIMEDTEXT_PATH)) {
                return url;
            }

            String target = resolveTargetLanguage();
            Uri uri = Uri.parse(url);
            String language = uri.getQueryParameter(LANGUAGE_PARAMETER);
            String existing = uri.getQueryParameter(TRANSLATION_PARAMETER);

            // The track is already in the target language.
            if (language != null && isSameLanguage(language, target) && existing == null) {
                return url;
            }
            if (existing != null) {
                if (Settings.CAPTION_TRANSLATION_KEEP_MANUAL_CHOICE.get()) {
                    // The user picked a language from the auto-translate menu. Respect it.
                    return url;
                }
                if (isSameLanguage(existing, target)) {
                    return url;
                }
            }

            String rewritten = setTranslationParameter(url, target);
            Logger.printDebug(() -> "Caption request translated to " + target
                    + " (track language: " + language + ")");
            return rewritten;
        } catch (Exception ex) {
            Logger.printException(() -> "rewriteUrl failure", ex);
        }
        return url;
    }

    /**
     * Replaces (or appends) the {@code tlang} parameter without re-encoding the other
     * parameters, since signed caption URLs must be sent back exactly as received.
     */
    @NonNull
    private static String setTranslationParameter(@NonNull String url, @NonNull String target) {
        final int queryIndex = url.indexOf('?');
        if (queryIndex < 0) {
            return url + '?' + TRANSLATION_PARAMETER + '=' + target;
        }

        String query = url.substring(queryIndex + 1);
        String fragment = "";
        final int fragmentIndex = query.indexOf('#');
        if (fragmentIndex >= 0) {
            fragment = query.substring(fragmentIndex);
            query = query.substring(0, fragmentIndex);
        }

        StringBuilder builder = new StringBuilder(url.length() + 16);
        builder.append(url, 0, queryIndex + 1);
        for (String parameter : query.split("&")) {
            if (parameter.isEmpty() || parameter.startsWith(TRANSLATION_PARAMETER + '=')) {
                continue;
            }
            builder.append(parameter).append('&');
        }
        builder.append(TRANSLATION_PARAMETER).append('=').append(target).append(fragment);
        return builder.toString();
    }

    @NonNull
    private static String resolveTargetLanguage() {
        String setting = Settings.CAPTION_TRANSLATION_TARGET_LANGUAGE.get();
        String tag = (setting == null || setting.isEmpty() || setting.equals("app"))
                ? Locale.getDefault().toLanguageTag()
                : setting;
        // timedtext only distinguishes region for Chinese (zh-Hans / zh-Hant / zh-TW).
        return tag.startsWith("zh") ? tag : baseLanguage(tag);
    }

    @NonNull
    private static String baseLanguage(@Nullable String tag) {
        if (tag == null) {
            return "";
        }
        final int index = tag.indexOf('-');
        String language = index > 0 ? tag.substring(0, index) : tag;
        return language.toLowerCase(Locale.ENGLISH);
    }

    private static boolean isSameLanguage(@NonNull String source, @NonNull String target) {
        return baseLanguage(source).equals(baseLanguage(target));
    }
}
