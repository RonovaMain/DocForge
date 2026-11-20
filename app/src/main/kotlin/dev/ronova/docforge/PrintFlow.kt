package dev.ronova.docforge

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintJob
import android.print.PrintManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.ronova.docforge.core.DocException
import dev.ronova.docforge.databinding.DialogPrintBinding
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Печать: окно настроек (принтер, страницы, бумага, поля, ориентация, ручная двусторонняя печать) и выполнение по проходам.
 * Печатать можно через системную печать Android или напрямую на USB-принтер (PCL, без драйверов).
 */
class PrintFlow(private val activity: AppCompatActivity, private val host: Host) {

    interface Host {
        val docName: String

        /** Для PDF — сам файл; для остальных форматов null (страницы собирает печатный адаптер WebView). */
        val pdfFile: File?
        val currentPage: Int

        /** Вёрстка открытого документа в PDF по заданным параметрам бумаги; null в [callback] — успех. */
        fun renderToPdf(attributes: PrintAttributes, out: File, callback: (String?) -> Unit)

        /** Запасной вариант, если PDF собрать не удалось: обычный диалог печати WebView. */
        fun systemPrintFallback()

        fun showError(error: Throwable)

        fun snack(text: String)
    }

    private class Job(val source: File, val total: Int, val options: PrintOptions, val passes: List<List<Int>>, val dir: File)

    private val usb = UsbPrinters(activity)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val devices = HashMap<Int, UsbDevice>()
    private var busy: AlertDialog? = null
    private var busyText: TextView? = null

    @Volatile
    private var cancelled = false

    // ------------------------------------------------------------------ окно настроек

