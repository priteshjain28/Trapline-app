package app.site.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.model.OrgRole
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.Avatar
import app.site.design.components.InfoBanner
import app.site.design.components.PrimaryButton
import app.site.design.components.SecondaryButton
import app.site.design.components.SiteTextField
import app.site.design.components.Tone
import app.site.ui.fieldError
import app.site.ui.generalError
import app.site.ui.photoModel
import app.site.ui.rememberImagePicker
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** S29: the company's name and logo. Owners and admins change them; everyone else sees them. */
@Composable
fun CompanyScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val session by container.session.session.collectAsStateWithLifecycle()
    val org = session?.org
    val canEdit = session?.orgRole == OrgRole.OWNER || session?.orgRole == OrgRole.ADMIN
    var name by rememberSaveable(org?.id) { mutableStateOf(org?.name.orEmpty()) }
    var logo by rememberSaveable(org?.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val pick = rememberImagePicker { logo = it; saved = false }
    val shown = logo ?: org?.logoRef

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.company_title), onBack) },
        bottomBar = {
            if (canEdit) Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_save), {
                    busy = true
                    scope.launch {
                        container.session.updateOrganisation(name, logo).onSuccess { saved = true; logo = null }.onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = name.isNotBlank() && (name.trim() != org?.name || logo != null))
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            if (!canEdit) InfoBanner(stringResource(R.string.company_admins_only))
            if (saved) InfoBanner(stringResource(R.string.company_saved), tone = Tone.SUCCESS, icon = R.drawable.ic_check)
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Box(Modifier.size(72.dp).clip(Radius.md).background(SiteTheme.colors.surfaceTrack), contentAlignment = Alignment.Center) {
                    if (shown != null) {
                        AsyncImage(model = photoModel(shown), contentDescription = stringResource(R.string.a11y_company_logo),
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else Avatar(name.ifBlank { "?" }, size = 72.dp, square = true)
                }
                if (canEdit) {
                    SecondaryButton(stringResource(if (shown == null) R.string.action_add_logo else R.string.action_change_logo), pick,
                        icon = R.drawable.ic_image_plus)
                }
            }
            SiteTextField(name, { name = it.take(120); saved = false; error = null }, stringResource(R.string.field_company_name),
                required = true, enabled = canEdit, error = fieldError(error, "name"))
        }
    }
}
