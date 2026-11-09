package dev.ronova.docforge

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class DocForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: цвета из обоев на Android 12+, на старых версиях — палитра из themes.xml
        DynamicColors.applyToActivitiesIfAvailable(this)
        // pdfbox-android грузит таблицы шрифтов из ресурсов — без этого извлечение текста падает
        PDFBoxResourceLoader.init(applicationContext)
    }
}
