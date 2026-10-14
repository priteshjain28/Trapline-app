package app.site.ui.money

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.ExpenseCategory
import app.site.core.model.Payable
import app.site.core.model.PayableKind
import app.site.core.model.PayableStatus
import app.site.core.model.PaymentMode
import app.site.core.model.SpendCategory
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.DateField
import app.site.design.components.FilterChip
import app.site.design.components.InfoBanner
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.PrimaryButton
import app.site.design.components.SecondaryButton
import app.site.design.components.SectionLabel
import app.site.design.components.SiteTextField
import app.site.design.components.StatusBadge
import app.site.design.components.TextAction
import app.site.design.components.Tone
import app.site.ui.errorRes
import app.site.ui.fieldError
import app.site.ui.generalError
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A bill with what's left to pay, and a Pay action while anything is due. */
@Composable
fun PayableRow(p: Payable, title: String, onPay: () -> Unit) {
    val c = SiteTheme.colors
    ListRow {
        Column(Modifier.weight(1f).padding(end = Space.s2)) {
            Text(title, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (p.paidPaise > 0) stringResource(R.string.payable_paid_of, Format.rupees(p.paidPaise), Format.rupees(p.amountPaise))
                else Format.rupees(p.amountPaise),
                style = SiteTheme.type.sm, color = c.textMuted,
            )
        }
        when (p.status) {
            PayableStatus.PAID -> StatusBadge(stringResource(R.string.payable_status_paid), Tone.SUCCESS)
            else -> {
                StatusBadge(stringResource(if (p.status == PayableStatus.PARTLY_PAID) R.string.payable_status_partly else R.string.payable_status_due),
                    Tone.WARNING)
                TextAction(stringResource(R.string.action_pay), onPay)
            }
        }
    }
}

private fun modeLabel(m: PaymentMode) = when (m) {
    PaymentMode.CASH -> R.string.mode_cash
    PaymentMode.UPI -> R.string.mode_upi
    PaymentMode.BANK -> R.string.mode_bank
    PaymentMode.CHEQUE -> R.string.mode_cheque
    PaymentMode.OTHER -> R.string.mode_other
}

