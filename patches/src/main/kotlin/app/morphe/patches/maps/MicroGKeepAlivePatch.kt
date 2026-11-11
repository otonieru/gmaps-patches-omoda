package app.morphe.patches.maps

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val KEEP_ALIVE_CLASS = "Lapp/morphe/extension/maps/patches/GmsCoreKeepAlive;"

/**
 * Head units (Chery/Omoda, BYD, ...) often kill MicroG-RE in the background. When Maps then
 * starts without a live provider, its Google API clients fail to connect and Maps falls back
 * to a signed-out state even though the account still exists in AccountManager.
 *
 * This starts MicroG-RE as early as possible (Application.onCreate, off the main thread) and
 * holds an important binding to it for as long as Maps is alive, so the provider shares Maps'
 * process priority instead of being killed during navigation.
 */
@Suppress("unused")
val microGKeepAlivePatch = bytecodePatch(
    name = "MicroG-RE keep-alive",
    description = "Starts MicroG-RE when Maps starts and keeps it bound while Maps runs, so sign-in and location survive aggressive head-unit app killers.",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(googleMapsMicroGPatch)

    execute {
        val method = findSuperclassHook(MAPS_APPLICATION_CLASS, "Maps application onCreate") {
            it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.isEmpty()
        }
        // Does not depend on Utils.setContext, so instruction order with the core patch does not matter.
        method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $KEEP_ALIVE_CLASS->onApplicationCreate(Landroid/content/Context;)V",
        )
    }
}
