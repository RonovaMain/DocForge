package dev.ronova.docforge.ooxml

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Разбирает XML с поддержкой пространств имён. DOCTYPE запрещён (защита от XXE), где парсер это умеет. */
internal fun parseXml(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    // На Android часть настроек не поддерживается — это не страшно, поэтому runCatching
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.isExpandEntityReferences = false }
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

internal fun Document.toBytes(): ByteArray {
    runCatching { xmlStandalone = true }
    val out = ByteArrayOutputStream()
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    transformer.setOutputProperty(OutputKeys.INDENT, "no")
    transformer.transform(DOMSource(this), StreamResult(out))
    return out.toByteArray()
}

private fun NodeList.toElementList(): List<Element> {
    val result = ArrayList<Element>(length)
    for (i in 0 until length) {
        val node = item(i)
        if (node is Element) result.add(node)
    }
    return result
}

/** Все потомки-элементы с данным именем (в порядке документа). */
internal fun Document.elements(ns: String, local: String): List<Element> =
    getElementsByTagNameNS(ns, local).toElementList()

internal fun Element.elements(ns: String, local: String): List<Element> =
    getElementsByTagNameNS(ns, local).toElementList()

/** Прямые дочерние элементы. */
internal fun Element.children(): List<Element> {
    val result = ArrayList<Element>()
    var node = firstChild
    while (node != null) {
        if (node is Element) result.add(node)
        node = node.nextSibling
    }
    return result
}

internal fun Element.isNamed(ns: String, local: String): Boolean = namespaceURI == ns && localName == local

internal fun Element.children(ns: String, local: String): List<Element> = children().filter { it.isNamed(ns, local) }

internal fun Element.child(ns: String, local: String): Element? = children().firstOrNull { it.isNamed(ns, local) }
