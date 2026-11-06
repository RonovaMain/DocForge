package dev.ronova.docforge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.ronova.docforge.core.DocumentEngines
import dev.ronova.docforge.core.DocumentFormat
import dev.ronova.docforge.core.ExtractedImage
import dev.ronova.docforge.core.ImageData
import dev.ronova.docforge.core.MIME_DOCX
import dev.ronova.docforge.core.MIME_PDF
import dev.ronova.docforge.core.MIME_PPTX
import dev.ronova.docforge.core.MIME_XLSX
import dev.ronova.docforge.core.MIME_ZIP
import dev.ronova.docforge.core.NewDocument
import dev.ronova.docforge.core.ReplaceResult
import dev.ronova.docforge.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val engines = DocumentEngines { assets.open(FONT_ASSET) }

    private var source: SourceFile? = null
    private var pending: PendingSave? = null

    private class SourceFile(val uri: Uri, val name: String, val format: DocumentFormat, val bytes: ByteArray) {
        val baseName: String get() = name.substringBeforeLast('.', name)
    }

    private class PendingSave(val bytes: ByteArray)

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadSource(uri)
        }

    // CreateDocument фиксирует MIME-тип при регистрации, поэтому по одному «сохраняльщику» на тип
    private val savers: Map<String, ActivityResultLauncher<String>> =
        listOf(MIME_DOCX, MIME_XLSX, MIME_PPTX, MIME_PDF, MIME_ZIP).associateWith { mime ->
            registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
                if (uri != null) writePending(uri) else pending = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.btnPick.setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        binding.btnText.setOnClickListener { onReadText() }
        binding.btnInfo.setOnClickListener { onReadInfo() }
        binding.btnImages.setOnClickListener { onExtractImages() }
        binding.btnReplace.setOnClickListener { onReplace() }
        binding.btnWatermark.setOnClickListener { onWatermark() }
        binding.btnCreate.setOnClickListener { onCreateSample() }

        render()
        // После пересоздания активности (поворот, смена темы) открываем тот же файл заново
        savedInstanceState?.getString(KEY_URI)?.let { loadSource(Uri.parse(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        source?.let { outState.putString(KEY_URI, it.uri.toString()) }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ действия

    private fun onReadText() {
        val src = requireSource() ?: return
        work({ engines.forFormat(src.format).readText(src.bytes) }) { text ->
            showOutput(text.ifBlank { getString(R.string.msg_empty_text) })
        }
    }

    private fun onReadInfo() {
        val src = requireSource() ?: return
        work({ engines.forFormat(src.format).readMetadata(src.bytes).describe() }) { showOutput(it) }
    }

    private fun onExtractImages() {
        val src = requireSource() ?: return
        work({
            val images = engines.forFormat(src.format).extractImages(src.bytes, src.baseName)
            images to (if (images.isEmpty()) null else zip(images))
        }) { (images, archive) ->
            if (archive == null) {
                showOutput(getString(R.string.msg_no_images))
            } else {
                showOutput(
                    getString(R.string.msg_images_found, images.size) + "\n" +
                        images.joinToString("\n") { it.describe() }
                )
                startSave(archive, "${src.baseName}_images.zip", MIME_ZIP)
            }
        }
    }

    private fun onReplace() {
        val src = requireSource() ?: return
        if (src.format == DocumentFormat.PDF) {
            showError(UnsupportedOperationException("Замена текста не поддерживается для PDF — используйте водяной знак"))
            return
        }
        val replacements = try {
            parseReplacements(binding.etReplace.text?.toString().orEmpty())
        } catch (e: IllegalArgumentException) {
            showError(e)
            return
        }
        work({ engines.textReplacer(src.format).replaceText(src.bytes, replacements) }) { result: ReplaceResult ->
            if (result.count == 0) {
                showOutput(getString(R.string.msg_nothing_replaced))
            } else {
                showOutput(getString(R.string.msg_replaced, result.count))
                startSave(result.bytes, "${src.baseName}_edited.${src.format.extension}", src.format.mime)
            }
        }
    }

    private fun onWatermark() {
        val src = requireSource() ?: return
        if (src.format != DocumentFormat.PDF) {
            showError(UnsupportedOperationException("Водяной знак поддерживается только для PDF"))
            return
        }
        val text = binding.etWatermark.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            showError(IllegalArgumentException(getString(R.string.err_empty_watermark)))
            return
        }
        work({ engines.pdf.addWatermark(src.bytes, text) }) { bytes ->
            startSave(bytes, "${src.baseName}_watermark.pdf", MIME_PDF)
        }
    }

    private fun onCreateSample() {
        val formats = DocumentFormat.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_sample_title)
            .setItems(formats.map { it.title + " (." + it.extension + ")" }.toTypedArray()) { _, which ->
                val format = formats[which]
                work({ engines.creator(format).create(sampleDocument()) }) { bytes ->
                    startSave(bytes, "sample.${format.extension}", format.mime)
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ файлы

    private fun loadSource(uri: Uri) {
        work({
            val name = displayName(uri) ?: "document"
            val format = DocumentFormat.fromFileName(name)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(getString(R.string.err_cannot_open))
            SourceFile(uri, name, format, bytes)
        }) { loaded ->
            source = loaded
            render()
            showOutput(getString(R.string.output_placeholder))
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /** Запоминает результат и открывает системный диалог «Сохранить как». Запись — в [writePending]. */
    private fun startSave(bytes: ByteArray, fileName: String, mime: String) {
        pending = PendingSave(bytes)
        savers.getValue(mime).launch(fileName)
    }

    private fun writePending(uri: Uri) {
        val save = pending ?: return
        pending = null
        work({
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error(getString(R.string.err_cannot_save))
            stream.use { it.write(save.bytes) }
            displayName(uri) ?: uri.toString()
        }) { name ->
            appendOutput("\n" + getString(R.string.msg_saved, name))
        }
    }

    private fun zip(images: List<ExtractedImage>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (image in images) {
                zip.putNextEntry(ZipEntry(image.name))
                zip.write(image.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Строки вида `ключ=значение`; пустые строки пропускаются. */
    private fun parseReplacements(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (rawLine in input.lines()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            require('=' in line && !line.startsWith("=")) { getString(R.string.err_bad_replacement, line) }
            result[line.substringBefore('=')] = line.substringAfter('=')
        }
        require(result.isNotEmpty()) { getString(R.string.err_empty_replacements) }
        return result
    }

    private fun sampleDocument(): NewDocument = NewDocument(
        title = "Отчёт за {{month}}",
        paragraphs = listOf(
            "Клиент: {{client}}",
            "Сумма договора: {{amount}} руб.",
            "Документ создан приложением DocForge.",
        ),
        table = listOf(
            listOf("Позиция", "Кол-во", "Цена"),
            listOf("Лицензия", "2", "1000"),
            listOf("Поддержка", "1", "500"),
        ),
        images = listOf(ImageData("logo.png", sampleLogo())),
    )

    /** PNG-градиент 240x120 в цветах приложения. */
    private fun sampleLogo(): ByteArray {
        val bitmap = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 240f, 120f, 0xFF8EC5FF.toInt(), 0xFFF58BB8.toInt(), Shader.TileMode.CLAMP)
        Canvas(bitmap).drawRect(0f, 0f, 240f, 120f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ UI

    /** Выполняет [task] в фоне и отдаёт результат в [onDone] на главном потоке; ошибки показывает в окне результата. */
    private fun <T> work(task: () -> T, onDone: (T) -> Unit) {
        setBusy(true)
        worker.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                result.fold(onSuccess = onDone, onFailure = { showError(it) })
            }
        }
    }

    private fun requireSource(): SourceFile? {
        val current = source
        if (current == null) showError(IllegalStateException(getString(R.string.err_no_file)))
        return current
    }

    private fun render() {
        val src = source
        binding.tvFile.text = if (src == null) {
            getString(R.string.no_file)
        } else {
            getString(R.string.file_info, src.name, src.format.title, Formatter.formatShortFileSize(this, src.bytes.size.toLong()))
        }
        val hasFile = src != null
        binding.btnText.isEnabled = hasFile
        binding.btnInfo.isEnabled = hasFile
        binding.btnImages.isEnabled = hasFile
        binding.btnReplace.isEnabled = hasFile && src?.format != DocumentFormat.PDF
        binding.btnWatermark.isEnabled = src?.format == DocumentFormat.PDF
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun showOutput(text: String) {
        binding.tvOutput.text = if (text.length > MAX_SHOWN_CHARS) {
            text.substring(0, MAX_SHOWN_CHARS) + getString(R.string.msg_truncated, MAX_SHOWN_CHARS, text.length)
        } else {
            text
        }
    }

    private fun appendOutput(text: String) {
        binding.tvOutput.append(text)
    }

    private fun showError(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        binding.tvOutput.text = getString(R.string.error_prefix, message)
    }

    private companion object {
        const val FONT_ASSET = "fonts/DejaVuSans.ttf"
        const val KEY_URI = "source_uri"
        const val MAX_SHOWN_CHARS = 100_000
    }
}
