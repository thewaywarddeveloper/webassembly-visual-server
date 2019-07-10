package com.thewaywarddeveloper.wavs

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.Desktop.Action.BROWSE
import java.awt.Desktop.getDesktop
import java.awt.Desktop.isDesktopSupported
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.Toolkit.getDefaultToolkit
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.IOException
import java.lang.Integer.MAX_VALUE
import java.lang.Integer.toHexString
import java.lang.ProcessHandle
import java.lang.System.currentTimeMillis
import java.lang.System.getProperty
import java.net.http.HttpClient.newHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.BodyHandlers.discarding
import java.net.http.HttpResponse.BodyHandlers.ofString
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.net.URI
import java.net.URLEncoder.encode
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.allOf
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedList
import java.util.prefs.Preferences
import java.util.prefs.Preferences.userNodeForPackage
import java.util.Random
import javax.swing.GroupLayout
import javax.swing.GroupLayout.Alignment.CENTER
import javax.swing.GroupLayout.Alignment.TRAILING
import javax.swing.InputVerifier
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFileChooser.APPROVE_OPTION
import javax.swing.JFrame
import javax.swing.JFrame.MAXIMIZED_BOTH
import javax.swing.JLabel
import javax.swing.JOptionPane.ERROR_MESSAGE
import javax.swing.JOptionPane.INFORMATION_MESSAGE
import javax.swing.JOptionPane.NO_OPTION
import javax.swing.JOptionPane.OK_OPTION
import javax.swing.JOptionPane.QUESTION_MESSAGE
import javax.swing.JOptionPane.showConfirmDialog
import javax.swing.JOptionPane.showMessageDialog
import javax.swing.JOptionPane.showOptionDialog
import javax.swing.JOptionPane.WARNING_MESSAGE
import javax.swing.JOptionPane.YES_NO_CANCEL_OPTION
import javax.swing.JOptionPane.YES_NO_OPTION
import javax.swing.JOptionPane.YES_OPTION
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSpinner.NumberEditor
import javax.swing.JTable
import javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
import javax.swing.JTextField
import javax.swing.JTextField.LEFT
import javax.swing.JTextField.RIGHT
import javax.swing.LayoutStyle.ComponentPlacement.RELATED
import javax.swing.LayoutStyle.ComponentPlacement.UNRELATED
import javax.swing.ListSelectionModel.SINGLE_SELECTION
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities.invokeLater
import javax.swing.table.DefaultTableModel
import javax.swing.UIManager.getSystemLookAndFeelClassName
import javax.swing.UIManager.setLookAndFeel
import javax.swing.WindowConstants.DISPOSE_ON_CLOSE

private data class Endpoint(val address: String, val port: Int, val path: String, val filePath: String)

private enum class Status {
    ENABLED, DISABLED;
    
    override fun toString() = super.toString().toLowerCase().capitalize()
}

private object Server {
    private data class AddressAndPort(val address: String, val port: Int)
    private data class InstanceAndPaths(val instance: HttpServer, val paths: MutableMap<String, String>)
    
    private val instances = HashMap<AddressAndPort, InstanceAndPaths>()
    
    private data class EndpointAndStatus(var endpoint: Endpoint, var status: Status)
    
    private val list = LinkedList<EndpointAndStatus>()
    
    private lateinit var additionListener:     ((Endpoint, Status) -> Unit)
    private lateinit var changeListener:       ((Int, Int, Any) -> Unit)
    private lateinit var statusChangeListener: ((Status) -> Unit)
    private lateinit var removalListener:      ((Int) -> Unit)
    
    private var indexListenedForStatusChange: Int? = null
    
    fun add(endpoint: Endpoint) {
        val status = Status.DISABLED
        
        list.add(EndpointAndStatus(endpoint, status))
        
        additionListener.invoke(endpoint, status)
    }
    
