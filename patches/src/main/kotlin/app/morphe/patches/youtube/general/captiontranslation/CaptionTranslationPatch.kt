package app.morphe.patches.youtube.general.captiontranslation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.general.transcript.indexOfNewTranscriptUrlRequestBuilderInstruction
import app.morphe.patches.youtube.general.transcript.transcriptUrlFingerprint
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.extension.Constants.GENERAL_PATH
import app.morphe.patches.youtube.utils.patch.PatchList.AUTO_TRANSLATE_CAPTIONS
import app.morphe.patches.youtube.utils.settings.ResourceUtils.addPreference
import app.morphe.patches.youtube.utils.settings.settingsPatch
import app.morphe.util.fingerprint.methodOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$GENERAL_PATH/CaptionTranslationPatch;"

@Suppress("unused")
val captionTranslationPatch = bytecodePatch(
    AUTO_TRANSLATE_CAPTIONS.title,
    AUTO_TRANSLATE_CAPTIONS.summary,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(settingsPatch)

    execute {

        // region rewrite caption request url

        // Same method the 'Set transcript cookies' patch hooks: the Cronet request for
        // caption (timedtext) data. The URL register is replaced right before the
        // request builder is created, so every caption request can carry 'tlang'.
        transcriptUrlFingerprint.methodOrThrow().apply {
            val urlIndex = indexOfNewTranscriptUrlRequestBuilderInstruction(this)
            val urlRegister = getInstruction<FiveRegisterInstruction>(urlIndex).registerD

            addInstructions(
                urlIndex, """
                    invoke-static { v$urlRegister }, $EXTENSION_CLASS_DESCRIPTOR->rewriteUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$urlRegister
                    """
            )
        }

        // endregion

        // region add settings

        addPreference(
            arrayOf(
                "PREFERENCE_SCREEN: GENERAL",
                "SETTINGS: AUTO_TRANSLATE_CAPTIONS"
            ),
            AUTO_TRANSLATE_CAPTIONS
        )

        // endregion
    }
}
