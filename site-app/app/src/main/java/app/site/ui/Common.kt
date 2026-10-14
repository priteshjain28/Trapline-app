package app.site.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.site.R
import app.site.core.data.AppError
import app.site.core.model.ProjectStatus
import app.site.design.components.Tone

/** Every failure becomes plain language. Server text never reaches the screen. */
@StringRes
fun errorRes(e: Throwable): Int = when (e) {
    is AppError.Offline -> R.string.err_offline
    is AppError.NotAllowed -> R.string.err_not_allowed
    is AppError.Invalid -> when (e.reason) {
        AppError.Reason.REQUIRED -> R.string.err_required
        AppError.Reason.TOO_LONG -> R.string.err_too_long
        AppError.Reason.END_BEFORE_START -> R.string.err_end_before_start
        AppError.Reason.NOT_POSITIVE -> R.string.err_not_positive
        AppError.Reason.WRONG_CODE -> R.string.err_wrong_code
        AppError.Reason.BAD_PHONE -> R.string.err_bad_phone
    }
    else -> R.string.err_unknown
}

/** Error text for one field, or null when the error is about something else. */
@Composable
fun fieldError(e: Throwable?, field: String): String? =
    (e as? AppError.Invalid)?.takeIf { it.field == field }?.let { stringResource(errorRes(it)) }

/** Error text for anything that isn't a field error (shown in a banner). */
@Composable
fun generalError(e: Throwable?): String? =
    e?.takeIf { it !is AppError.Invalid }?.let { stringResource(errorRes(it)) }

@StringRes
fun ProjectStatus.label(): Int = when (this) {
    ProjectStatus.ACTIVE -> R.string.status_active
    ProjectStatus.ON_HOLD -> R.string.status_on_hold
    ProjectStatus.COMPLETED -> R.string.status_completed
    ProjectStatus.ARCHIVED -> R.string.status_archived
}

fun ProjectStatus.tone(): Tone = when (this) {
    ProjectStatus.ACTIVE -> Tone.SUCCESS
    ProjectStatus.ON_HOLD -> Tone.WARNING
    ProjectStatus.COMPLETED -> Tone.INFO
    ProjectStatus.ARCHIVED -> Tone.NEUTRAL
}

/** Screens not built yet. Each opens an honest placeholder, never a dead tap. */
enum class Planned(val id: String, @StringRes val title: Int) {
    MESSAGES("S33", R.string.screen_messages),
    HELP("S37", R.string.screen_help),
    EXPENSES("S26", R.string.screen_expenses),
    ISSUES("S31", R.string.screen_issues),
    GALLERY("S30", R.string.screen_gallery),
    FILES("S34", R.string.screen_files),
    CAMERA("S32", R.string.screen_camera),
    SITE_PROFILE("S35", R.string.screen_site_profile),
    MEMBERS("S36", R.string.screen_members),
    ;

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}
