package com.leica.cam.common

/**
 * Legacy flat result type — kept for binary compatibility only.
 *
 * **DEPRECATED:** All pipeline code must use the typed sealed class
 * [com.leica.cam.common.result.LeicaResult] from `platform/common/result/`
 * instead.  That version has typed `Failure` sub-types (`Pipeline`,
 * `Hardware`, `Recoverable`) and functional operators (`map`, `flatMap`,
 * `getOrElse`, `fold`).
 *
 * Removal target: once all call-sites are migrated, delete this file.
 */
@Deprecated(
    message = "Use com.leica.cam.common.result.LeicaResult instead; this flat version lacks typed failure sub-types.",
    replaceWith = ReplaceWith(
        "LeicaResult",
        "com.leica.cam.common.result.LeicaResult",
    ),
)
sealed interface LeicaResult<out T> {
    data class Success<T>(val value: T) : LeicaResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : LeicaResult<Nothing>
}