    fun update(index: Int, data: Endpoint) {
        val endpoint = get(index)
        
        if (endpoint != data) {
            list.get(index).endpoint = data
            
            if (endpoint.address != data.address) {
                changeListener.invoke(index, 0, data.address)
            }
            
            if (endpoint.port != data.port) {
                changeListener.invoke(index, 1, data.port)
            }
            
            if (endpoint.path != data.path) {
                changeListener.invoke(index, 2, data.path)
            }
            
            if (endpoint.filePath != data.filePath) {
                changeListener.invoke(index, 3, data.filePath)
            }
        }
    }
    
    private fun setStatusOf(index: Int, status: Status) {
        list.get(index).status = status
        
        changeListener.invoke(index, 4, status)
        
        if (indexListenedForStatusChange == index) {
            statusChangeListener.invoke(status)
        }
    }
    
    class AddressAndPortConflictException: Exception()
    class PathConflictException: Exception()
    
    private fun HttpExchange.respond(status: Int, contentType: String, contentLength: Long, content: InputStream) {
        responseHeaders["Content-Type"] = listOf(contentType)
        
        sendResponseHeaders(status, contentLength)
        content.copyTo(responseBody)
        
        close()
    }
    
    private fun String.escape() = replace("&", "&amp;").replace("<", "&lt;")
    
    private fun HttpExchange.respondWithDocument(status: Int, title: String, text: String, script: String? = null) {
        val scriptOrNothing = if (script != null) "\n" + " ".repeat(4 * 5) + script else ""
        
        val document = """
            <!DOCTYPE html>
            <html lang="en">
                <head>
                    <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
                    <title>$title</title>$scriptOrNothing
                </head>
                <body>
                    <p>$text</p>
                </body>
            </html>
        """.trimIndent().toByteArray()
        
        respond(status, "text/html; charset=utf-8", document.size.toLong(), document.inputStream())
    }
    
    private class Handler(val paths: Map<String, String>): HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                with (exchange) {
                    val path = requestURI.path
                    
