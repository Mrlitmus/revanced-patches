package app.morphe.extension.youtube.patches.translation;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.translation.TextTranslator;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;

/**
 * Automatically translates comment and video description text rendered by Litho,
 * and appends a tappable "Copy" link below the text.
 * <p>
 * Uses the same two injection points as Return YouTube Username:
 * {@link #preFetchLithoText} starts the translation while the SpannableString is being built,
 * and {@link #onLithoTextLoaded} swaps in the translated text once the TextComponent is created.
 */
@SuppressWarnings("unused")
public final class TextTranslationPatch {

    /**
     * The YouTube handle in comments. Never translate it.
     */
    private static final String AUTHOR_BADGE_PATH = "|author_badge.";

    /**
     * Comment body text in the comments panel.
     */
    private static final String COMMENT_PATH = "|comment.";

    /**
     * Comment preview shown below the video.
     */
    private static final String COMMENT_PREVIEW_PATH = "comments_entry_point_teaser";

    /**
     * Known path fragments of the description body.
     */
    private static final String[] DESCRIPTION_PATHS = {
            "video_description",
            "expandable_description",
            "description_body",
            "description_text",
    };

    /**
     * Components inside the description panel that must never be translated.
     */
    private static final String[] NOT_DESCRIPTION_PATHS = {
            "comment",
            "lockup",
            "shelf",
            "chip",
            "button",
            "author",
            "channel",
            "badge",
    };

    /**
     * Texts in the description panel shorter than this are treated as labels
     * (channel name, view count, date) and are left alone.
     */
    private static final int MINIMUM_DESCRIPTION_LENGTH = 24;

    /**
     * A copy link is added to untranslated text only when it is at least this long,
     * so short UI labels ("Reply", "Show replies") do not get one.
     */
    private static final int MINIMUM_COPY_LENGTH = 30;

    /**
     * Maximum time Litho layout waits for a translation. Layout runs off the main thread,
     * and a timed out translation is still cached and used the next time the text is laid out.
     */
    private static final int MAXIMUM_WAIT_MILLISECONDS = 4_000;

    private static final int CACHE_SIZE = 400;

    private static final int LINK_COLOR = 0xFF3EA6FF;

    private static final String KEY_SEPARATOR = "\u0000";

    private enum Kind {
        NONE,
        COMMENT,
        DESCRIPTION
    }

