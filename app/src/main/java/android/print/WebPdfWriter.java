package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Превращает печатный адаптер WebView (то, что показывает документ) в PDF-файл — без диалога печати.
 * Лежит в пакете android.print, потому что конструкторы LayoutResultCallback и WriteResultCallback в Android скрыты.
 * Если система такой доступ не разрешит, вызывающий получит onError и откроет обычный диалог печати.
 */
public final class WebPdfWriter {

    public interface Callback {
        void onSuccess();

        void onError(String message);
    }

    private WebPdfWriter() {
    }

    public static void write(final PrintDocumentAdapter adapter, final PrintAttributes attributes, final File out, final Callback callback) {
        try {
            adapter.onStart();
            adapter.onLayout(null, attributes, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
                @Override
                public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                    writePages(adapter, out, callback);
                }

                @Override
                public void onLayoutFailed(CharSequence error) {
                    callback.onError(error == null ? "layout failed" : error.toString());
                }

                @Override
                public void onLayoutCancelled() {
                    callback.onError("layout cancelled");
                }
            }, null);
        } catch (Throwable t) {
            callback.onError(String.valueOf(t.getMessage()));
        }
    }

    private static void writePages(final PrintDocumentAdapter adapter, File out, final Callback callback) {
        try {
            if (out.exists() && !out.delete()) {
                callback.onError("cannot replace " + out);
                return;
            }
            final ParcelFileDescriptor fd = ParcelFileDescriptor.open(out,
                    ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
            adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, fd, new CancellationSignal(), new PrintDocumentAdapter.WriteResultCallback() {
                @Override
                public void onWriteFinished(PageRange[] pages) {
                    close(fd);
                    adapter.onFinish();
                    callback.onSuccess();
                }

                @Override
                public void onWriteFailed(CharSequence error) {
                    close(fd);
                    callback.onError(error == null ? "write failed" : error.toString());
                }

                @Override
                public void onWriteCancelled() {
                    close(fd);
                    callback.onError("write cancelled");
                }
            });
        } catch (Throwable t) {
            callback.onError(String.valueOf(t.getMessage()));
        }
    }

    private static void close(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (Exception ignored) {
            // уже закрыт
        }
    }
}