                    if (requestURI.query == null) {
                        val filePath = paths[path]
                        
                        if (filePath != null) {
                            val file = File(filePath)
                            
                            try {
                                file.inputStream().use { fileStream ->
                                    responseHeaders["Access-Control-Allow-Origin"] = listOf("*")
                                    respond(200, "application/wasm", file.length(), fileStream)
                                }
                            
                            } catch (e: FileNotFoundException) {
                                respondWithDocument(500, "Error",
                                    "No file found at the location associated with the path <code>${path.escape()}</code>")
                            }
                        
                        } else {
                            respondWithDocument(404, "Not Found",
                                "No file is associated with the path <code>${path.escape()}</code>")
                        }
                    
                    } else {
                        if (requestURI.query == "!js") {
                            val script = """
                                const paragraph = new Promise((resolve, _) => {
                                    window.addEventListener('DOMContentLoaded', () => {
                                        resolve(document.querySelector('p'));
                                    });
                                });
                                
                                (async () => {
                                    (await paragraph).textContent = 'Loading…';
                                })();
                                
                                (async () => {
                                    const response = await fetch(location.pathname);
                                    
                                    if (response.ok) {
                                        try {
                                            window.module = await WebAssembly.compile(await response.arrayBuffer());
                                            
                                            if (WebAssembly.Module.imports(window.module).length > 0) {
                                                (await paragraph).innerHTML = 'The module is available as variable <var>module</var>.';
                                            
                                            } else {
                                                try {
                                                    window.instance = await WebAssembly.instantiate(module);
                                                    (await paragraph).innerHTML = 'The module and an instance are available as variables <var>module</var> and <var>instance</var>.';
                                                
                                                } catch (error) {
                                                    if (error instanceof WebAssembly.LinkError || error instanceof WebAssembly.RuntimeError) {
                                                        console.error(error);
                                                        (await paragraph).textContent = 'Error while instantiating module.';
                                                    
                                                    } else {
                                                        throw error;
                                                    }
                                                }
                                            }
                                        
                                        } catch (error) {
                                            if (error instanceof WebAssembly.CompileError) {
                                                console.error(error);
                                                (await paragraph).textContent = 'Error while compiling module.';
                                            
                                            } else {
                                                throw error;
                                            }
                                        }
                                    
                                    } else {
                                        const text = await response.text();
                                        
                                        document.open();
                                        document.write(text);
                                        document.close();
                                    }
                                })();
                            """.trimIndent().toByteArray()
                            
                            respond(200, "application/javascript; charset=utf-8", script.size.toLong(), script.inputStream())
                        
                        } else {
                            val script = "<script src=\"${requestURI.rawPath}?!js\"></script>"
                            
                            respondWithDocument(200, path.escape(),
                                "Loading of the module requires JavaScript enabled.", script)
                        }
                    }
                }
            
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }
    
    fun enable(index: Int) {
        val (address, port, path, filePath) = list.get(index).endpoint
        
        val (_, paths) = instances.getOrPut(AddressAndPort(address, port)) {
            val instance = try {
                HttpServer.create(InetSocketAddress(InetAddress.getByName(address), port), 0)
            
            } catch (_: SocketException) {
                throw AddressAndPortConflictException()
            }
            
            val paths = ConcurrentHashMap<String, String>()
            
            instance.createContext("/", Handler(paths))
            instance.start()
            
            InstanceAndPaths(instance, paths)
        }
        
        if (paths.containsKey(path)) {
            throw PathConflictException()
        }
        
        paths[path] = filePath
        
        setStatusOf(index, Status.ENABLED)
    }
    
    fun disable(index: Int) {
        val (address, port, path, _) = list.get(index).endpoint
        
        val addressAndPort = AddressAndPort(address, port)
        
        instances[addressAndPort]!!.let {(instance, paths) ->
            paths.remove(path)
            
            if (paths.size == 0) {
                instance.stop(0)
                instances.remove(addressAndPort)
            }
        }
        
        setStatusOf(index, Status.DISABLED)
    }
    
    fun disableAll() {
        for ((index, element) in list.withIndex()) {
            if (element.status == Status.ENABLED) {
                disable(index)
            }
        }
    }
    
    val numberOfEntries get() = list.size
    
    fun get(index: Int) = list.get(index).endpoint
    
    fun statusOf(index: Int) = list.get(index).status
    
    fun remove(index: Int) {
        list.removeAt(index)
        removalListener.invoke(index)
    }
    
    fun setAdditionListener(listener: (Endpoint, Status) -> Unit) {
        additionListener = listener
    }
    
    fun setChangeListener(listener: (Int, Int, Any) -> Unit) {
        changeListener = listener
    }
    
    fun setStatusChangeListener(listener: (Status) -> Unit) {
        statusChangeListener = listener
    }
    
    fun setRemovalListener(listener: (Int) -> Unit) {
        removalListener = listener
    }
    
    fun setIndexListenedForStatusChange(index: Int?) {
        indexListenedForStatusChange = index
    }
}

private fun error(text: String, component: Component) = showMessageDialog(component, text, "Error", ERROR_MESSAGE)

private class Dialog(frame: JFrame, values: Endpoint? = null): JDialog(frame, true) {
    private var values: Endpoint? = null
    