    /**
     * Translation requests keyed by target language and original text.
     */
    private static final Map<String, TranslationRequest> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(CACHE_SIZE));

    /**
     * Texts this patch produced. Litho hands modified text back into the hooks when a
     * component is reused, and those must not be processed a second time.
     */
    private static final Map<String, Boolean> producedTexts =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(CACHE_SIZE));

    private TextTranslationPatch() {
    }

    /**
     * Injection point.
     * <p>
     * Called before the SpannableString is built. Starts the translation so it is
     * (usually) finished by the time {@link #onLithoTextLoaded} runs.
     */
    @NonNull
    public static CharSequence preFetchLithoText(@Nullable Object conversionContext,
                                                 @NonNull CharSequence original) {
        try {
            if (conversionContext == null || isProduced(original)) {
                return original;
            }
            String text = original.toString();
            Kind kind = classify(conversionContext.toString(), text);
            if (kind == Kind.NONE) {
                return original;
            }
            String target = resolveTargetLanguage();
            if (!shouldSkipLocally(text, target)) {
                requestTranslation(text, target);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "preFetchLithoText failure", ex);
        }
        return original;
    }

    /**
     * Injection point.
     * <p>
     * Called when the TextComponent is created. Usually off the main thread,
     * but occasionally on it, in which case no waiting is done.
     */
    @NonNull
    public static CharSequence onLithoTextLoaded(@Nullable Object conversionContext,
                                                 @NonNull CharSequence original) {
        try {
            if (conversionContext == null || isProduced(original)) {
                return original;
            }
            String text = original.toString();
            Kind kind = classify(conversionContext.toString(), text);
            if (kind == Kind.NONE) {
                return original;
            }

            String target = resolveTargetLanguage();
            String translated = null;
            if (!shouldSkipLocally(text, target)) {
                TranslationRequest request = requestTranslation(text, target);
                translated = request.get(!Utils.isCurrentlyOnMainThread());
            }

            final boolean copyEnabled = Settings.TEXT_TRANSLATION_COPY_BUTTON.get();
            final boolean addCopyLink = copyEnabled
                    && (translated != null || text.trim().length() >= MINIMUM_COPY_LENGTH);

            if (translated == null && !addCopyLink) {
                return original;
            }

            final String translatedText = translated;
            Logger.printDebug(() -> "Text " + kind + " translated: " + (translatedText != null)
                    + " copy: " + addCopyLink + " context: " + conversionContext);

            return buildDisplayText(kind, original, text, translated, addCopyLink);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return original;
    }

    // region Classification

    private static Kind classify(@NonNull String context, @NonNull String text) {
        final boolean comments = Settings.TEXT_TRANSLATION_COMMENTS.get();
        final boolean description = Settings.TEXT_TRANSLATION_DESCRIPTION.get();
        if (!comments && !description) {
            return Kind.NONE;
        }
        if (context.contains(AUTHOR_BADGE_PATH)) {
            return Kind.NONE;
        }
        if (comments && (context.contains(COMMENT_PATH) || context.contains(COMMENT_PREVIEW_PATH))) {
            return Kind.COMMENT;
        }
        if (description && EngagementPanel.isDescription()) {
            for (String path : DESCRIPTION_PATHS) {
                if (context.contains(path)) {
                    return Kind.DESCRIPTION;
                }
            }
            if (text.trim().length() >= MINIMUM_DESCRIPTION_LENGTH) {
                for (String path : NOT_DESCRIPTION_PATHS) {
                    if (context.contains(path)) {
                        return Kind.NONE;
                    }
                }
                return Kind.DESCRIPTION;
            }
        }
        return Kind.NONE;
    }

    private static boolean isProduced(@NonNull CharSequence text) {
        if (text instanceof Spanned spanned
                && spanned.getSpans(0, spanned.length(), ActionSpan.class).length > 0) {
            return true;
        }
        return producedTexts.containsKey(text.toString());
    }

    /**
     * Cheap checks that avoid a network request for text that cannot need translation.
     */
    private static boolean shouldSkipLocally(@NonNull String text, @NonNull String target) {
        String trimmed = text.trim();
        if (trimmed.length() < 2) {
            return true;
        }
        // Handles and bare links.
        if (trimmed.startsWith("@") && !trimmed.contains(" ")) {
            return true;
        }
        if ((trimmed.startsWith("http://") || trimmed.startsWith("https://")) && !trimmed.contains(" ")) {
            return true;
        }

        boolean hasLetter = false;
        boolean hasKana = false;
        boolean hasHangul = false;
        boolean hasHan = false;
        for (int i = 0, length = trimmed.length(); i < length; ) {
            final int codePoint = trimmed.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            hasLetter = true;
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                hasKana = true;
            } else if (script == Character.UnicodeScript.HANGUL) {
                hasHangul = true;
            } else if (script == Character.UnicodeScript.HAN) {
                hasHan = true;
            }
        }
        if (!hasLetter) {
            return true; // Numbers, timestamps, emoji only.
        }

        String language = baseLanguage(target);
        switch (language) {
            case "ja":
                return hasKana;
            case "ko":
                return hasHangul;
            case "zh":
                return hasHan && !hasKana && !hasHangul;
            default:
                return false;
        }
    }

    // endregion

    // region Translation

    @NonNull
    private static String resolveTargetLanguage() {
        String setting = Settings.TEXT_TRANSLATION_TARGET_LANGUAGE.get();
        String tag = (setting == null || setting.isEmpty() || setting.equals("app"))
                ? Locale.getDefault().toLanguageTag()
                : setting;
        // Google only distinguishes region for Chinese (zh-CN / zh-TW).
        return tag.startsWith("zh") ? tag : baseLanguage(tag);
    }

    @NonNull
    private static String baseLanguage(@NonNull String tag) {
        final int index = tag.indexOf('-');
        String language = index > 0 ? tag.substring(0, index) : tag;
        return language.toLowerCase(Locale.ENGLISH);
    }

    private static boolean isSameLanguage(@NonNull String source, @NonNull String target) {
        return baseLanguage(source).equals(baseLanguage(target));
    }

    @NonNull
    private static TranslationRequest requestTranslation(@NonNull String text, @NonNull String target) {
        final String key = target + KEY_SEPARATOR + text;
        synchronized (cache) {
            TranslationRequest request = cache.get(key);
            if (request == null) {
                request = new TranslationRequest(key, text, target);
                cache.put(key, request);
            }
            return request;
        }
    }

    /**
     * @return The translated text, or null if the text is already in the target language
     * or the translation failed.
     */
    @Nullable
    private static String fetch(@NonNull String key, @NonNull String text, @NonNull String target) {
        try {
            TextTranslator.DetectedTranslation result = TextTranslator.detectAndTranslate(text, target);
            final String source = result.sourceLanguage;
            if (!source.isEmpty() && isSameLanguage(source, target)) {
                Logger.printDebug(() -> "Already in target language (" + source + "): " + text);
                return null;
            }
            String translated = result.translated.trim();
            if (translated.isEmpty() || translated.equals(text.trim())) {
                return null;
            }
            Logger.printDebug(() -> "Translated (" + source + " -> " + target + "): " + text);
            return translated;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Translation failed: " + text, ex);
            // Allow a retry the next time this text is shown.
            cache.remove(key);
            return null;
        }
    }

    private static final class TranslationRequest {
        private final Future<String> future;

        TranslationRequest(@NonNull String key, @NonNull String text, @NonNull String target) {
            future = Utils.submitOnBackgroundThread(() -> fetch(key, text, target));
        }

        /**
         * @param wait If the calling thread may block waiting for the translation.
         */
        @Nullable
        String get(boolean wait) {
            if (!wait && !future.isDone()) {
                return null;
            }
            try {
                return future.get(MAXIMUM_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                Logger.printDebug(() -> "Translation not ready in time");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                Logger.printException(() -> "Translation failure", ex);
            }
            return null;
        }
    }

    // endregion

    // region Display

    @NonNull
    private static CharSequence buildDisplayText(@NonNull Kind kind,
                                                 @NonNull CharSequence original,
                                                 @NonNull String text,
                                                 @Nullable String translated,
                                                 boolean addCopyLink) {
        final boolean showOriginal = kind == Kind.COMMENT
                ? Settings.TEXT_TRANSLATION_COMMENTS_SHOW_ORIGINAL.get()
                : Settings.TEXT_TRANSLATION_DESCRIPTION_SHOW_ORIGINAL.get();

        SpannableStringBuilder builder = new SpannableStringBuilder();
        final String displayedText;

        if (translated == null) {
            builder.append(original);
            displayedText = text;
        } else if (showOriginal) {
            builder.append(original);
            builder.append("\n\n");
            appendStyled(builder, translated, original);
            displayedText = text + "\n\n" + translated;
        } else {
            appendStyled(builder, translated, original);
            displayedText = translated;
        }

        if (addCopyLink) {
            builder.append('\n');
            appendLink(builder, "⧉ " + str("revanced_text_translation_copy"),
                    () -> Utils.setClipboard(displayedText, str("revanced_text_translation_copied")));

            if (translated != null && !showOriginal) {
                builder.append(" ");
                appendLink(builder, str("revanced_text_translation_show_original"),
                        () -> showOriginalDialog(text));
            }
        }

        producedTexts.put(builder.toString(), Boolean.TRUE);
        return builder;
    }

    /**
     * Appends text styled like the original: spans covering the whole original text
     * (color, size, typeface) are copied, but links and images are not.
     */
    private static void appendStyled(@NonNull SpannableStringBuilder builder,
                                     @NonNull String text,
                                     @NonNull CharSequence original) {
        final int start = builder.length();
        builder.append(text);
        final int end = builder.length();

        if (original instanceof Spanned spanned) {
            final int length = spanned.length();
            for (Object span : spanned.getSpans(0, length, Object.class)) {
                if (span instanceof ClickableSpan || span instanceof ImageSpan) {
                    continue;
                }
                if (spanned.getSpanStart(span) == 0 && spanned.getSpanEnd(span) == length) {
                    builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }

    private static void appendLink(@NonNull SpannableStringBuilder builder,
                                   @NonNull String label,
                                   @NonNull Runnable action) {
        final int start = builder.length();
        builder.append(label);
        builder.setSpan(new ActionSpan(action), start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void showOriginalDialog(@NonNull String original) {
        Utils.runOnMainThreadNowOrLater(() -> {
            Activity activity = Utils.getActivity();
            if (activity == null) {
                Utils.setClipboard(original, str("revanced_text_translation_copied"));
                return;
            }
            CustomDialog.create(
                    activity,
                    str("revanced_text_translation_original_dialog_title"),
                    original,
                    null,
                    str("revanced_text_translation_copy"),
                    () -> Utils.setClipboard(original, str("revanced_text_translation_copied")),
                    () -> {
                    },
                    null,
                    null,
                    true
            ).first.show();
        });
    }

    /**
     * Tappable link appended below the text.
     */
    private static final class ActionSpan extends ClickableSpan {
        private final Runnable action;

        ActionSpan(@NonNull Runnable action) {
            this.action = action;
        }

        @Override
        public void onClick(@NonNull View widget) {
            try {
                action.run();
            } catch (Exception ex) {
                Logger.printException(() -> "ActionSpan onClick failure", ex);
            }
        }

        @Override
        public void updateDrawState(@NonNull TextPaint paint) {
            paint.setColor(LINK_COLOR);
            paint.setUnderlineText(false);
        }
    }

    // endregion
}
