package dev.ronova.docforge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

    // нумерация страниц (PDF и слайды) и поиск
    private var pageTotal = 0
    private var pdfHits: List<PdfTools.Hit> = emptyList()
    private var pdfHitIndex = -1
    private var pendingExport: Export? = null

    private class OpenDoc(
        var uri: Uri,
        var name: String,
        val format: DocFormat,
        @Volatile var bytes: ByteArray,
        val media: ZipPackage?,
        val pdf: PdfSource?,
        val tools: PdfTools?,
    ) {
        @Volatile
        var dirty = false
    }

    private class Loaded(val doc: OpenDoc, val html: String)

    private class Export(val bytes: ByteArray, val name: String, val mime: String)

    private val openPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistPermission(uri)
                openUri(uri, fromRecent = false)
            }
        }

    // CreateDocument фиксирует MIME-тип при регистрации, поэтому по одному «сохраняльщику» на тип
    private val savers: Map<String, ActivityResultLauncher<String>> =
        DocFormat.values().filter { it.isEditable }.associate { format ->
            format.mime to registerForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
                if (uri != null) saveAs(uri)
            }
        }

    private val exportSavers: Map<String, ActivityResultLauncher<String>> =
        EXPORT_MIMES.associateWith { mime ->
            registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> onExportTarget(uri) }
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
        binding.pageIndicator.setOnClickListener { showGotoDialog() }
        setupSearch()
        configureWebView()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.searchBar.isVisible -> closeSearch()
                        doc != null -> confirmDiscard { closeDocument() }
                        else -> finish()
                    }
                }
            },
        )

        renderRecents()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applySettings()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySettings()
    }

    override fun onDestroy() {
        worker.shutdown()
        doc?.pdf?.close()
        binding.web.destroy()
        super.onDestroy()
    }

    /** Настройки из экрана «Настройки»: тема документа, размер текста, «не гасить экран». */
    private fun applySettings() {
        val settings = AppSettings(this)
        val kind = doc?.format?.kind
        binding.web.settings.textZoom =
            if (kind == DocFormat.Kind.TEXT || kind == DocFormat.Kind.SHEET) settings.textZoom else 100
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding.web.evaluateJavascript("if(window.setNight){setNight(${settings.isNight()})}", null)
    }

    // ------------------------------------------------------------------ меню

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val d = doc
        val editable = d?.format?.isEditable == true
        menu.findItem(R.id.action_search).isVisible = d != null
        menu.findItem(R.id.action_edit).apply {
            isVisible = editable
            isChecked = editMode
        }
        menu.findItem(R.id.action_save).isVisible = editable
        menu.findItem(R.id.action_save_as).isVisible = editable
        menu.findItem(R.id.action_goto).isVisible = pageTotal > 1
        menu.findItem(R.id.action_save_images).isVisible = d != null
        menu.findItem(R.id.action_share).isVisible = d != null
        menu.findItem(R.id.action_print).isVisible = d != null && d.format != DocFormat.PDF
        menu.findItem(R.id.action_close).isVisible = d != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_open -> pickFile()
            R.id.action_search -> openSearch()
            R.id.action_edit -> {
                editMode = !editMode
                item.isChecked = editMode
                applyEditMode()
            }
            R.id.action_save -> save()
            R.id.action_save_as -> doc?.let { savers[it.format.mime]?.launch(it.name) }
            R.id.action_goto -> showGotoDialog()
            R.id.action_save_images -> saveAllImages()
            R.id.action_share -> share()
            R.id.action_print -> printDocument()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
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
            javaScriptEnabled = true // нужен только нашему скрипту страницы (правка, вкладки, масштаб слайдов, номера страниц)
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
        web.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            if (doc?.format != DocFormat.PDF && binding.searchBar.isVisible) {
                binding.searchCount.text = if (numberOfMatches == 0) {
                    getString(R.string.search_none)
                } else {
                    getString(R.string.search_count, activeMatchOrdinal + 1, numberOfMatches)
                }
            }
        }
        // Долгое нажатие на картинку документа или страницу PDF — меню «Сохранить»
        web.setOnLongClickListener { view ->
            val hit = (view as WebView).hitTestResult
            val extra = hit.extra
            if (extra != null &&
                (hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
            ) {
                onImageLongPress(extra)
                true
            } else {
                false
            }
        }
        web.webViewClient = object : WebViewClient() {
            // Ссылки внутри документа не открываем: страница — это только отображение файла
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // Сам документ (loadDataWithBaseURL) WebView тоже «запрашивает» по адресу базы — его пропускаем,
            // иначе страница не откроется (ERR_HTTP_RESPONSE_CODE_FAILURE). Блокируем только подресурсы.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.isForMainFrame) null else serve(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript("if(window.setEditing){setEditing($editMode)}", null)
                applySettings()
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

    /** Мост «страница -> приложение»: правка текста и номер текущей страницы. */
    inner class Bridge {
        @JavascriptInterface
        fun edit(id: String) {
            runOnUiThread { requestEdit(id) }
        }

        @JavascriptInterface
        fun page(current: Int, total: Int) {
            runOnUiThread {
                if (pageTotal != total) {
                    pageTotal = total
                    invalidateOptionsMenu()
                }
                binding.pageIndicator.text = "$current / $total"
                binding.pageIndicator.isVisible = total > 1
            }
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
            return Loaded(OpenDoc(uri, name, format, ByteArray(0), null, pdf, PdfTools(file)), html)
        }
        val html = Engines.forFormat(format).renderHtml(bytes)
        return Loaded(OpenDoc(uri, name, format, bytes, ZipPackage.read(bytes), null, null), html)
    }

    private fun show(loaded: Loaded) {
        val old = doc
        doc = loaded.doc
        old?.pdf?.close()
        editMode = false
        resetNavigation()
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
        resetNavigation()
        binding.web.loadUrl("about:blank")
        binding.web.isVisible = false
        binding.start.isVisible = true
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = null
        invalidateOptionsMenu()
    }

    /** Сбрасывает поиск и нумерацию страниц при смене документа. */
    private fun resetNavigation() {
        closeSearch()
        pageTotal = 0
        binding.pageIndicator.isVisible = false
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

    // ------------------------------------------------------------------ поиск

    private fun setupSearch() {
        binding.searchInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty().trim()
            if (doc?.format == DocFormat.PDF) {
                if (query.isEmpty()) {
                    pdfHits = emptyList()
                    pdfHitIndex = -1
                    binding.searchCount.text = ""
                }
            } else if (query.isEmpty()) {
                binding.web.clearMatches()
                binding.searchCount.text = ""
            } else {
                binding.web.findAllAsync(query)
            }
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        binding.btnSearchNext.setOnClickListener { searchStep(true) }
        binding.btnSearchPrev.setOnClickListener { searchStep(false) }
        binding.btnSearchClose.setOnClickListener { closeSearch() }
        binding.searchCount.setOnClickListener { if (pdfHits.isNotEmpty()) showHitList() }
    }

    private fun openSearch() {
        if (doc == null) return
        binding.searchBar.isVisible = true
        binding.searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        if (binding.searchBar.isVisible) hideKeyboard()
        binding.searchBar.isVisible = false
        binding.searchInput.setText("")
        binding.searchCount.text = ""
        binding.web.clearMatches()
        pdfHits = emptyList()
        pdfHitIndex = -1
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun submitSearch() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()
        val d = doc ?: return
        val tools = d.tools
        if (tools == null) {
            binding.web.findAllAsync(query)
            return
        }
        // У PDF нет HTML-текста: ищем в извлечённом тексте страниц и переходим на страницу с совпадением
        work(
            task = { if (tools.hasText()) tools.search(query) else null },
            onDone = { hits ->
                if (hits == null) {
                    snack(getString(R.string.search_no_text))
                } else {
                    pdfHits = hits
                    if (hits.isEmpty()) {
                        pdfHitIndex = -1
                        binding.searchCount.text = getString(R.string.search_none)
                    } else {
                        pdfHitIndex = 0
                        showHit()
                    }
                }
            },
        )
    }

    private fun showHit() {
        val hit = pdfHits.getOrNull(pdfHitIndex) ?: return
        binding.searchCount.text = getString(R.string.search_count_pdf, pdfHitIndex + 1, pdfHits.size, hit.page)
        gotoPage(hit.page)
    }

    private fun searchStep(forward: Boolean) {
        if (doc?.format == DocFormat.PDF) {
            if (pdfHits.isEmpty()) return
            pdfHitIndex = (pdfHitIndex + (if (forward) 1 else pdfHits.size - 1)) % pdfHits.size
            showHit()
        } else {
            binding.web.findNext(forward)
        }
    }

    private fun showHitList() {
        val items = pdfHits.map { getString(R.string.search_hit_item, it.page, it.snippet) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.search_hits_title, pdfHits.size))
            .setItems(items) { _, which ->
                pdfHitIndex = which
                showHit()
            }
            .show()
    }

    // ------------------------------------------------------------------ страницы

    private fun gotoPage(page: Int) {
        binding.web.evaluateJavascript("if(window.gotoPage){gotoPage($page)}", null)
    }

    private fun showGotoDialog() {
        if (pageTotal <= 1) return
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.goto_hint, pageTotal)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.goto_title)
            .setView(inputContainer(input))
            .setPositiveButton(R.string.goto_go) { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page != null) gotoPage(page.coerceIn(1, pageTotal))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun inputContainer(input: EditText): FrameLayout {
        val container = FrameLayout(this)
        container.setPadding(dp(20), dp(8), dp(20), 0)
        container.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return container
    }

    // ------------------------------------------------------------------ картинки

    private fun onImageLongPress(url: String) {
        val d = doc ?: return
        val uri = Uri.parse(url)
        if (uri.host != "docforge.local") return
        val path = uri.path.orEmpty()
        if (path.startsWith("/m/")) {
            val name = path.removePrefix("/m/")
            val bytes = d.media?.get(name) ?: return
            val fileName = name.substringAfterLast('/')
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.image_actions_title)
                .setItems(arrayOf(getString(R.string.image_save))) { _, _ ->
                    export(bytes, fileName, imageMimeFor(fileName))
                }
                .show()
        } else if (path.startsWith("/pdf/")) {
            val index = path.removePrefix("/pdf/").toIntOrNull() ?: return
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.image_actions_title))
                .setItems(arrayOf(getString(R.string.pdf_page_save), getString(R.string.pdf_page_images))) { _, which ->
                    if (which == 0) savePdfPage(d, index) else savePdfPageImages(d, index)
                }
                .show()
        }
    }

    private fun savePdfPage(d: OpenDoc, index: Int) {
        val pdf = d.pdf ?: return
        work(
            task = { pdf.renderJpeg(index, 2400) ?: throw DocException(getString(R.string.err_open)) },
            onDone = { bytes -> export(bytes, "${baseName(d)}_page${index + 1}.jpg", "image/jpeg") },
        )
    }

    private fun savePdfPageImages(d: OpenDoc, index: Int) {
        val tools = d.tools ?: return
        work(
            task = { tools.imagesOnPage(index) },
            onDone = { images -> deliverImages(images, "${baseName(d)}_page${index + 1}_images.zip") },
        )
    }

    /** Все картинки документа: из папок media (Office, ODF) или встроенные в PDF — одним архивом. */
    private fun saveAllImages() {
        val d = doc ?: return
        work(
            task = {
                val tools = d.tools
                val media = d.media
                if (tools != null) {
                    tools.allImages()
                } else if (media != null) {
                    val list = ArrayList<NamedImage>()
                    for (part in media.names().filter { isMediaImage(it) }.sorted()) {
                        val bytes = media.get(part)
                        if (bytes != null) list.add(NamedImage(part.substringAfterLast('/'), bytes))
                    }
                    list
                } else {
                    emptyList<NamedImage>()
                }
            },
            onDone = { images -> deliverImages(images, "${baseName(d)}_images.zip") },
        )
    }

    private fun deliverImages(images: List<NamedImage>, zipName: String) {
        when {
            images.isEmpty() -> snack(getString(R.string.no_images))
            images.size == 1 -> export(images[0].bytes, images[0].name, imageMimeFor(images[0].name))
            else -> work(task = { zip(images) }, onDone = { bytes -> export(bytes, zipName, "application/zip") })
        }
    }

    private fun isMediaImage(path: String): Boolean {
        val lower = path.lowercase()
        val inMedia = lower.contains("/media/") || lower.startsWith("pictures/")
        return inMedia && lower.substringAfterLast('.', "") in IMAGE_EXTENSIONS
    }

    private fun imageMimeFor(name: String): String = Html.imageMime(name) ?: "application/octet-stream"

    private fun baseName(d: OpenDoc): String = d.name.substringBeforeLast('.', d.name)

    private fun zip(images: List<NamedImage>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val used = HashSet<String>()
            for (image in images) {
                var name = image.name
                var n = 1
                while (!used.add(name)) {
                    n++
                    name = "${n}_${image.name}"
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(image.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Открывает системный диалог «Сохранить как»; запись — в [onExportTarget]. */
    private fun export(bytes: ByteArray, name: String, mime: String) {
        val launcher = exportSavers[mime] ?: exportSavers.getValue("application/octet-stream")
        pendingExport = Export(bytes, name, mime)
        launcher.launch(name)
    }

    private fun onExportTarget(uri: Uri?) {
        val request = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        work(
            task = {
                write(uri, request.bytes)
                displayName(uri) ?: request.name
            },
            onDone = { name -> snack(getString(R.string.saved, name)) },
        )
    }

    // ------------------------------------------------------------------ поделиться и печать

    private fun share() {
        val d = doc ?: return
        work(
            task = {
                val dir = File(cacheDir, "share")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val safe = d.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifEmpty { "document" }
                val file = File(dir, safe)
                val pdf = d.pdf
                if (pdf != null) pdf.file.copyTo(file, overwrite = true) else file.writeBytes(d.bytes)
                file
            },
            onDone = { file ->
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND)
                send.type = d.format.mime
                send.putExtra(Intent.EXTRA_STREAM, uri)
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
            },
        )
    }

    /** Печать и «Сохранить как PDF» средствами Android: выводится то, что сейчас на странице. */
    private fun printDocument() {
        val d = doc ?: return
        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(d.name, binding.web.createPrintDocumentAdapter(d.name), PrintAttributes.Builder().build())
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_dialog_title)
            .setView(inputContainer(input))
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

    private companion object {
        val EXPORT_MIMES = listOf(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/svg+xml",
            "application/zip", "application/octet-stream",
        )
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tif", "tiff", "emf", "wmf")
    }
}