    init {
        title = if (values == null) "Add new" else "Edit"
        
        val fileLabel    = JLabel("File")
        val addressLabel = JLabel("Address")
        val portLabel    = JLabel("Port")
        val pathLabel    = JLabel("Path")
        
        var filePath = values?.filePath ?: ""
        
        val fileChooser = JFileChooser().apply {
            if (filePath != "") {
                selectedFile = File(filePath)
            }
        }
        
        val fileField = object: JTextField(15) {
            init {
                isEditable  = false
                isFocusable = false
            }
            
            val widthOf = getFontMetrics(font)::stringWidth
            val contentAreaWidth: Int by lazy {size.width - with (insets) {left + right}}
            
            fun update() {
                if (widthOf(filePath) > contentAreaWidth) {
                    val prefix = "…"
                    
                    for (index in 1 until filePath.length) {
                        val value = prefix + filePath.substring(index)
                        
                        if (widthOf(value) < contentAreaWidth) {
                            text = value
                            horizontalAlignment = RIGHT
                            
                            break
                        }
                    }
                
                } else {
                    text = filePath
                    horizontalAlignment = LEFT
                }
            }
        }
        
        val fileButton = JButton("Select").apply {
            addActionListener {
                val option = fileChooser.showDialog(this@Dialog, "Select")
                
                if (option == APPROVE_OPTION) {
                    filePath = fileChooser.selectedFile.toString()
                    fileField.update()
                }
            }
        }
        
        class RevertingVerifier(initialValue: String, val verification: (String) -> Boolean): InputVerifier() {
            private var lastValidValue = initialValue
            
            override fun verify(component: JComponent) = verification.invoke((component as JTextField).text)
            
            override fun shouldYieldFocus(source: JComponent): Boolean {
                val component = source as JTextField
                
                if (!verify(component)) {
                    component.text = lastValidValue
                
                } else {
                    lastValidValue = component.text
                }
                
                return true
            }
        }
        
        val addressField = JTextField().apply {
            text = values?.address ?: "127.0.0.1"
            
            preferredSize = Dimension(
                getFontMetrics(font).stringWidth("000.000.000.000.") + with (insets) {left + right},
                preferredSize.height
            )
            
            inputVerifier = RevertingVerifier(text) { value ->
                if (value.length > 0 && (value[0] in '0'..'9' || value[0] == ':')) {
                    try {
                        InetAddress.getByName(value)
                        true
                    
                    } catch (e: UnknownHostException) {
                        false
                    }
                
                } else {
                    false
                }
            }
        }
        
        val portField = JSpinner(SpinnerNumberModel(values?.port ?: 8080, 0, 65535, 1)).apply {
            editor = NumberEditor(this, "#").apply {
                with (textField) {
                    preferredSize = Dimension(
                        getFontMetrics(font).stringWidth("00000.") + with (insets) {left + right},
                        preferredSize.height
                    )
                    
                    columns = 0
                }
            }
        }
        
        val pathField = JTextField(15).apply {
            text = values?.path ?: "/"
            inputVerifier = RevertingVerifier(text, {it.startsWith("/")})
        }
        
        fileLabel.labelFor    = fileButton
        addressLabel.labelFor = addressField
        portLabel.labelFor    = portField
        pathLabel.labelFor    = pathField
        
        val confirmButton = JButton(if (values == null) "Add" else "Save").apply {
            addActionListener {
                if (filePath == "") {
                    error("No file is selected.", this@Dialog)
                    
                    return@addActionListener
                }
                
                this@Dialog.values = Endpoint(addressField.text, portField.value as Int, pathField.text, filePath)
                this@Dialog.isVisible = false
            }
        }
        
        val cancelButton = JButton("Cancel").apply {
            addActionListener {
                this@Dialog.isVisible = false
            }
        }
        
        layout = GroupLayout(contentPane).apply {
            autoCreateContainerGaps = true
            
            setVerticalGroup(createSequentialGroup().apply {
                addComponent(fileLabel)
                addPreferredGap(RELATED)
                
                addGroup(createParallelGroup(CENTER).apply {
                    addComponent(fileField)
                    addComponent(fileButton)
                })
                
                addPreferredGap(UNRELATED)
                
                addGroup(createParallelGroup().apply {
                    addComponent(addressLabel)
                    addComponent(portLabel)
                    addComponent(pathLabel)
                })
                
                addPreferredGap(RELATED)
                
                addGroup(createParallelGroup().apply {
                    addComponent(addressField)
                    addComponent(portField)
                    addComponent(pathField)
                })
                
                addPreferredGap(UNRELATED)
                
                addGroup(createParallelGroup().apply {
                    addComponent(confirmButton)
                    addComponent(cancelButton)
                })
            })
            
            setHorizontalGroup(createParallelGroup(TRAILING).apply {
                addGroup(createParallelGroup().apply {
                    addComponent(fileLabel)
                    
                    addGroup(createSequentialGroup().apply {
                        addComponent(fileField)
                        addPreferredGap(RELATED)
                        addComponent(fileButton)
                        addGap(0, 0, MAX_VALUE)
                    })
                    
                    addGroup(createSequentialGroup().apply {
                        addGroup(createParallelGroup().apply {
                            addComponent(addressLabel)
                            addComponent(addressField)
                        })
                        
                        addPreferredGap(RELATED)
                        
                        addGroup(createParallelGroup().apply {
                            addComponent(portLabel)
                            addComponent(portField)
                        })
                        
                        addPreferredGap(RELATED)
                        
                        addGroup(createParallelGroup().apply {
                            addComponent(pathLabel)
                            addComponent(pathField)
                        })
                    })
                })
                
                addGroup(createSequentialGroup().apply {
                    addComponent(confirmButton)
                    addPreferredGap(RELATED)
                    addComponent(cancelButton)
                })
            })
        }
        
        pack()
        
        fileField.update()
        
        isResizable = false
        
        setLocationRelativeTo(frame)
    }
    
