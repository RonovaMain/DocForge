package dev.ronova.docforge

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.ronova.docforge.core.DocException
import dev.ronova.docforge.core.DocFormat
import dev.ronova.docforge.core.Engines
import dev.ronova.docforge.databinding.ActivityMainBinding
import dev.ronova.docforge.html.Html
import dev.ronova.docforge.zip.ZipPackage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Просмотрщик и редактор документов. Документ превращается в HTML (движки в пакетах core/ooxml/odf),
 * который показывает WebView; PDF рисуется встроенным PdfRenderer и отдаётся в тот же WebView по страницам.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recents: RecentFiles
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var doc: OpenDoc? = null
    private var editMode = false

    private class OpenDoc(
        var uri: Uri,
        var name: String,
        val format: DocFormat,
        @Volatile var bytes: ByteArray,
        val media: ZipPackage?,
        val pdf: PdfSource?,
    ) {
        @Volatile
        var dirty = false
    }

    private class Loaded(val doc: OpenDoc, val html: String)

    private val openPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistPermission(uri)
                openUri(uri, fromRecent = false)
            }
        }

    // CreateDocument фиксирует MIME-тип при регистрации, поэтому по одному «сохраняльщику» на формат
    private val savers: Map<String, ActivityResultLauncher<String>> =
        DocFormat.values().filter { it.isEditable }.associate { format ->
            format.mime to registerForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
                if (uri != null) saveAs(uri)
            }
        }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        recents = RecentFiles(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.btnOpen.setOnClickListener { pickFile() }
        binding.btnClearRecent.setOnClickListener {
            recents.clear()
            renderRecents()
        }
        configureWebView()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (doc != null) confirmDiscard { closeDocument() } else finish()
                }
            },
        )

        renderRecents()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onDestroy() {
        worker.shutdown()
        doc?.pdf?.close()
        binding.web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ меню

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val editable = doc?.format?.isEditable == true
        menu.findItem(R.id.action_edit).apply {
            isVisible = editable
            isChecked = editMode
        }
        menu.findItem(R.id.action_save).isVisible = editable
        menu.findItem(R.id.action_save_as).isVisible = editable
        menu.findItem(R.id.action_close).isVisible = doc != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_open -> pickFile()
            R.id.action_edit -> {
                editMode = !editMode
                item.isChecked = editMode
                applyEditMode()
            }
            R.id.action_save -> save()
            R.id.action_save_as -> doc?.let { savers[it.format.mime]?.launch(it.name) }
            R.id.action_close -> confirmDiscard { closeDocument() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun applyEditMode() {
        binding.web.evaluateJavascript("if(window.setEditing){setEditing($editMode)}", null)
        supportActionBar?.subtitle = if (editMode) getString(R.string.edit_mode_on) else null
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val web = binding.web
        web.settings.apply {
            javaScriptEnabled = true // нужен только нашему скрипту страницы (правка, вкладки, масштаб слайдов)
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
        }
        web.setBackgroundColor(0xFFE6E7EB.toInt())
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            // Ссылки внутри документа не открываем: страница — это только отображение файла
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript("if(window.setEditing){setEditing($editMode)}", null)
            }
        }
    }

    /** Картинки документа и страницы PDF отдаются WebView напрямую из памяти. Всё остальное блокируется. */
    private fun serve(url: Uri): WebResourceResponse? {
        if (url.host != "docforge.local") return blocked()
        val d = doc ?: return blocked()
        val path = url.path.orEmpty()
        if (path.startsWith("/m/")) {
            val name = path.removePrefix("/m/")
            val bytes = d.media?.get(name)
            val mime = Html.imageMime(name)
            return if (bytes != null && mime != null) WebResourceResponse(mime, null, ByteArrayInputStream(bytes)) else blocked()
        }
        if (path.startsWith("/pdf/")) {
            val index = path.removePrefix("/pdf/").toIntOrNull() ?: return blocked()
            val width = (resources.displayMetrics.widthPixels * 2).coerceIn(1080, 2200)
            val jpeg = try {
                d.pdf?.renderJpeg(index, width)
            } catch (e: Exception) {
                null
            }
            return if (jpeg != null) WebResourceResponse("image/jpeg", null, ByteArrayInputStream(jpeg)) else blocked()
        }
        return blocked()
    }

    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Мост «страница -> приложение»: нажатие на редактируемый элемент в режиме правки. */
    inner class Bridge {
        @JavascriptInterface
        fun edit(id: String) {
            runOnUiThread { requestEdit(id) }
        }
    }

    // ------------------------------------------------------------------ открытие

    private fun pickFile() = confirmDiscard { openPicker.launch(arrayOf("*/*")) }

    private fun handleIntent(intent: Intent?) {
        val i = intent ?: return
        val uri = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> streamExtra(i)
            else -> null
        } ?: return
        openUri(uri, fromRecent = false)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun persistPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
                // разрешение действует, пока приложение запущено — этого достаточно
            }
        }
    }

    private fun openUri(uri: Uri, fromRecent: Boolean) {
        work(
            task = { load(uri) },
            onDone = { loaded -> show(loaded) },
            onError = { e ->
                if (fromRecent) {
                    recents.remove(uri)
                    renderRecents()
                    snack(getString(R.string.recent_unavailable))
                } else {
                    showError(e)
                }
            },
        )
    }

    private fun load(uri: Uri): Loaded {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "document"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw DocException(getString(R.string.err_open))
        val format = DocFormat.detect(name, bytes)
        if (format == DocFormat.PDF) {
            val file = File(cacheDir, "current.pdf")
            file.writeBytes(bytes)
            val pdf = try {
                PdfSource(file)
            } catch (e: Exception) {
                file.delete()
                throw DocException(getString(R.string.err_pdf), e)
            }
            val html = Html.pdfPages(pdf.pageSizes())
            return Loaded(OpenDoc(uri, name, format, ByteArray(0), null, pdf), html)
        }
        val html = Engines.forFormat(format).renderHtml(bytes)
        return Loaded(OpenDoc(uri, name, format, bytes, ZipPackage.read(bytes), null), html)
    }

    private fun show(loaded: Loaded) {
        val old = doc
        doc = loaded.doc
        old?.pdf?.close()
        editMode = false
        binding.web.loadDataWithBaseURL(Html.BASE_URL, loaded.html, "text/html", "UTF-8", null)
        binding.start.isVisible = false
        binding.web.isVisible = true
        supportActionBar?.title = loaded.doc.name
        supportActionBar?.subtitle = null
        recents.add(loaded.doc.uri, loaded.doc.name)
        renderRecents()
        invalidateOptionsMenu()
    }

    private fun closeDocument() {
        val old = doc
        doc = null
        old?.pdf?.close()
        editMode = false
        binding.web.loadUrl("about:blank")
        binding.web.isVisible = false
        binding.start.isVisible = true
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = null
        invalidateOptionsMenu()
    }

    private fun displayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }

    private fun renderRecents() {
        val items = recents.list()
        binding.recentList.removeAllViews()
        binding.recentTitle.isVisible = items.isNotEmpty()
        binding.btnClearRecent.isVisible = items.isNotEmpty()
        for (entry in items) {
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            button.text = entry.name
            button.isAllCaps = false
            button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(6)
            button.layoutParams = params
            button.setOnClickListener { confirmDiscard { openUri(entry.uri, fromRecent = true) } }
            binding.recentList.addView(button)
        }
    }

    // ------------------------------------------------------------------ правка

    private fun requestEdit(id: String) {
        val d = doc ?: return
        if (!d.format.isEditable) return
        work(
            task = { Engines.forFormat(d.format).textAt(d.bytes, id) },
            onDone = { text -> if (text == null) snack(getString(R.string.err_not_found)) else showEditDialog(id, text) },
        )
    }

    private fun showEditDialog(id: String, current: String) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.setText(current)
        input.setSelection(current.length)
        input.maxLines = 10
        val container = FrameLayout(this)
        container.setPadding(dp(20), dp(8), dp(20), 0)
        container.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.apply) { _, _ -> applyEdit(id, input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyEdit(id: String, text: String) {
        val d = doc ?: return
        work(
            task = {
                val engine = Engines.forFormat(d.format)
                val updated = engine.editText(d.bytes, id, text)
                updated to (engine.displayAt(updated, id) ?: text)
            },
            onDone = { (updated, shown) ->
                d.bytes = updated
                d.dirty = true
                // Обновляем текст прямо на странице: перерисовка сбросила бы прокрутку и масштаб
                binding.web.evaluateJavascript("if(window.setText){setText(${JSONObject.quote(id)},${JSONObject.quote(shown)})}", null)
            },
        )
    }

    // ------------------------------------------------------------------ сохранение

    private fun write(uri: Uri, bytes: ByteArray) {
        val stream = contentResolver.openOutputStream(uri, "wt") ?: throw DocException(getString(R.string.err_save))
        stream.use { it.write(bytes) }
    }

    private fun save() {
        val d = doc ?: return
        val bytes = d.bytes
        work(
            task = { write(d.uri, bytes) },
            onDone = {
                d.dirty = false
                snack(getString(R.string.saved, d.name))
            },
            onError = {
                // Исходный файл часто открыт только для чтения (например, из почты) — предлагаем сохранить копию
                snack(getString(R.string.save_fallback))
                savers[d.format.mime]?.launch(d.name)
            },
        )
    }

    private fun saveAs(uri: Uri) {
        val d = doc ?: return
        val bytes = d.bytes
        work(
            task = {
                write(uri, bytes)
                displayName(uri) ?: d.name
            },
            onDone = { name ->
                persistPermission(uri)
                d.uri = uri
                d.name = name
                d.dirty = false
                supportActionBar?.title = name
                recents.add(uri, name)
                renderRecents()
                snack(getString(R.string.saved, name))
            },
        )
    }

    /** Если есть несохранённые правки — спрашивает, что с ними делать, и только потом выполняет [proceed]. */
    private fun confirmDiscard(proceed: () -> Unit) {
        val d = doc
        if (d == null || !d.dirty) {
            proceed()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(getString(R.string.unsaved_message, d.name))
            .setPositiveButton(R.string.save) { _, _ ->
                val bytes = d.bytes
                work(
                    task = { write(d.uri, bytes) },
                    onDone = {
                        d.dirty = false
                        proceed()
                    },
                    onError = { savers[d.format.mime]?.launch(d.name) },
                )
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                d.dirty = false
                proceed()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Выполняет [task] в фоне; результат или ошибка приходят на главном потоке. */
    private fun <T> work(task: () -> T, onDone: (T) -> Unit, onError: (Throwable) -> Unit = { showError(it) }) {
        setBusy(true)
        worker.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                result.fold(onDone, onError)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    private fun showError(error: Throwable) {
        val message = when (error) {
            is OutOfMemoryError -> getString(R.string.err_too_big)
            else -> error.message ?: error.javaClass.simpleName
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun snack(text: String) = Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
