package app.site.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import app.site.LocalContainer
import app.site.R
import app.site.core.model.NewProject
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.DateField
import app.site.design.components.InfoBanner
import app.site.design.components.PrimaryButton
import app.site.design.components.SiteTextField
import app.site.design.components.Tone
import app.site.ui.fieldError
import app.site.ui.generalError
import kotlinx.coroutines.launch
import java.time.LocalDate

/** S03: a new project. Only the name is required; dates can come later. */
@Composable
fun CreateProjectScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var end by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    val pickPhoto = app.site.ui.rememberImagePicker { photo = it }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.new_project_title), onBack) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_create_project), {
                    busy = true
                    scope.launch {
                        container.projects.create(NewProject(name, address, start, end, photo))
                            .onSuccess { onCreated(it.id) }
                            .onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = name.isNotBlank())
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SiteTextField(name, { name = it; error = null }, stringResource(R.string.field_project_name), required = true,
                placeholder = stringResource(R.string.field_project_name_placeholder), error = fieldError(error, "name"))
            SiteTextField(address, { address = it }, stringResource(R.string.field_address),
                placeholder = stringResource(R.string.field_address_placeholder))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                DateField(stringResource(R.string.field_start), start, { start = it; error = null }, Modifier.weight(1f),
                    placeholder = stringResource(R.string.field_date_optional), onClear = { start = null })
                DateField(stringResource(R.string.field_end), end, { end = it; error = null }, Modifier.weight(1f),
                    placeholder = stringResource(R.string.field_date_optional), min = start, onClear = { end = null },
                    error = fieldError(error, "endDate"))
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                photo?.let {
                    coil.compose.AsyncImage(model = it, contentDescription = stringResource(R.string.a11y_site_photo),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(Size.thumb).clip(app.site.design.Radius.md))
                }
                app.site.design.components.SecondaryButton(
                    stringResource(if (photo == null) R.string.action_add_site_photo else R.string.action_change_site_photo),
                    pickPhoto, icon = R.drawable.ic_image_plus)
            }
        }
    }
}