    fun prompt(): Endpoint? {
        isVisible = true
        
        dispose()
        
        return values
    }
}

private val version = "1-0"

private fun uri(path: String, query: String? = null): URI {
    return URI("https", "thewaywarddeveloper.com", "/api/wavs/v1/$path", query, null)
}

private fun <T> request(uri: URI, handler: BodyHandler<T>): CompletableFuture<T> {
    val result = CompletableFuture<T>()
    
    newHttpClient().sendAsync(HttpRequest.newBuilder(uri).build(), handler).thenAccept { response ->
        if (response.statusCode() == 200) {
            result.complete(response.body())
        }
    }
    
    return result
}

private fun request(uri: URI) = request(uri, discarding())

private val namespace = userNodeForPackage(object {}.javaClass).node("v1")

private fun <T> Preferences.getOrNull(key: String, method: Preferences.() -> T): T? {
    return if (key in keys()) method.invoke(this) else null
}

private fun read(path: String, key: String)    = namespace.node(path).getOrNull(key, {get(key, "")})
private fun readInt(path: String, key: String) = namespace.node(path).getOrNull(key, {getInt(key, 0)})

private fun write(path: String, key: String, value: String) = namespace.node(path).put(key, value)
private fun write(path: String, key: String, value: Int)    = namespace.node(path).putInt(key, value)

val systemId = read("", "id")

val processId = with (ProcessHandle.current()) {
    pid().toString() + "." + info().startInstant().orElse(Instant.ofEpochMilli(0)).toEpochMilli().toString()
}

private fun sendEvent(event: String, attributes: List<Pair<String, String>>) {
    val defaultAttributes = listOf(
        "t"   to currentTimeMillis().toString(),
        "sid" to systemId
    )
    
    request(uri("actions/$event", (defaultAttributes + attributes).map({(name, value) -> "$name=$value"}).joinToString("&")))
}

private fun sendOpenEvent(arguments: String) {
    fun property(name: String) = encode(getProperty(name), "UTF-8")
    
    val attributes = listOf(
        "pid" to processId,
        "a"   to arguments,
        "v"   to version,
        "sn"  to property("os.name"),
        "sv"  to property("os.version"),
        "sa"  to property("os.arch")
    )
    
    sendEvent("open", attributes)
}

private fun sendCloseEvent() = sendEvent("close", listOf("pid" to processId))

private fun sendUpdateEvent(id: String, action: String) {
    val attributes = listOf(
        "id" to id,
        "a"  to action
    )
    
    sendEvent("update", attributes)
}

private val browse = if (isDesktopSupported()) with(getDesktop()) {if (isSupported(BROWSE)) ::browse else null } else null

private fun openFallbackDialogFor(url: URI, frame: JFrame) {
    val option = showConfirmDialog(frame, "Unable to open URL, copy it to clipboard?", "Copy",
        YES_NO_OPTION, QUESTION_MESSAGE)
    
    if (option == YES_OPTION) {
        getDefaultToolkit().getSystemClipboard().setContents(StringSelection(url.toString()), null)
    }
}

private fun open(url: URI, frame: JFrame) {
    if (browse != null) {
        try {
            browse(url)
        
        } catch (e: IOException) {
            openFallbackDialogFor(url, frame)
        }
    
    } else {
        openFallbackDialogFor(url, frame)
    }
}

