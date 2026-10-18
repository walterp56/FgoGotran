package com.fgogotran.chapter.codec

import com.fgogotran.chapter.AssetBytes

/** Public asset-format parameters, not account/session/API credentials. JP observed format only.
 * Research provenance and limitations: chapter/THIRD_PARTY.md and README.md.
 * They are never read from the desktop's ignored fixtures on an Android device.
 */
object FgoJpFormatProfile {
    const val GAME_PACKAGE = "com.aniplex.fategrandorder"
    const val MANIFEST_NAME = "cfb1d36393fd67385e046b084b7cf7ed"

    fun manifestCodec() = FgoInstalledManifestCodec(stageEnvelope())
    fun scriptCodec() = FgoNativeScriptCodec(stageEnvelope())
    fun containerCodec() = FgoJpContainerCodec(RijndaelEnvelope(
        hex("50464273756e6f784c627144317253683653414c464c5566334633464c496b33"),
        hex("306549754b6b43635665725568533237334138744776747a46654b454e766934")
    ))
    private fun stageEnvelope() = RijndaelEnvelope(
        hex("6b64746d71486630735567686a434f31737169584771567148796a6b76435333"),
        hex("7a4d707a43417830613149545975444a7474737359526372526433364a797567")
    )
    private fun hex(value: String) = AssetBytes.copyOf(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}
