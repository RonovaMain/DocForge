package dev.ronova.docforge

import android.annotation.SuppressLint
import android.graphics.Color
import android.print.WebPdfWriter
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.SeekBar
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    private val printFlow by lazy {
        PrintFlow(
            this,
            object : PrintFlow.Host {
                override val docName: String get() = doc?.let { baseName(it) } ?: "DocForge"
                override val pdfFile: File? get() = doc?.pdf?.file
                override val currentPage: Int get() = curPage

                override fun renderToPdf(attributes: PrintAttributes, out: File, callback: (String?) -> Unit) {
                    val d = doc
                    if (d == null) {
                        callback("no document")
                        return
                    }
                    // в тёмной теме документ инвертирован — для бумаги возвращаем светлый вид
                    binding.web.evaluateJavascript("if(window.setNight){setNight(false)}", null)
                    val adapter = binding.web.createPrintDocumentAdapter(baseName(d))
                    WebPdfWriter.write(
                        adapter,
                        attributes,
                        out,
                        object : WebPdfWriter.Callback {
                            override fun onSuccess() {
                                runOnUiThread {
                                    applySettings()
                                    callback(null)
                                }
                            }

                            override fun onError(message: String?) {
                                runOnUiThread {
                                    applySettings()
                                    callback(message ?: "error")
                                }
                            }
                        },
                    )
                }

                override fun systemPrintFallback() = printDocument()

                override fun showError(error: Throwable) = this@MainActivity.showError(error)

                override fun snack(text: String) = this@MainActivity.snack(text)

                override fun pickFirmware(callback: (Uri?) -> Unit) {
                    firmwareCallback = callback
                    firmwarePicker.launch(arrayOf("*/*"))
                }
            },
        )
    }

    // верхняя панель, скрываемая при прокрутке
    private var barHidden = false
    private var barHeight = 0
    private var barLockUntil = 0L
    private var scrollAccum = 0

    // режим презентации
    private var presenting = false
    private var gesturesOn = true
    private var drawing = false
    private var showPageNum = true
    private var curPage = 1
    private var pendingPresentPage: Int? = null
    private val swatches = ArrayList<View>()
    private var tapValid = false
    private var tapX = 0f
    private var tapY = 0f

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

    private class Export(val bytes: ByteArray, val name: String, val mime: String, val onSaved: (() -> Unit)? = null)

    private var firmwareCallback: ((Uri?) -> Unit)? = null

    private val firmwarePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = firmwareCallback
            firmwareCallback = null
            callback?.invoke(uri)
        }

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
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout(),
            )
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
        setupPresentation()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        presenting -> if (drawing) setDrawing(false) else confirmInk { stopPresentation() }
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
        resetBar()
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
        if (settings.keepScreenOn || presenting) {
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
        menu.findItem(R.id.action_present).isVisible = canPresent() && pageTotal >= 1
        menu.findItem(R.id.action_save_images).isVisible = d != null
        menu.findItem(R.id.action_share).isVisible = d != null
        menu.findItem(R.id.action_print).isVisible = d != null
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
            R.id.action_present -> showPresentDialog()
            R.id.action_save_images -> saveAllImages()
            R.id.action_share -> share()
            R.id.action_print -> printFlow.show()
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
            if (presenting) return@setOnLongClickListener false
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
                // после «Перезаписать исходный файл» документ открыт заново — возвращаемся в режим презентации
                pendingPresentPage?.let { page ->
                    pendingPresentPage = null
                    startPresentation(page)
                }
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
                curPage = current
                binding.pageIndicator.text = "$current / $total"
                binding.drawPage.text = "$current / $total"
                updatePageIndicator()
            }
        }

        /** Режим презентации: страница [index] нарисована в этом прямоугольнике (CSS-пиксели; [viewportWidth] — ширина окна страницы). */
        @JavascriptInterface
        fun presRect(index: Int, x: Double, y: Double, w: Double, h: Double, viewportWidth: Double) {
            runOnUiThread { onPresRect(index, x, y, w, h, viewportWidth) }
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
            val file = File(cacheDir, "doc-${System.nanoTime()}.pdf")
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
        if (presenting) stopPresentation()
        resetBar()
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
        if (presenting) stopPresentation()
        resetBar()
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
        curPage = 1
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
        if (doc == null || presenting) return
        resetBar()
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
        if (presenting && !gesturesOn) resetZoom()
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
    private fun export(bytes: ByteArray, name: String, mime: String, onSaved: (() -> Unit)? = null) {
        val launcher = exportSavers[mime] ?: exportSavers.getValue("application/octet-stream")
        pendingExport = Export(bytes, name, mime, onSaved)
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
            onDone = { name ->
                snack(getString(R.string.saved, name))
                request.onSaved?.invoke()
            },
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

    /** Сначала спрашивает про несохранённые пометки в PDF, затем про несохранённые правки текста. */
    private fun confirmDiscard(proceed: () -> Unit) = confirmInk { confirmEdits(proceed) }

    /** Если есть несохранённые правки — спрашивает, что с ними делать, и только потом выполняет [proceed]. */
    private fun confirmEdits(proceed: () -> Unit) {
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

    // ------------------------------------------------------------------ верхняя панель скрывается при прокрутке

    /** Прокрутка вниз прячет панель вверх, прокрутка вверх возвращает её (как в браузере). Работает и в портрете, и в ландшафте. */
    private fun onWebScroll(y: Int, oldY: Int) {
        if (presenting || binding.searchBar.isVisible) return
        if (SystemClock.uptimeMillis() < barLockUntil) return
        if (y <= 0) {
            scrollAccum = 0
            setBarHidden(false)
            return
        }
        val dy = y - oldY
        if (dy == 0) return
        if ((dy > 0) != (scrollAccum > 0)) scrollAccum = 0
        scrollAccum += dy
        if (scrollAccum > dp(HIDE_DISTANCE_DP) && y > dp(HIDE_DISTANCE_DP)) {
            setBarHidden(true)
        } else if (scrollAccum < -dp(SHOW_DISTANCE_DP)) {
            setBarHidden(false)
        }
    }

    /**
     * Панель и документ едут вверх (или вниз) вместе, а размер WebView меняется один раз — в начале или в конце анимации,
     * поэтому на каждом кадре страница не перерисовывается заново.
     */
    private fun setBarHidden(hidden: Boolean) {
        if (presenting || hidden == barHidden) return
        val bar = binding.topBar
        val area = binding.area
        barLockUntil = SystemClock.uptimeMillis() + BAR_LOCK_MS
        scrollAccum = 0
        bar.animate().cancel()
        area.animate().cancel()
        if (hidden) {
            val h = bar.height
            if (h <= 0) return
            barHeight = h
            barHidden = true
            bar.animate().translationY(-h.toFloat()).setDuration(BAR_ANIM_MS).start()
            area.animate().translationY(-h.toFloat()).setDuration(BAR_ANIM_MS).withEndAction {
                if (barHidden) {
                    bar.visibility = View.GONE
                    bar.translationY = 0f
                    area.translationY = 0f
                }
            }.start()
        } else {
            barHidden = false
            if (bar.visibility != View.VISIBLE) {
                bar.visibility = View.VISIBLE
                bar.translationY = -barHeight.toFloat()
                area.translationY = -barHeight.toFloat()
            }
            bar.animate().translationY(0f).setDuration(BAR_ANIM_MS).start()
            area.animate().translationY(0f).setDuration(BAR_ANIM_MS).start()
        }
    }

    /** Мгновенно возвращает панель на место (новый документ, поворот экрана, выход из презентации). */
    private fun resetBar() {
        binding.topBar.animate().cancel()
        binding.area.animate().cancel()
        binding.topBar.translationY = 0f
        binding.area.translationY = 0f
        binding.topBar.visibility = if (presenting) View.GONE else View.VISIBLE
        barHidden = false
        scrollAccum = 0
        barLockUntil = SystemClock.uptimeMillis() + BAR_LOCK_MS
    }

    // ------------------------------------------------------------------ режим презентации

    /** PDF и презентации (PPTX, ODP) можно показывать по одной странице на весь экран. */
    private fun canPresent(): Boolean {
        val d = doc ?: return false
        return d.format == DocFormat.PDF || d.format.kind == DocFormat.Kind.SLIDES
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPresentation() {
        binding.presMenu.setOnClickListener { showPresMenu() }
        // Касания только отслеживаем (возвращаем false), чтобы WebView по-прежнему мог масштабировать и прокручивать
        binding.web.setOnTouchListener { view, event ->
            trackTap(view, event)
            false
        }
        binding.web.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> onWebScroll(scrollY, oldScrollY) }
        setupDrawBar()
    }

    /** Спрашивает, с какой страницы начать: с начала, с текущей или с любой. */
    private fun showPresentDialog() {
        if (!canPresent()) return
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.goto_hint, pageTotal.coerceAtLeast(1))
        input.setText(curPage.toString())
        input.setSelectAllOnFocus(true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.present_title)
            .setMessage(R.string.present_message)
            .setView(inputContainer(input))
            .setPositiveButton(R.string.present_start) { _, _ ->
                startPresentation(input.text.toString().toIntOrNull() ?: curPage)
            }
            .setNeutralButton(R.string.present_from_start) { _, _ -> startPresentation(1) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPresentation(page: Int) {
        if (!canPresent() || presenting) return
        if (editMode) {
            editMode = false
            applyEditMode()
            invalidateOptionsMenu()
        }
        closeSearch()
        presenting = true
        gesturesOn = true
        drawing = false
        tapValid = false
        binding.ink.clearAll()
        resetBar()
        updatePresUi()
        applyPresentationZoom()
        applyImmersiveMode(true)
        applySettings()
        val first = if (pageTotal > 0) page.coerceIn(1, pageTotal) else page.coerceAtLeast(1)
        binding.web.evaluateJavascript("if(window.presStart){presStart($first)}", null)
    }

    private fun stopPresentation() {
        if (!presenting) return
        presenting = false
        drawing = false
        gesturesOn = true
        tapValid = false
        binding.web.settings.setSupportZoom(true)
        resetZoom()
        binding.ink.clearAll()
        resetBar()
        updatePresUi()
        applyImmersiveMode(false)
        applySettings()
        binding.web.evaluateJavascript("if(window.presStop){presStop()}", null)
    }

    /** Показывает или прячет элементы режима презентации и верхнюю панель. */
    private fun updatePresUi() {
        val p = presenting
        binding.presMenu.isVisible = p
        // Пометки лежат поверх страницы «как она вписана в экран»; при масштабировании они бы разъехались — прячем
        binding.ink.visibility = if (p && gesturesOn) View.VISIBLE else View.GONE
        binding.ink.drawingEnabled = p && drawing
        binding.drawBar.isVisible = p && drawing
        binding.topBar.isVisible = !p
        updatePageIndicator()
    }

    private fun updatePageIndicator() {
        binding.pageIndicator.isVisible = pageTotal > 1 && (!presenting || (showPageNum && !drawing))
    }

    private fun applyImmersiveMode(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, binding.root)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Жесты включены — масштабирование выключено (иначе двойное касание масштабирует вместо листания). */
    private fun applyPresentationZoom() {
        val web = binding.web
        if (presenting && gesturesOn) {
            resetZoom()
            web.settings.setSupportZoom(false)
        } else {
            web.settings.setSupportZoom(true)
        }
    }

    private fun resetZoom() {
        var n = 0
        while (n < 12 && binding.web.zoomOut()) n++
    }

    private fun setGestures(on: Boolean) {
        if (!presenting || on == gesturesOn) return
        gesturesOn = on
        if (!on) drawing = false
        applyPresentationZoom()
        updatePresUi()
        if (!on) snack(getString(R.string.pres_gestures_hint))
    }

    private fun presGo(delta: Int) {
        binding.web.evaluateJavascript("if(window.presGo){presGo($delta)}", null)
    }

    /** Экран делится на три части: слева — назад, справа — вперёд, середина ничего не делает. */
    private fun trackTap(view: View, e: MotionEvent) {
        if (!presenting || !gesturesOn || drawing) {
            tapValid = false
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapValid = true
                tapX = e.x
                tapY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapValid = false
            MotionEvent.ACTION_MOVE -> {
                if (tapValid) {
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (Math.abs(e.x - tapX) > slop || Math.abs(e.y - tapY) > slop) tapValid = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout()
                if (tapValid && quick) onPresTap(e.x, view.width)
                tapValid = false
            }
            MotionEvent.ACTION_CANCEL -> tapValid = false
        }
    }

    private fun onPresTap(x: Float, width: Int) {
        val third = width / 3f
        if (x < third) {
            presGo(-1)
        } else if (x > third * 2f) {
            presGo(1)
        }
    }

    /** Три точки в правом верхнем углу: переход на страницу, жесты, рисование, номер страницы, выход. */
    private fun showPresMenu() {
        val popup = PopupMenu(this, binding.presMenu)
        popup.menuInflater.inflate(R.menu.presentation, popup.menu)
        popup.menu.findItem(R.id.pres_gestures).setTitle(if (gesturesOn) R.string.pres_gestures_off else R.string.pres_gestures_on)
        popup.menu.findItem(R.id.pres_pagenum).isChecked = showPageNum
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.pres_goto -> showGotoDialog()
                R.id.pres_gestures -> setGestures(!gesturesOn)
                R.id.pres_draw -> setDrawing(true)
                R.id.pres_pagenum -> {
                    showPageNum = !showPageNum
                    updatePageIndicator()
                }
                R.id.pres_exit -> confirmInk { stopPresentation() }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    /** Страница нарисована в прямоугольнике (CSS-пиксели страницы) — переводим в пиксели слоя рисования. */
    private fun onPresRect(index: Int, x: Double, y: Double, w: Double, h: Double, viewportWidth: Double) {
        if (!presenting || viewportWidth <= 0.0 || binding.web.width <= 0) return
        val k = binding.web.width / viewportWidth.toFloat()
        val webAt = IntArray(2)
        val inkAt = IntArray(2)
        binding.web.getLocationInWindow(webAt)
        binding.ink.getLocationInWindow(inkAt)
        val left = (webAt[0] - inkAt[0]) + x.toFloat() * k
        val top = (webAt[1] - inkAt[1]) + y.toFloat() * k
        binding.ink.setPage(index, RectF(left, top, left + w.toFloat() * k, top + h.toFloat() * k))
    }

    // ------------------------------------------------------------------ рисование поверх страницы

    private fun setupDrawBar() {
        val size = dp(28)
        val gap = dp(4)
        for (color in INK_COLORS) {
            val swatch = View(this)
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(gap, 0, gap, 0)
            swatch.layoutParams = params
            swatch.setOnClickListener { selectInkColor(color) }
            binding.colorRow.addView(swatch)
            swatches.add(swatch)
        }
        binding.toolPen.setOnClickListener { selectInkTool(InkView.Tool.PEN) }
        binding.toolMarker.setOnClickListener { selectInkTool(InkView.Tool.HIGHLIGHTER) }
        binding.toolEraser.setOnClickListener { selectInkTool(InkView.Tool.ERASER) }
        binding.toolUndo.setOnClickListener { binding.ink.undo() }
        binding.toolClear.setOnClickListener { binding.ink.clearPage() }
        binding.toolSave.setOnClickListener { saveInkToPdf() }
        binding.toolDone.setOnClickListener { setDrawing(false) }
        binding.drawPrev.setOnClickListener { presGo(-1) }
        binding.drawNext.setOnClickListener { presGo(1) }
        binding.ink.onChanged = { refreshDrawUi() }
        binding.drawSize.max = InkView.MAX_LEVEL - 1
        binding.drawSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.ink.setCurrentLevel(progress + 1)
                binding.drawSizeLabel.text = (progress + 1).toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        refreshDrawUi()
    }

    private fun setDrawing(on: Boolean) {
        if (!presenting) return
        if (on && !gesturesOn) {
            gesturesOn = true
            applyPresentationZoom()
        }
        drawing = on
        binding.toolSave.isVisible = doc?.format == DocFormat.PDF
        refreshDrawUi()
        updatePresUi()
    }

    private fun selectInkTool(tool: InkView.Tool) {
        binding.ink.tool = tool
        refreshDrawUi()
    }

    private fun selectInkColor(color: Int) {
        val ink = binding.ink
        if (ink.tool == InkView.Tool.HIGHLIGHTER) {
            ink.markerColor = color
        } else {
            ink.tool = InkView.Tool.PEN
            ink.penColor = color
        }
        refreshDrawUi()
    }

    private fun refreshDrawUi() {
        val ink = binding.ink
        binding.toolPen.isSelected = ink.tool == InkView.Tool.PEN
        binding.toolMarker.isSelected = ink.tool == InkView.Tool.HIGHLIGHTER
        binding.toolEraser.isSelected = ink.tool == InkView.Tool.ERASER
        val erasing = ink.tool == InkView.Tool.ERASER
        val current = if (ink.tool == InkView.Tool.HIGHLIGHTER) ink.markerColor else ink.penColor
        for ((i, swatch) in swatches.withIndex()) {
            val dot = GradientDrawable()
            dot.shape = GradientDrawable.OVAL
            dot.setColor(INK_COLORS[i])
            dot.setStroke(dp(2), if (!erasing && INK_COLORS[i] == current) Color.WHITE else 0x55FFFFFF)
            swatch.background = dot
            swatch.alpha = if (erasing) 0.35f else 1f
        }
        val level = ink.currentLevel()
        binding.drawSize.progress = level - 1
        binding.drawSizeLabel.text = level.toString()
        binding.toolUndo.isEnabled = ink.canUndo()
        binding.toolUndo.alpha = if (ink.canUndo()) 1f else 0.35f
        binding.toolClear.isEnabled = ink.hasInkOnPage()
        binding.toolClear.alpha = if (ink.hasInkOnPage()) 1f else 0.35f
    }

    // ------------------------------------------------------------------ пометки -> PDF

    /** Перед выходом из презентации или закрытием PDF спрашивает, что делать с несохранёнными пометками. */
    private fun confirmInk(proceed: () -> Unit) {
        if (!presenting || doc?.format != DocFormat.PDF || !binding.ink.needsSave()) {
            proceed()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ink_unsaved_title)
            .setMessage(R.string.ink_unsaved_pdf)
            .setPositiveButton(R.string.ink_save_action) { _, _ -> saveInkToPdf() }
            .setNegativeButton(R.string.ink_discard) { _, _ -> proceed() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun saveInkToPdf() {
        val d = doc ?: return
        if (d.tools == null) return
        if (!binding.ink.hasInk()) {
            snack(getString(R.string.ink_empty))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ink_save_title)
            .setItems(arrayOf(getString(R.string.ink_save_copy), getString(R.string.ink_save_overwrite))) { _, which ->
                bakeInk(overwrite = which == 1)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bakeInk(overwrite: Boolean) {
        val d = doc ?: return
        val tools = d.tools ?: return
        val strokes = binding.ink.snapshot()
        work(
            task = { tools.bakeInk(strokes) },
            onDone = { bytes ->
                if (overwrite) {
                    overwritePdf(d, bytes)
                } else {
                    export(bytes, "${baseName(d)}_notes.pdf", "application/pdf") { binding.ink.markSaved() }
                }
            },
        )
    }

    /** Записывает PDF с пометками в исходный файл и открывает его заново — уже с вшитыми пометками. */
    private fun overwritePdf(d: OpenDoc, bytes: ByteArray) {
        work(
            task = { write(d.uri, bytes) },
            onDone = {
                snack(getString(R.string.ink_saved))
                val page = curPage
                stopPresentation()
                pendingPresentPage = page
                openUri(d.uri, fromRecent = false)
            },
            onError = {
                // Исходный файл только для чтения — предлагаем сохранить копию
                snack(getString(R.string.save_fallback))
                export(bytes, "${baseName(d)}_notes.pdf", "application/pdf") { binding.ink.markSaved() }
            },
        )
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
        if (busy && barHidden) setBarHidden(false)
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
            "application/zip", "application/octet-stream", "application/pdf",
        )
        const val BAR_ANIM_MS = 180L
        const val BAR_LOCK_MS = 500L
        const val HIDE_DISTANCE_DP = 24
        const val SHOW_DISTANCE_DP = 10

        /** Цвета ручки и маркера: жёлтый, красный, зелёный, синий, чёрный. */
        val INK_COLORS = intArrayOf(0xFFFFEB3B.toInt(), 0xFFF44336.toInt(), 0xFF4CAF50.toInt(), 0xFF2196F3.toInt(), 0xFF212121.toInt())
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tif", "tiff", "emf", "wmf")
    }
}
