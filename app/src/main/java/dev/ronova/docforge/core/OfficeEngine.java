package dev.ronova.docforge.core;

/**
 * Движок формата: превращает документ в HTML для WebView и умеет точечно менять текст.
 * Все методы работают с байтами файла и ничего не хранят — потокобезопасны.
 *
 * Каждый редактируемый элемент (абзац, ячейка, текст слайда) получает в HTML атрибут data-id;
 * по этому id движок находит тот же элемент при правке.
 */
public interface OfficeEngine {

    DocFormat format();

    /** Полная HTML-страница. Картинки ссылаются на {@link dev.ronova.docforge.html.Html#MEDIA_PREFIX}. */
    String renderHtml(byte[] source);

    /** Текст элемента для окна правки; null, если элемента с таким id нет. */
    String textAt(byte[] source, String id);

    /** Новый файл с изменённым текстом элемента. Исходный массив не меняется. */
    byte[] editText(byte[] source, String id, String newText);

    /** Что показать в элементе после правки (для ячеек — отформатированное значение). */
    String displayAt(byte[] source, String id);
}