    fun show() {
        val b = DialogPrintBinding.inflate(activity.layoutInflater)
        setupForm(b)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.prn_title)
            .setView(b.root)
            .setPositiveButton(R.string.prn_start, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val options = readForm(b) ?: return@setOnClickListener
                dialog.dismiss()
                begin(options)
            }
        }
        dialog.show()
    }

    private fun setupForm(b: DialogPrintBinding) {
        b.paperSpinner.adapter = spinnerAdapter(PaperSize.values().map { it.label })
        b.marginSpinner.adapter = spinnerAdapter(
            listOf(R.string.prn_margin_0, R.string.prn_margin_1, R.string.prn_margin_2, R.string.prn_margin_3).map { activity.getString(it) },
        )
        b.marginSpinner.setSelection(2)
        b.qualitySpinner.adapter = spinnerAdapter(listOf(activity.getString(R.string.prn_quality_draft), activity.getString(R.string.prn_quality_normal)))
        b.qualitySpinner.setSelection(1)

        // «Текущая страница» понятна только для PDF: у остальных форматов страницы появляются при вёрстке под бумагу
        b.pagesCurrent.visibility = if (host.pdfFile != null) View.VISIBLE else View.GONE
        b.pagesGroup.setOnCheckedChangeListener { _, id -> b.rangeInput.isEnabled = id == R.id.pagesRange }
        b.duplexGroup.setOnCheckedChangeListener { _, id ->
            b.reverseBacks.visibility = if (id == R.id.duplexBatch) View.VISIBLE else View.GONE
        }
        b.printerGroup.setOnCheckedChangeListener { _, id ->
            b.usbSection.visibility = if (devices.containsKey(id)) View.VISIBLE else View.GONE
        }
        b.btnFindPrinters.setOnClickListener { fillPrinters(b) }
        fillPrinters(b)
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    /** Ищет подключённые USB-принтеры и добавляет их в список выбора. */
    private fun fillPrinters(b: DialogPrintBinding) {
        for (i in b.printerGroup.childCount - 1 downTo 0) {
            val child = b.printerGroup.getChildAt(i)
            if (child.id != R.id.printerSystem) b.printerGroup.removeViewAt(i)
        }
        devices.clear()
        b.printerSystem.isChecked = true
        val found = usb.list()
        for (device in found) {
            val radio = RadioButton(activity)
            radio.id = View.generateViewId()
            radio.text = usb.title(device)
            devices[radio.id] = device
            b.printerGroup.addView(radio)
        }
        b.printerHint.text = if (found.isEmpty()) {
            activity.getString(R.string.prn_none_found) + "\n\n" + activity.getString(R.string.prn_hint)
        } else {
            activity.getString(R.string.prn_found, found.size) + "\n\n" + activity.getString(R.string.prn_hint)
        }
    }

    private fun readForm(b: DialogPrintBinding): PrintOptions? {
        val checkedPrinter = b.printerGroup.checkedRadioButtonId
        val device = devices[checkedPrinter]
        if (checkedPrinter != R.id.printerSystem && device == null) {
            host.snack(activity.getString(R.string.prn_usb_missing))
            return null
        }
        val pages = when (b.pagesGroup.checkedRadioButtonId) {
            R.id.pagesCurrent -> PageChoice.CURRENT
            R.id.pagesRange -> PageChoice.RANGE
            else -> PageChoice.ALL
        }
        val orientation = when (b.orientGroup.checkedRadioButtonId) {
            R.id.orientPortrait -> Orientation.PORTRAIT
            R.id.orientLandscape -> Orientation.LANDSCAPE
            else -> Orientation.AUTO
        }
        val duplex = when (b.duplexGroup.checkedRadioButtonId) {
            R.id.duplexBatch -> DuplexMode.BATCH
            R.id.duplexSheet -> DuplexMode.SHEET
            else -> DuplexMode.OFF
        }
        val margins = intArrayOf(0, 200, 400, 800)
        return PrintOptions(
            paper = PaperSize.values()[b.paperSpinner.selectedItemPosition],
            orientation = orientation,
            marginMils = margins[b.marginSpinner.selectedItemPosition.coerceIn(0, margins.size - 1)],
            pages = pages,
            rangeText = b.rangeInput.text.toString(),
            copies = (b.copiesInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99),
            duplex = duplex,
            reverseBacks = duplex == DuplexMode.BATCH && b.reverseBacks.isChecked,
            usbDevice = device,
            dpi = if (b.qualitySpinner.selectedItemPosition == 0) 150 else 300,
            pjl = b.pjlCheck.isChecked,
        )
    }

    // ------------------------------------------------------------------ подготовка и проходы

    private fun begin(o: PrintOptions) {
        val dir = File(activity.cacheDir, "print")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val pdf = host.pdfFile
        if (pdf != null) {
            prepare(pdf, o, dir)
            return
        }
        showBusy(activity.getString(R.string.prn_preparing), false)
        val out = File(dir, "source.pdf")
        try {
            host.renderToPdf(o.attributes(), out) { error ->
                if (error == null) {
                    prepare(out, o, dir)
                } else {
                    hideBusy()
                    offerSystemPrint()
                }
            }
        } catch (t: Throwable) {
            hideBusy()
            offerSystemPrint()
        }
    }

    private fun offerSystemPrint() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.prn_prepare_failed)
            .setPositiveButton(R.string.prn_open_system) { _, _ -> host.systemPrintFallback() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun prepare(src: File, o: PrintOptions, dir: File) {
        showBusy(activity.getString(R.string.prn_preparing), false)
        val current = host.currentPage
        background(
            task = {
                val total = PrintPdf.pageCount(src)
                val pages = when (o.pages) {
                    PageChoice.ALL -> (1..total).toList()
                    PageChoice.CURRENT -> listOf(current.coerceIn(1, total))
                    PageChoice.RANGE -> PrintPlan.parseRange(o.rangeText, total)
                        ?: throw DocException(activity.getString(R.string.prn_err_range, total))
                }
                Job(src, total, o, PrintPlan.passes(pages, o.duplex, o.reverseBacks), dir)
            },
            onDone = { job ->
                hideBusy()
                executePass(job, 0)
            },
        )
    }

    /** Между проходами печать ждёт, пока человек перевернёт бумагу и нажмёт «Продолжить». */
    private fun nextPass(job: Job, index: Int) {
        if (index >= job.passes.size) {
            host.snack(activity.getString(R.string.prn_done))
            return
        }
        val message = when {
            job.options.duplex == DuplexMode.BATCH -> R.string.prn_flip_batch
            index % 2 == 1 -> R.string.prn_flip_back
            else -> R.string.prn_flip_next
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.prn_flip_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.prn_continue) { _, _ -> executePass(job, index) }
            .setNegativeButton(R.string.prn_stop) { _, _ -> host.snack(activity.getString(R.string.prn_cancelled)) }
            .show()
    }

    private fun executePass(job: Job, index: Int) {
        val device = job.options.usbDevice
        if (device == null) {
            printSystem(job, index)
            return
        }
        usb.requestPermission(device) { granted ->
            if (granted) {
                printUsb(job, index, device)
            } else {
                host.snack(activity.getString(R.string.prn_no_permission))
            }
        }
    }

    /** Файл с нужными страницами прохода; если это весь документ без поворотов — берём исходник как есть. */
    private fun passFile(job: Job, index: Int, rotate: RotateMode): File {
        val pages = job.passes[index]
        if (rotate == RotateMode.NONE && pages == (1..job.total).toList()) return job.source
        val out = File(job.dir, "pass-$index.pdf")
        PrintPdf.subset(job.source, pages, rotate, out)
        return out
    }

    private fun printUsb(job: Job, index: Int, device: UsbDevice) {
        val o = job.options
        cancelled = false
        val label = passLabel(job, index)
        showBusy(label, true)
        background(
            task = {
                val file = passFile(job, index, o.rotateMode())
                val copies = if (o.duplex == DuplexMode.OFF) o.copies else 1
                usb.open(device).use { session ->
                    PclPrinter.print(
                        file,
                        o,
                        copies,
                        session::write,
                        { done, all ->
                            activity.runOnUiThread {
                                busyText?.text = activity.getString(R.string.prn_printing, done, all) + label
                            }
                        },
                        { cancelled },
                    )
                }
            },
            onDone = {
                hideBusy()
                nextPass(job, index + 1)
            },
        )
    }

    private fun printSystem(job: Job, index: Int) {
        showBusy(activity.getString(R.string.prn_preparing), false)
        background(
            task = { passFile(job, index, RotateMode.NONE) },
            onDone = { file ->
                hideBusy()
                val name = host.docName + passLabel(job, index)
                val adapter = FilePrintAdapter(file, name, job.passes[index].size)
                val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val printJob = try {
                    manager.print(name, adapter, job.options.attributes())
                } catch (e: Exception) {
                    host.showError(e)
                    null
                }
                if (printJob != null) {
                    watch(printJob) { ok ->
                        if (ok) {
                            nextPass(job, index + 1)
                        } else {
                            host.snack(activity.getString(R.string.prn_job_failed))
                        }
                    }
                }
            },
        )
    }

    private fun passLabel(job: Job, index: Int): String =
        if (job.passes.size > 1) " (${index + 1}/${job.passes.size})" else ""

    /** Системное задание опрашиваем раз в секунду: продолжаем только когда оно успешно завершено. */
    private fun watch(printJob: PrintJob, onFinished: (Boolean) -> Unit) {
        val poll = object : Runnable {
            override fun run() {
                if (activity.isDestroyed) return
                when {
                    printJob.isCompleted -> onFinished(true)
                    printJob.isFailed || printJob.isCancelled -> onFinished(false)
                    else -> handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(poll, 1000)
    }

    // ------------------------------------------------------------------ фон и индикатор

    private fun <T> background(task: () -> T, onDone: (T) -> Unit) {
        executor.execute {
            try {
                val result = task()
                activity.runOnUiThread { onDone(result) }
            } catch (e: Throwable) {
                activity.runOnUiThread {
                    hideBusy()
                    if (e is CancellationException) {
                        host.snack(activity.getString(R.string.prn_cancelled))
                    } else {
                        host.showError(e)
                    }
                }
            }
        }
    }

    private fun showBusy(text: String, cancellable: Boolean) {
        hideBusy()
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(pad, pad, pad, pad / 2)
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.addView(ProgressBar(activity))
        val label = TextView(activity)
        label.text = text
        label.setPadding(pad / 2, 0, 0, 0)
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        busyText = label
        val builder = MaterialAlertDialogBuilder(activity).setView(row).setCancelable(false)
        if (cancellable) builder.setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
        busy = builder.show()
    }

    private fun hideBusy() {
        busy?.dismiss()
        busy = null
        busyText = null
    }
}
