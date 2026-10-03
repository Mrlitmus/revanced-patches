package app.morphe.patches.youtube.player.translation

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.textcomponent.hookSpannableString
import app.morphe.patches.shared.textcomponent.hookTextComponent
import app.morphe.patches.shared.textcomponent.textComponentPatch
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.utils.extension.Constants.PATCHES_PATH
import app.morphe.patches.youtube.utils.patch.PatchList.TEXT_TRANSLATION
import app.morphe.patches.youtube.utils.settings.ResourceUtils.addPreference
import app.morphe.patches.youtube.utils.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$PATCHES_PATH/translation/TextTranslationPatch;"

@Suppress("unused")
val textTranslationPatch = bytecodePatch(
    TEXT_TRANSLATION.title,
    TEXT_TRANSLATION.summary,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        settingsPatch,
        textComponentPatch,
        engagementPanelHookPatch,
    )

    execute {
        // Start the translation while the SpannableString is built,
        // then swap in the translated text when the TextComponent is created.
        hookSpannableString(EXTENSION_CLASS_DESCRIPTOR, "preFetchLithoText")
        hookTextComponent(EXTENSION_CLASS_DESCRIPTOR)

        // region add settings

        addPreference(
            arrayOf(
                "PREFERENCE_SCREEN: PLAYER",
                "SETTINGS: TEXT_TRANSLATION"
            ),
            TEXT_TRANSLATION
        )

        // endregion
    }
}