/** Record a payment against a bill: amount (what's due, by default), date, how, reference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaySheet(p: Payable, title: String, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf(Format.rupeesPlain(p.duePaise)) }
    var date by rememberSaveable { mutableStateOf(container.today()) }
    var mode by rememberSaveable { mutableStateOf(PaymentMode.CASH) }
    var reference by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val paise = Format.parseRupees(amount)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        Column(
            Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(stringResource(R.string.pay_title, title), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            Text(stringResource(R.string.payable_due, Format.rupees(p.duePaise)), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SiteTextField(amount, { amount = it; error = null }, stringResource(R.string.field_amount), prefix = "₹", keyboardType = KeyboardType.Decimal,
                error = when {
                    amount.isNotBlank() && paise == null -> stringResource(R.string.err_money)
                    paise != null && paise > p.duePaise -> stringResource(R.string.err_more_than_due)
                    else -> fieldError(error, "amount")
                })
            DateField(stringResource(R.string.field_paid_on), date, { date = it }, max = container.today(), error = fieldError(error, "date"))
            Text(stringResource(R.string.field_mode), style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = SiteTheme.colors.text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                PaymentMode.entries.forEach { m -> FilterChip(stringResource(modeLabel(m)), m == mode, { mode = m }) }
            }
            SiteTextField(reference, { reference = it.take(60) }, stringResource(R.string.field_reference),
                placeholder = stringResource(R.string.field_reference_placeholder))
            PrimaryButton(stringResource(R.string.action_pay), {
                busy = true
                scope.launch {
                    container.money.pay(p.id, paise ?: 0, date, mode, reference).onSuccess { onDismiss() }.onFailure { error = it }
                    busy = false
                }
            }, large = true, loading = busy, enabled = paise != null && paise > 0 && paise <= p.duePaise)
        }
    }
}

private fun spendLabel(c: SpendCategory) = when (c) {
    SpendCategory.MATERIAL -> R.string.spend_material
    SpendCategory.SUBCONTRACTOR -> R.string.spend_subcontractor
    SpendCategory.ATTENDANCE -> R.string.spend_attendance
    SpendCategory.PETTY_SPEND -> R.string.spend_petty
    SpendCategory.MISCELLANEOUS -> R.string.spend_misc
    SpendCategory.ASSETS -> R.string.spend_assets
}

private fun expenseLabel(c: ExpenseCategory) = spendLabel(SpendCategory.valueOf(c.name))

/** S26: what this site has cost, in six buckets; bills to pay; spend that isn't a bill. */
@Composable
fun ExpensesScreen(projectId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val canSee by remember(projectId) { container.money.canSeeMoney(projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val spend by remember(projectId) { container.money.spend(projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val payables by remember(projectId) { container.money.payables(projectId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val expenses by remember(projectId) { container.money.expenses(projectId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val workers by remember(projectId) { container.attendance.workers(projectId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var paying by remember { mutableStateOf<Payable?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }   // "spend" | "bill"
    val c = SiteTheme.colors
    val names = workers.associate { it.id to it.name }
    @Composable fun titleOf(p: Payable) = when (p.kind) {
        PayableKind.CUSTOM -> p.title.orEmpty()
        PayableKind.ATTENDANCE -> stringResource(R.string.payable_period, names[p.workerId].orEmpty(),
            Format.range(p.periodStart!!, p.periodEnd!!, container.today()))
    }

    Scaffold(topBar = { AppBarSub(stringResource(R.string.screen_expenses), onBack) }, containerColor = c.bg) { padding ->
        when {
            canSee == null || spend == null -> Box(Modifier.padding(padding)) { LoadingBlock() }
            canSee == false -> Box(Modifier.padding(padding).padding(Size.gutter)) { InfoBanner(stringResource(R.string.money_hidden)) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = Space.s7)) {
                item {
                    Column(Modifier.padding(Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                        Text(stringResource(R.string.spend_total), style = SiteTheme.type.sm, color = c.textMuted)
                        Text(Format.rupees(spend!!.totalPaise), style = SiteTheme.type.xl.copy(fontWeight = FontWeight.W600), color = c.text)
                    }
                }
                items(SpendCategory.entries.toList(), key = { it.name }) { cat ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Size.gutter, vertical = Space.s1), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(spendLabel(cat)), Modifier.weight(1f), style = SiteTheme.type.base, color = c.text)
                        Text(Format.rupees(spend!!.byCategory[cat] ?: 0), style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text)
                    }
                }
                item {
                    Column(Modifier.padding(Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Text(stringResource(R.string.spend_note), style = SiteTheme.type.xs, color = c.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            SecondaryButton(stringResource(R.string.action_add_spend), { adding = "spend" }, Modifier.weight(1f), icon = R.drawable.ic_plus)
                            SecondaryButton(stringResource(R.string.action_add_bill), { adding = "bill" }, Modifier.weight(1f), icon = R.drawable.ic_plus)
                        }
                    }
                }
                item { SectionLabel(stringResource(R.string.bills_title), Modifier.padding(horizontal = Size.gutter)) }
                if (payables.isEmpty()) item {
                    Text(stringResource(R.string.bills_none), Modifier.padding(horizontal = Size.gutter, vertical = Space.s2), style = SiteTheme.type.sm, color = c.textMuted)
                }
                // what's still due first
                items(payables.sortedBy { it.status == PayableStatus.PAID }, key = { it.id }) { p ->
                    val t = titleOf(p)
                    PayableRow(p, t) { paying = p }
                }
                item { SectionLabel(stringResource(R.string.spends_title), Modifier.padding(horizontal = Size.gutter)) }
                if (expenses.isEmpty()) item {
                    Text(stringResource(R.string.spends_none), Modifier.padding(horizontal = Size.gutter, vertical = Space.s2), style = SiteTheme.type.sm, color = c.textMuted)
                }
                items(expenses, key = { it.id }) { e ->
                    ListRow {
                        Column(Modifier.weight(1f).padding(end = Space.s2)) {
                            Text(e.note ?: stringResource(expenseLabel(e.category)), style = SiteTheme.type.base, color = c.text, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Text("${stringResource(expenseLabel(e.category))} · ${Format.short(e.spentOn)}", style = SiteTheme.type.sm, color = c.textMuted)
                        }
                        Text(Format.rupees(e.amountPaise), style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text)
                    }
                }
            }
        }
    }
    paying?.let { p -> PaySheet(p, titleOf(p)) { paying = null } }
    adding?.let { kind -> AddMoneySheet(projectId, kind, onDismiss = { adding = null }) }
}

/** Spend that isn't a bill (petty cash, sundries, tools), or a bill to pay later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMoneySheet(projectId: String, kind: String, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf(ExpenseCategory.PETTY_SPEND) }
    var text by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<LocalDate>(container.today()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val paise = Format.parseRupees(amount)
    val bill = kind == "bill"
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        Column(
            Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(stringResource(if (bill) R.string.action_add_bill else R.string.action_add_spend), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            if (!bill) {
                Text(stringResource(R.string.field_spend_kind), style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = SiteTheme.colors.text)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    ExpenseCategory.entries.forEach { cat -> FilterChip(stringResource(expenseLabel(cat)), cat == category, { category = cat }) }
                }
            }
            SiteTextField(text, { text = it.take(if (bill) 120 else 500); error = null },
                stringResource(if (bill) R.string.field_bill_for else R.string.field_notes),
                placeholder = stringResource(if (bill) R.string.field_bill_for_placeholder else R.string.field_spend_note_placeholder),
                error = fieldError(error, "title"))
            SiteTextField(amount, { amount = it; error = null }, stringResource(R.string.field_amount), prefix = "₹", keyboardType = KeyboardType.Decimal,
                error = if (amount.isNotBlank() && paise == null) stringResource(R.string.err_money) else fieldError(error, "amount"))
            if (!bill) DateField(stringResource(R.string.field_spent_on), date, { date = it }, max = container.today())
            PrimaryButton(stringResource(R.string.action_save), {
                busy = true
                scope.launch {
                    val r = if (bill) container.money.addCustomPayable(projectId, text, paise ?: 0).map { }
                        else container.money.addExpense(projectId, category, paise ?: 0, date, text).map { }
                    r.onSuccess { onDismiss() }.onFailure { error = it }
                    busy = false
                }
            }, large = true, loading = busy, enabled = paise != null && paise > 0 && (!bill || text.isNotBlank()))
        }
    }
}

/** Shown to people who can't see money, wherever money would be. */
@Composable
fun MoneyHidden(modifier: Modifier = Modifier) = InfoBanner(stringResource(R.string.money_hidden), modifier)
