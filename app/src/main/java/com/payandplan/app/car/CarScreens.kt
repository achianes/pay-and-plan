package com.payandplan.app.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.payandplan.app.PayPlanApp
import com.payandplan.app.R
import com.payandplan.app.data.Payment
import com.payandplan.app.data.ShoppingItem
import com.payandplan.app.data.ShoppingList
import com.payandplan.app.util.Format
import kotlinx.coroutines.launch
import java.time.LocalDate

/** How many rows this particular car screen is willing to show. */
private fun Screen.rowLimit(): Int = runCatching {
    carContext.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
}.getOrDefault(6)

private fun Screen.icon(): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_notification)).build()

/** Two ways in, and nothing else to read at a glance. */
class HomeScreen(context: CarContext) : Screen(context) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Today and the next days")
                    .addText("Appointments, bills, reminders")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(TodayScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Shopping")
                    .addText("Tick things off on the way home")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(ListsScreen(carContext)) }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("Pay & Plan")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list)
            .build()
    }
}

/**
 * What is coming, soonest first. An appointment that was pinned on the map hands its place
 * straight to the navigation app; everything else is read only, which is what a car is for.
 */
class TodayScreen(context: CarContext) : Screen(context) {

    private var rows: List<Payment> = emptyList()
    private var loading = true

    init {
        val repo = PayPlanApp.repository(context)
        lifecycleScope.launch {
            val today = LocalDate.now()
            repo.observeBetween(today, today.plusDays(7)).collect { all ->
                rows = all.filter { it.isOpen }.sortedWith(compareBy({ it.dueDate }, { it.dueTimeMinutes }))
                loading = false
                invalidate()
            }
        }
    }

    private fun navigateTo(payment: Payment) {
        val lat = payment.latitude
        val lon = payment.longitude
        val uri = if (lat != null && lon != null) {
            Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(payment.title)})")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(payment.location)}")
        }
        runCatching { carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri)) }
            .onFailure { CarToast.makeText(carContext, "No navigation app here", CarToast.LENGTH_SHORT).show() }
    }

    override fun onGetTemplate(): Template {
        val currency = PayPlanApp.repository(carContext).currency()
        val builder = ItemList.Builder()
        val shown = rows.take(rowLimit())

        for (payment in shown) {
            val date = LocalDate.ofEpochDay(payment.dueDate)
            val when0 = "${Format.relative(date, LocalDate.now())} · ${Format.time(payment.dueTimeMinutes)}"
            val what = when {
                payment.isAppointment -> payment.location.ifBlank { "Appointment" }
                payment.isReminder -> "To do"
                payment.isIncome -> "In · ${Format.money(payment.amountCents, currency)}"
                else -> Format.money(payment.amountCents, currency)
            }
            val canDrive = payment.isAppointment &&
                (payment.location.isNotBlank() || (payment.latitude != null && payment.longitude != null))

            builder.addItem(
                Row.Builder()
                    .setTitle(payment.title)
                    .addText(when0)
                    .addText(what)
                    .apply {
                        if (canDrive) {
                            setImage(icon())
                            setOnClickListener { navigateTo(payment) }
                        }
                    }
                    .build()
            )
        }

        if (shown.isEmpty()) {
            builder.setNoItemsMessage(if (loading) "Reading the calendar…" else "Nothing due this week")
        }

        return ListTemplate.Builder()
            .setTitle("The week")
            .setHeaderAction(Action.BACK)
            .setSingleList(builder.build())
            .build()
    }
}

/** The lists somebody still has to do. */
class ListsScreen(context: CarContext) : Screen(context) {

    private var lists: List<ShoppingList> = emptyList()
    private var loading = true

    init {
        val repo = PayPlanApp.repository(context)
        lifecycleScope.launch {
            repo.observeLists().collect { all ->
                lists = all.filter { !it.isDone }
                loading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val builder = ItemList.Builder()
        val shown = lists.take(rowLimit())
        for (list in shown) {
            builder.addItem(
                Row.Builder()
                    .setTitle(list.title)
                    .addText(list.notes.ifBlank { "Tap to open" })
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(ItemsScreen(carContext, list.id, list.title)) }
                    .build()
            )
        }
        if (shown.isEmpty()) {
            builder.setNoItemsMessage(if (loading) "Reading the lists…" else "No list waiting")
        }
        return ListTemplate.Builder()
            .setTitle("Shopping")
            .setHeaderAction(Action.BACK)
            .setSingleList(builder.build())
            .build()
    }
}

/** One tap per thing: the tick travels to the other phones like any other change. */
class ItemsScreen(context: CarContext, private val listId: String, private val title: String) :
    Screen(context) {

    private var items: List<ShoppingItem> = emptyList()
    private var loading = true

    init {
        val repo = PayPlanApp.repository(context)
        lifecycleScope.launch {
            repo.observeItems(listId).collect { all ->
                items = all
                loading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val repo = PayPlanApp.repository(carContext)
        val builder = ItemList.Builder()
        // what is still missing comes first: that is what the shopper is looking for
        val ordered = items.sortedBy { it.checked }
        val shown = ordered.take(rowLimit())

        for (item in shown) {
            val toggle = Toggle.Builder { checked ->
                lifecycleScope.launch { repo.updateItem(item.copy(checked = checked)) }
            }.setChecked(item.checked).build()

            builder.addItem(
                Row.Builder()
                    .setTitle(item.text)
                    .apply { if (item.quantity.isNotBlank()) addText(item.quantity) }
                    .setToggle(toggle)
                    .build()
            )
        }
        if (shown.isEmpty()) {
            builder.setNoItemsMessage(if (loading) "Reading the list…" else "Nothing on this list")
        }
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(builder.build())
            .build()
    }
}
