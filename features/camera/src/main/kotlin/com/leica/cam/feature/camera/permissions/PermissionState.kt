package com.leica.cam.feature.camera.permissions

/**
 * Plain-data model of runtime permission state. Not tied to Accompanist
 * so this file compiles without any Android test deps and is unit-testable.
 */
sealed interface LeicaPermissionState {
    data object Unknown : LeicaPermissionState
    data object AllGranted : LeicaPermissionState
    data class NeedsRationale(val permissions: List<String>) : LeicaPermissionState
    data class PermanentlyDenied(val permissions: List<String>) : LeicaPermissionState
}

/**
 * Deterministic reducer that maps raw (permission, isGranted, shouldShowRationale)
 * triples to a [LeicaPermissionState]. Pure — no Android imports, fully testable.
 */
object LeicaPermissionReducer {
    fun reduce(
        grants: Map<String, Boolean>,
        rationales: Map<String, Boolean>,
        required: List<String> = RequiredPermissions.mustHave,
    ): LeicaPermissionState {
        val missing = required.filter { grants[it] != true }
        if (missing.isEmpty()) return LeicaPermissionState.AllGranted
        val needsRationale = missing.filter { rationales[it] == true }
        return if (needsRationale.isNotEmpty()) {
            LeicaPermissionState.NeedsRationale(needsRationale)
        } else {
            val asked = grants.keys.intersect(missing.toSet())
            if (asked.isEmpty()) LeicaPermissionState.Unknown
            else LeicaPermissionState.PermanentlyDenied(missing)
        }
    }
}