fun main(args: Array<String>) {
    if ("-d" in args) {
        for (name in namespace.childrenNames()) {
            namespace.node(name).removeNode()
        }
        
        return
    }
    
    val updatesAreEnabled      = "-u" !in args
    val sendingEventsIsEnabled = "-s" !in args
    
    if (read("", "id") == null) {
        fun Int.format() = toHexString(this).padStart(8, '0').toUpperCase()
        
        with (Random()) {
            write("", "id", nextInt().format() + nextInt().format() + nextInt().format() + nextInt().format())
        }
    }
    
    setLookAndFeel(getSystemLookAndFeelClassName())
    
    data class Update(val id: String, val text: String, val url: String)
    
    val updates = CompletableFuture<List<Update>>()
    var dismissed: MutableList<String>? = null
    
    invokeLater {
        val frame = JFrame("WebAssembly Visual Server").apply frame@ {
            setIconImage(getDefaultToolkit().createImage(object {}.javaClass.getResource("icon.png")))
            
            val table = object: JTable() {
                init {
                    model = DefaultTableModel().apply {
                        addColumn("Address")
                        addColumn("Port")
                        addColumn("Path")
                        addColumn("File")
                        addColumn("State")
                        
                        Server.setAdditionListener { endpoint, status ->
                            addRow(arrayOf(endpoint.address, endpoint.port, endpoint.path, endpoint.filePath, status))
                        }
                        
                        Server.setChangeListener { index, field, value ->
                            setValueAt(value, index, field)
                        }
                        
                        Server.setRemovalListener { index ->
                            removeRow(index)
                        }
                    }
                    
                    setSelectionMode(SINGLE_SELECTION)
                    
                    tableHeader.reorderingAllowed = false
                    
                    fun setColumnWidth(index: Int, value: Int?) = value?.let {
                        columnModel.getColumn(index).width = it
                    }
                    
                    setColumnWidth(0, readInt("column_widths", "address"))
                    setColumnWidth(1, readInt("column_widths", "port"))
                    setColumnWidth(2, readInt("column_widths", "path"))
                    setColumnWidth(3, readInt("column_widths", "file"))
                    setColumnWidth(4, readInt("column_widths", "state"))
                    
                    autoResizeMode = AUTO_RESIZE_LAST_COLUMN
                    
                    preferredScrollableViewportSize = Dimension(preferredScrollableViewportSize.width, rowHeight * 5)
                }
                
                override fun isCellEditable(row: Int, column: Int): Boolean = false
                
                override fun doLayout() {
                    tableHeader.resizingColumn = tableHeader.resizingColumn ?: columnModel.getColumn(columnCount - 1)
                    super.doLayout()
                }
            }
            
            val scrollPane = JScrollPane(table)
            
            val enableDisableButton = JButton().apply {
                text = "Enable"
                
                val enableSize = preferredSize
                
                text = "Disable"
                
                val disableSize = preferredSize
                
                minimumSize = Dimension(
                    maxOf(enableSize.width,  disableSize.width),
                    maxOf(enableSize.height, disableSize.height)
                )
                
                isVisible = false
                
                addActionListener {
                    val index = table.selectedRow
                    
                    if (Server.statusOf(index) != Status.ENABLED) {
                        try {
                            Server.enable(index)
                        
                        } catch (e: Server.AddressAndPortConflictException) {
                            val (address, port, _, _) = Server.get(index)
                            
                            error("Cannot listen on $address:$port", this@frame)
                        
                        } catch (e: Server.PathConflictException) {
                            val path = Server.get(index).path
                            
                            error("The path $path is already in use.", this@frame)
                        }
                    
                    } else {
                        Server.disable(index)
                    }
                }
            }
            
            val openButton = JButton("Open").apply {
                isVisible = false
                
                fun url() = with (Server.get(table.selectedRow)) {
                    URI("http", null, address, port, path, "!", null)
                }
                
                addActionListener {
                    open(url(), this@frame)
                }
            }
            
            val editButton = JButton("Edit").apply {
                isVisible = false
                
                addActionListener {
                    val index = table.selectedRow
                    
                    Dialog(this@frame, Server.get(index)).prompt()?.let { data ->
                        Server.update(index, data)
                    }
                }
            }
            
            val removeButton = JButton("Remove").apply {
                isVisible = false
                
                addActionListener {
                    val option = showConfirmDialog(this@frame, "Remove the selected endpoint?", "Remove",
                        YES_NO_OPTION, WARNING_MESSAGE)
                    
                    if (option == YES_OPTION) {
                        Server.remove(table.selectedRow)
                    }
                }
            }
            
            val addButton = JButton("Add new").apply {
                addActionListener {
                    Dialog(this@frame).prompt()?.let { endpoint ->
                        Server.add(endpoint)
                    }
                }
            }
            
            fun updateButtonsForStatus(status: Status) {
                enableDisableButton.text = when (status) {
                    Status.DISABLED -> "Enable"
                    Status.ENABLED  -> "Disable"
                }
                
                openButton.isEnabled   = status == Status.ENABLED
                editButton.isEnabled   = status == Status.DISABLED
                removeButton.isEnabled = status == Status.DISABLED
            }
            
            Server.setStatusChangeListener { status ->
                updateButtonsForStatus(status)
            }
            
            table.selectionModel.addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    val index = table.selectedRow
                    
                    val isSelectionEvent = index > -1
                    
                    if (isSelectionEvent) {
                        updateButtonsForStatus(Server.statusOf(index))
                        Server.setIndexListenedForStatusChange(index)
                    
                    } else {
                        Server.setIndexListenedForStatusChange(null)
                    }
                    
                    enableDisableButton.isVisible = isSelectionEvent
                    openButton.isVisible          = isSelectionEvent
                    editButton.isVisible          = isSelectionEvent
                    removeButton.isVisible        = isSelectionEvent
                }
            }
            
            layout = GroupLayout(contentPane).apply {
                autoCreateContainerGaps = true
                
                setVerticalGroup(createSequentialGroup().apply {
                    addComponent(scrollPane)
                    addPreferredGap(UNRELATED)
                    
                    addGroup(createParallelGroup().apply {
                        addComponent(enableDisableButton)
                        addComponent(openButton)
                        addComponent(editButton)
                        addComponent(removeButton)
                        addComponent(addButton)
                    })
                })
                
                setHorizontalGroup(createParallelGroup(TRAILING).apply {
                    addComponent(scrollPane)
                    
                    addGroup(createSequentialGroup().apply {
                        addComponent(enableDisableButton)
                        addPreferredGap(RELATED)
                        addComponent(openButton)
                        addPreferredGap(RELATED)
                        addComponent(editButton)
                        addPreferredGap(RELATED)
                        addComponent(removeButton)
                        addPreferredGap(RELATED)
                        addComponent(addButton)
                    })
                })
            }
            
            pack()
            
            minimumSize = size
            
            val windowWidth  = readInt("window_size", "width")
            val windowHeight = readInt("window_size", "height")
            
            if (windowWidth != null && windowHeight != null) {
                size = Dimension(windowWidth, windowHeight)
            }
            
            val windowX = readInt("window_location", "x")
            val windowY = readInt("window_location", "y")
            
            if (windowX != null && windowY != null) {
                setLocation(windowX, windowY)
            
            } else {
                isLocationByPlatform = true
            }
            
            if (read("", "window_state") == "maximized") {
                extendedState = MAXIMIZED_BOTH
            }
            
            var oldWindowLocation = location
            var newWindowLocation = location
            
            var oldWindowSize = size
            var newWindowSize = size
            
            addComponentListener(object: ComponentAdapter() {
                override fun componentMoved(event: ComponentEvent) {
                    oldWindowLocation = newWindowLocation
                    newWindowLocation = location
                }
                
                override fun componentResized(event: ComponentEvent) {
                    oldWindowSize = newWindowSize
                    newWindowSize = size
                }
            })
            
            addWindowListener(object: WindowAdapter() {
                override fun windowClosed(event: WindowEvent) {
                    Server.disableAll()
                    
                    val maximized = extendedState and MAXIMIZED_BOTH != 0
                    
                    val windowLocation = if (maximized) oldWindowLocation else newWindowLocation
                    val windowSize     = if (maximized) oldWindowSize     else newWindowSize
                    
                    write("window_location", "x", windowLocation.x)
                    write("window_location", "y", windowLocation.y)
                    
                    write("window_size", "width",  windowSize.width)
                    write("window_size", "height", windowSize.height)
                    
                    write("", "window_state", if (maximized) "maximized" else "normal")
                    
                    fun widthOfColumn(index: Int) = table.columnModel.getColumn(index).width
                    
                    write("column_widths", "address", widthOfColumn(0))
                    write("column_widths", "port",    widthOfColumn(1))
                    write("column_widths", "path",    widthOfColumn(2))
                    write("column_widths", "file",    widthOfColumn(3))
                    write("column_widths", "state",   widthOfColumn(4))
                    
                    val listSize = Server.numberOfEntries
                    
                    write("list", "size", listSize)
                    
                    namespace.node("list/entries").removeNode()
                    
                    for (index in 0 until listSize) {
                        with (Server.get(index)) {
                            write("list/entries/$index", "address",   address)
                            write("list/entries/$index", "port",      port)
                            write("list/entries/$index", "path",      path)
                            write("list/entries/$index", "file_path", filePath)
                        }
                    }
                    
                    if (updatesAreEnabled) {
                        namespace.node("updates").removeNode()
                        
                        write("updates", "size", dismissed!!.size)
                        
                        for ((index, id) in dismissed!!.withIndex()) {
                            write("updates/entries", index.toString(), id.toString())
                        }
                    }
                    
                    if (sendingEventsIsEnabled) {
                        sendCloseEvent()
                    }
                }
            })
            
            defaultCloseOperation = DISPOSE_ON_CLOSE
        }
        
        val listSize = readInt("list", "size") ?: 0
        
        for (index in 0 until listSize) {
            val address  = read("list/entries/$index",    "address")
            val port     = readInt("list/entries/$index", "port")
            val path     = read("list/entries/$index",    "path")
            val filePath = read("list/entries/$index",    "file_path")
            
            if (address != null && port != null && path != null && filePath != null) {
                Server.add(Endpoint(address, port, path, filePath))
            }
        }
        
        frame.isVisible = true
        
        updates.thenAccept { list ->
            for (update in list) {
                val option = showOptionDialog(frame, update.text, "Update", YES_NO_CANCEL_OPTION, INFORMATION_MESSAGE, null,
                    arrayOf("Visit", "Dismiss", "Remind me later"), null)
                
                if (option == YES_OPTION) {
                    open(URI(update.url), frame)
                }
                
                if (option == YES_OPTION || option == NO_OPTION) {
                    dismissed!!.add(update.id)
                }
                
                if (sendingEventsIsEnabled) {
                    sendUpdateEvent(update.id, when (option) {YES_OPTION -> "v"; NO_OPTION -> "d"; else -> "r"})
                }
            }
        }
    }
    
    if (updatesAreEnabled) {
        dismissed = (0 until (readInt("updates", "size") ?: 0)).map({
            read("updates/entries", it.toString())
        }).filterNotNull().toMutableList()
        
        request(uri("updates/indexes/$version.txt"), ofString()).thenAccept { indexDocument ->
            val index = indexDocument.lines().dropLast(1).toMutableList()
            
            dismissed.retainAll({it in index})
            index.removeAll({it in dismissed})
            
            val results = index.map { id ->
                request(uri("updates/$id.txt"), ofString())
            }
            
            allOf(*results.toTypedArray()).whenComplete { _, _ ->
                val list = mutableListOf<Update>()
                
                for ((id, result) in index.zip(results)) {
                    if (!result.isCompletedExceptionally()) {
                        val updateDocument = result.get().lines()
                        
                        list.add(Update(id, updateDocument[0], updateDocument[1]))
                    }
                }
                
                updates.complete(list)
            }
        }
    }
    
    if (sendingEventsIsEnabled) {
        sendOpenEvent(args.joinToString(" "))
    }
}
