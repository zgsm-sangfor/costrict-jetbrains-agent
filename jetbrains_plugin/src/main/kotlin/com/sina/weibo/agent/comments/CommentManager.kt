// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.comments

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.icons.AllIcons
import com.sina.weibo.agent.editor.EditorAndDocManager
import com.sina.weibo.agent.editor.EditorHolder
import com.sina.weibo.agent.editor.Range
import com.sina.weibo.agent.editor.ModelAddedData
import com.sina.weibo.agent.editor.createURI
import com.sina.weibo.agent.util.URI
import com.sina.weibo.agent.actions.executeCommand
import com.intellij.ide.BrowserUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import com.intellij.openapi.util.text.StringUtil

@Service(Service.Level.PROJECT)
class CommentManager(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(CommentManager::class.java)

    private val controllers = ConcurrentHashMap<Int, CommentController>()
    private val threads = ConcurrentHashMap<Int, CommentThread>()
    private val controllerRanges = ConcurrentHashMap<Int, Map<String, Any?>?>()
    private val openPopups = ConcurrentHashMap<Int, JBPopup>()
    private val markdownFlavour = CommonMarkFlavourDescriptor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val editorManager: EditorAndDocManager = project.getService(EditorAndDocManager::class.java)
    private val listeners = CopyOnWriteArrayList<CommentManagerListener>()

    init {
        // Listen for file open/close events
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                // Clean up UI for threads when file is closed
                val fileUri = URI.file(file.path)
                logger.debug("File closed event: ${fileUri.path}")
                deleteThreadsForFile(fileUri)
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                // Restore threads UI when file is opened
                val fileUri = URI.file(file.path)
                logger.debug("File opened event: ${fileUri.path}")
                restoreThreadsForFile(fileUri)
            }
        })
    }

    fun registerController(handle: Int, id: String, label: String, extensionId: String) {
        controllers[handle] = CommentController(handle, id, label, extensionId)
        logger.debug("Register comment controller: handle=$handle, id=$id")
        notifyListeners()
    }

    fun unregisterController(handle: Int) {
        logger.debug("Unregister comment controller: handle=$handle")
        controllers.remove(handle)
        controllerRanges.remove(handle)

        val ownedThreads = threads.filterValues { it.controllerHandle == handle }.values.toList()
        ownedThreads.forEach { thread ->
            clearThreadUI(thread)
            threads.remove(thread.handle)
        }
        notifyListeners()
    }

    fun updateControllerFeatures(handle: Int, features: Map<String, Any?>) {
        logger.debug("Update controller features: handle=$handle")
        controllers.computeIfPresent(handle) { _, controller ->
            controller.copy(features = features.toFeatures())
        }
        notifyListeners()
    }

    fun updateCommentingRanges(handle: Int, resourceHints: Map<String, Any?>?) {
        logger.debug("Update commenting ranges: handle=$handle")
        controllerRanges[handle] = resourceHints
        notifyListeners()
    }

    fun createThread(
        handle: Int,
        commentThreadHandle: Int,
        threadId: String,
        resource: Map<String, Any?>,
        range: Map<String, Any?>?,
        comments: List<Map<String, Any?>>,
        extensionId: String,
        isTemplate: Boolean,
        editorId: String?
    ): Map<String, Any?>? {
        logger.debug("Create comment thread: controller=$handle, thread=$commentThreadHandle")
        val thread = createThreadInternal(
            ThreadCreationData(
                commentThreadHandle = commentThreadHandle,
                threadId = threadId,
                controllerHandle = handle,
                resource = resource,
                range = range,
                comments = comments,
                extensionId = extensionId,
                isTemplate = isTemplate,
                editorId = editorId
            ),
            controllers[handle]
        )
        notifyListeners()
        return thread.toSerializableMap()
    }

    fun updateThread(
        handle: Int,
        commentThreadHandle: Int,
        threadId: String,
        resource: Map<String, Any?>,
        changes: Map<String, Any?>
    ) {
        logger.debug("Update comment thread: controller=$handle, thread=$commentThreadHandle")
        val thread = threads[commentThreadHandle] ?: return

        if (thread.threadId != threadId) {
            logger.warn("Thread id mismatch for handle=$commentThreadHandle (${thread.threadId} vs $threadId)")
        }

        thread.resource = resource

        var requiresRerender = false

        changes["comments"]?.let { newComments ->
            @Suppress("UNCHECKED_CAST")
            thread.comments = newComments as? List<Map<String, Any?>> ?: thread.comments
            requiresRerender = true
        }

        changes["range"]?.let { updatedRange ->
            @Suppress("UNCHECKED_CAST")
            val rangeMap = updatedRange as? Map<String, Any?>
            if (rangeMap != null) {
                thread.range = CommentRange.fromMap(rangeMap)
                requiresRerender = true
            }
        }

        changes["collapseState"]?.let { collapseValue ->
            val newState = collapseValue as? String ?: thread.collapseState
            if (newState != null) {
                val collapsed = newState == "collapsed"
                if (!collapsed && thread.manuallyCollapsed && thread.collapseState == "collapsed") {
                    logger.debug(
                        "Ignoring remote expand for manually collapsed thread ${thread.threadId} (handle=${thread.handle})"
                    )
                } else {
                    thread.collapseState = newState
                    thread.manuallyCollapsed = collapsed
                    if (collapsed && thread.isVisible) {
                        thread.isVisible = false
                        clearThreadUI(thread)
                    } else if (!collapsed && !thread.isVisible) {
                        thread.isVisible = true
                        ensureThreadDisplayed(thread)
                    }
                }
            }
        }
        changes["canReply"]?.let {
            thread.canReply = it as? Boolean ?: thread.canReply
        }
        changes["isTemplate"]?.let {
            thread.isTemplate = it as? Boolean ?: thread.isTemplate
        }
        thread.editorHandleId =
            (changes["editorId"] as? String) ?: (changes["editorHandle"] as? String) ?: thread.editorHandleId

        // Check if thread UI was cleaned up (e.g., file was closed and reopened)
        // If highlighters are null but thread should be visible, we need to rerender
        val needsRerender = requiresRerender || 
            (thread.lineHighlighter == null && thread.collapseState != "collapsed")

        if (needsRerender) {
            ensureThreadDisplayed(thread)
            if (openPopups.containsKey(thread.handle)) {
                showThreadPopup(thread.handle, force = true)
            }
        } else if (openPopups.containsKey(thread.handle)) {
            showThreadPopup(thread.handle, force = true)
        }
        notifyListeners()
    }

    fun deleteThread(handle: Int, commentThreadHandle: Int) {
        logger.debug("Delete comment thread: controller=$handle, thread=$commentThreadHandle")
        threads.remove(commentThreadHandle)?.let { thread ->
            thread.isVisible = false
            thread.collapseState = "collapsed"
            clearThreadUI(thread)
        }
        notifyListeners()
    }

    suspend fun revealThread(
        handle: Int,
        commentThreadHandle: Int,
        commentUniqueId: Int,
        options: Map<String, Any?>
    ) {
        logger.debug(
            "Reveal comment thread: controller=$handle, thread=$commentThreadHandle, comment=$commentUniqueId, options=$options"
        )
        val thread = threads[commentThreadHandle] ?: return
        val context = resolveEditorContext(thread) ?: return

        thread.editorHandleId = context.holder.id
        val revealRange = Range(
            thread.range.startLine,
            thread.range.startColumn,
            thread.range.endLine,
            thread.range.endColumn
        )

        renderThread(context.editor, thread) {
            context.holder.revealRange(revealRange)
            val document = context.editor.document
            val caretOffset = thread.range.startOffset(document)
            context.editor.caretModel.moveToOffset(caretOffset)
            context.editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    fun hideThread(handle: Int, commentThreadHandle: Int) {
        logger.debug("Hide comment thread: controller=$handle, thread=$commentThreadHandle")
        threads[commentThreadHandle]?.let { thread ->
            thread.isVisible = false
            thread.collapseState = "collapsed"
            clearThreadUI(thread)
        }
        notifyListeners()
    }

    fun focusThread(commentThreadHandle: Int) {
        scope.launch {
            val thread = threads[commentThreadHandle] ?: return@launch
            revealThread(thread.controllerHandle, commentThreadHandle, -1, emptyMap())
            showThreadPopup(commentThreadHandle, force = true)
        }
    }

    fun toggleThreadVisibility(commentThreadHandle: Int) {
        val thread = threads[commentThreadHandle] ?: return
        if (thread.isVisible) {
            hideThread(thread.controllerHandle, commentThreadHandle)
        } else {
            thread.isVisible = true
            thread.collapseState = "expanded"
            ensureThreadDisplayed(thread)
            notifyListeners()
        }
    }

    fun ensureThreadsForRevealRange(editorId: String, revealRange: Range) {
        val holder = editorManager.getEditorHandleById(editorId)
        if (holder == null) {
            logger.warn("Unable to locate editor holder for id=$editorId when ensuring gutter visibility")
            return
        }

        val document = holder.document
        val targetRange = revealRange.toClampedCommentRange(document)
        if (targetRange == null) {
            logger.warn("Skipping gutter recovery for editor=$editorId because target range could not be derived")
            return
        }

        val targetPath = normalizedPath(document.uri.path)

        val candidateThreads = threads.values
            .filter { thread ->
                val threadUri = createURI(thread.resource)
                normalizedPath(threadUri.path) == targetPath
            }
            .sortedWith(
                compareBy<CommentThread> {
                    kotlin.math.abs(it.range.startLine - targetRange.startLine)
                }.thenBy { it.handle }
            )

        var adjustedThread: CommentThread? = null

        for (thread in candidateThreads) {
            val rangeInvalid = isRangeInvalidForDocument(thread.range, document)
            val overlapsTarget = rangesOverlap(thread.range, targetRange)
            val missingHighlighter =
                thread.lineHighlighter == null || thread.rangeHighlighter == null
            val requiresExpand = thread.collapseState == "collapsed" || !thread.isVisible
            val requiresEditorBinding = thread.editorHandleId != editorId
            val shouldAdjust =
                rangeInvalid || (overlapsTarget && (missingHighlighter || requiresExpand || requiresEditorBinding))

            if (!shouldAdjust) {
                continue
            }

            logger.debug(
                "Ensuring visibility for thread ${thread.threadId} (handle=${thread.handle}) in response to revealRange on editor=$editorId"
            )

            if (rangeInvalid) {
                logger.debug(
                    "Thread ${thread.threadId} has invalid range ${thread.range}, clamping to target range $targetRange"
                )
                thread.range = targetRange
            }

            if (requiresExpand) {
                thread.collapseState = "expanded"
                thread.isVisible = true
                thread.manuallyCollapsed = false
            }

            logger.debug(
                "Rendering thread ${thread.threadId} with range ${thread.range} due to revealRange on editor=$editorId"
            )

            thread.editorHandleId = editorId
            thread.manuallyCollapsed = false
            ensureThreadDisplayed(thread)
            adjustedThread = thread
            break
        }

        if (adjustedThread != null) {
            notifyListeners()
        }
    }

    fun showThreadPopup(commentThreadHandle: Int, force: Boolean = false) {
        if (!force) {
            val existing = openPopups[commentThreadHandle]
            if (existing != null) {
                runOnEdt {
                    openPopups.remove(commentThreadHandle)?.cancel()
                }
                return
            }
        }

        scope.launch {
            val thread = threads[commentThreadHandle] ?: return@launch
            val context = resolveEditorContext(thread) ?: return@launch

            thread.editorHandleId = context.holder.id
            thread.isVisible = true
            thread.collapseState = "expanded"
            thread.manuallyCollapsed = false

            renderThread(context.editor, thread)

            runOnEdt {
                openPopups.remove(thread.handle)?.cancel()

                val popup = createThreadPopup(context.editor, thread)
                popup.addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        openPopups.remove(thread.handle)
                    }
                })
                openPopups[thread.handle] = popup

                val anchor = computePopupLocation(context.editor, thread)
                popup.show(RelativePoint(context.editor.contentComponent, anchor))
            }
        }
    }

    private fun ensureThreadDisplayed(thread: CommentThread) {
        if (project.isDisposed) return
        scope.launch {
            val context = resolveEditorContext(thread)
            if (context == null) {
                logger.warn("Unable to resolve editor context for thread ${thread.threadId}")
                return@launch
            }
            thread.editorHandleId = context.holder.id
            thread.isVisible = true
            thread.collapseState = "expanded"
            thread.manuallyCollapsed = false
            renderThread(context.editor, thread)
        }
    }

    private suspend fun resolveEditorContext(thread: CommentThread): EditorContext? =
        withContext(Dispatchers.IO) {
            if (project.isDisposed) {
                return@withContext null
            }

            val uri = createURI(thread.resource)
            var holder = thread.editorHandleId?.let { editorManager.getEditorHandleById(it) }
                ?: editorManager.getEditorHandleByUri(uri).firstOrNull()

            if (holder == null) {
                holder = try {
                    editorManager.openEditor(uri)
                } catch (e: Exception) {
                    logger.warn("Failed to open editor for comment thread ${thread.threadId}", e)
                    null
                }
            }

            if (holder == null) {
                return@withContext null
            }

            var editor = (holder.ideaEditor as? TextEditor)?.editor
            
            // If editor is disposed or null, try to get fresh editor instance
            if (editor == null || editor.isDisposed) {
                editor = openEditorDirect(uri)
                if (editor != null) {
                    // Refresh holder reference
                    holder = editorManager.getEditorHandleByUri(uri).firstOrNull() ?: holder
                    // Update holder's editor reference
                    if (holder.ideaEditor == null || (holder.ideaEditor as? TextEditor)?.editor?.isDisposed == true) {
                        val fileEditors = FileEditorManager.getInstance(project).getEditors(VfsUtil.findFile(Paths.get(uri.fsPath), true)!!)
                        val textEditor = fileEditors.firstOrNull { it is TextEditor } as? TextEditor
                        if (textEditor != null) {
                            holder.ideaEditor = textEditor
                            editor = textEditor.editor
                        }
                    }
                }
            }

            if (editor == null) {
                logger.warn("Unable to obtain Editor instance for ${uri.path}")
                return@withContext null
            }

            if (editor.isDisposed) {
                logger.warn("Editor is disposed for ${uri.path}")
                return@withContext null
            }

            EditorContext(holder, editor)
        }

    private fun renderThread(editor: Editor, thread: CommentThread, afterRender: (() -> Unit)? = null) {
        runOnEdt {
            if (project.isDisposed) {
                return@runOnEdt
            }
            
            // Verify editor is still valid
            var validEditor = editor
            if (editor.isDisposed) {
                logger.warn("Editor is disposed in renderThread, trying to get current editor")
                val uri = createURI(thread.resource)
                val vFile = VfsUtil.findFile(Paths.get(uri.fsPath), true)
                if (vFile != null) {
                    val fileEditors = FileEditorManager.getInstance(project).getEditors(vFile)
                    val textEditor = fileEditors.firstOrNull { it is TextEditor } as? TextEditor
                    if (textEditor != null && !textEditor.editor.isDisposed) {
                        validEditor = textEditor.editor
                    } else {
                        logger.warn("Could not get valid editor, skipping render")
                        return@runOnEdt
                    }
                } else {
                    logger.warn("VirtualFile not found, skipping render")
                    return@runOnEdt
                }
            }
            
            renderThreadDirect(validEditor, thread)
            afterRender?.invoke()
        }
    }

    private fun renderThreadDirect(editor: Editor, thread: CommentThread) {
        clearThreadUiDirect(thread)

        val document = editor.document
        val (startLineZero, rawEndLineZero) = thread.range.toZeroBasedLines()
        val lineCount = document.lineCount

        if (lineCount == 0) {
            logger.warn("Document is empty, skip rendering comment thread ${thread.threadId}")
            return
        }

        if (startLineZero >= lineCount) {
            logger.warn("Thread ${thread.threadId} start line outside document bounds")
            return
        }
        val endLineZero = rawEndLineZero.coerceAtMost(lineCount - 1)

        val markupModel = editor.markupModel
        val lineHighlighter = markupModel.addLineHighlighter(
            startLineZero,
            HighlighterLayer.LAST + 1,
            null
        ).apply {
            gutterIconRenderer = CommentGutterIconRenderer(this@CommentManager, thread)
        }

        val startOffset = thread.range.startOffset(document)
        val endOffset = thread.range.endOffset(document).coerceAtLeast(startOffset)
        val rangeHighlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.LAST + 1,
            COMMENT_TEXT_ATTRIBUTES,
            HighlighterTargetArea.EXACT_RANGE
        )

        thread.lineHighlighter = lineHighlighter
        thread.rangeHighlighter = rangeHighlighter
        thread.isVisible = true
        thread.collapseState = "expanded"
    }

    private fun clearThreadUI(thread: CommentThread) {
        runOnEdt { clearThreadUiDirect(thread) }
    }

    private fun clearThreadUiDirect(thread: CommentThread) {
        thread.lineHighlighter?.dispose()
        thread.lineHighlighter = null
        thread.rangeHighlighter?.dispose()
        thread.rangeHighlighter = null
        openPopups.remove(thread.handle)?.cancel()
    }

    private fun clearThreadHighlight(thread: CommentThread) {
        runOnEdt {
            thread.rangeHighlighter?.dispose()
            thread.rangeHighlighter = null
        }
    }

    private fun cancelThreadPopup(thread: CommentThread) {
        runOnEdt {
            openPopups.remove(thread.handle)?.cancel()
        }
    }

    private fun openEditorDirect(uri: URI): Editor? {
        if (project.isDisposed) return null
        var editor: Editor? = null
        val application = ApplicationManager.getApplication()
        try {
            application.invokeAndWait {
                if (project.isDisposed) {
                    return@invokeAndWait
                }
                val vf = VfsUtil.findFile(Paths.get(uri.fsPath), true) ?: return@invokeAndWait
                val descriptor = OpenFileDescriptor(project, vf)
                editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, false)
            }
        } catch (e: Exception) {
            logger.warn("Failed to open editor directly for ${uri.path}", e)
        }
        return editor
    }

    private fun Map<String, Any?>.toFeatures(): CommentControllerFeatures {
        val options = this["options"] as? Map<String, Any?>
        val reactionHandler = (this["reactionHandler"] as? Boolean) ?: false
        val resourceHintsValue = this["resourceHints"] ?: this["resource"] ?: this["hints"]
        @Suppress("UNCHECKED_CAST")
        val resourceHints = resourceHintsValue as? Map<String, Any?>
        return CommentControllerFeatures(options, reactionHandler, resourceHints)
    }

    fun getController(handle: Int): CommentController? = controllers[handle]

    fun getThreadsSnapshot(): List<CommentThread> = threads.values.sortedBy { it.handle }

    fun addListener(listener: CommentManagerListener) {
        listeners.addIfAbsent(listener)
        listener.onThreadsChanged(getThreadsSnapshot())
    }

    fun removeListener(listener: CommentManagerListener) {
        listeners.remove(listener)
    }

    fun clearAllThreads() {
        if (threads.isEmpty() && openPopups.isEmpty()) {
            return
        }

        val existingThreads = threads.values.toList()
        runOnEdt {
            existingThreads.forEach { clearThreadUiDirect(it) }
            openPopups.values.forEach { it.cancel() }
            openPopups.clear()
        }

        threads.clear()
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = getThreadsSnapshot()
        runOnEdt {
            listeners.forEach { listener ->
                try {
                    listener.onThreadsChanged(snapshot)
                } catch (e: Exception) {
                    logger.warn("CommentManager listener error", e)
                }
            }
        }
    }

    private fun isRangeInvalidForDocument(range: CommentRange, document: ModelAddedData): Boolean {
        val lines = document.lines
        if (lines.isEmpty()) {
            return true
        }
        val lineCount = lines.size
        if (range.startLine !in 1..lineCount) {
            return true
        }
        if (range.endLine !in 1..lineCount) {
            return true
        }
        if (range.endLine < range.startLine) {
            return true
        }

        val startLineLength = lines[range.startLine - 1].length + 1
        val endLineLength = lines[range.endLine - 1].length + 1

        if (range.startColumn !in 1..startLineLength) {
            return true
        }
        if (range.endColumn !in 1..endLineLength) {
            return true
        }
        return false
    }

    private fun Range.toClampedCommentRange(document: ModelAddedData): CommentRange? {
        val lines = document.lines
        if (lines.isEmpty()) {
            return null
        }
        val lineCount = lines.size
        val startLine = startLineNumber.coerceIn(1, lineCount)
        val endLine = endLineNumber.coerceIn(startLine, lineCount)
        val startLineText = lines[startLine - 1]
        val endLineText = lines[endLine - 1]

        val startColumnClamped = startColumn.coerceIn(1, startLineText.length + 1)
        val endColumnClamped = endColumn.coerceIn(1, endLineText.length + 1)

        return CommentRange(startLine, startColumnClamped, endLine, endColumnClamped)
    }

    private fun rangesOverlap(first: CommentRange, second: CommentRange): Boolean {
        val firstStart = Position(first.startLine, first.startColumn)
        val firstEnd = Position(first.endLine, first.endColumn)
        val secondStart = Position(second.startLine, second.startColumn)
        val secondEnd = Position(second.endLine, second.endColumn)

        return firstStart <= secondEnd && secondStart <= firstEnd
    }

    private data class Position(val line: Int, val column: Int) : Comparable<Position> {
        override fun compareTo(other: Position): Int {
            return when {
                line != other.line -> line.compareTo(other.line)
                else -> column.compareTo(other.column)
            }
        }
    }

    private fun normalizedPath(path: String): String {
        // Normalize path for comparison: remove leading slash on Windows paths like /C:/
        return if (path.matches(Regex("^/[A-Za-z]:.*"))) {
            path.substring(1)
        } else {
            path
        }
    }

    private fun deleteThreadsForFile(fileUri: URI) {
        if (project.isDisposed) return
        
        val normalizedFilePath = normalizedPath(fileUri.path)
        logger.debug("Cleaning up UI for threads in closed file: $normalizedFilePath")
        
        // Find all threads for this file
        val threadsToCleanup = threads.values.filter { thread ->
            val threadUri = createURI(thread.resource)
            val normalizedThreadPath = normalizedPath(threadUri.path)
            normalizedThreadPath == normalizedFilePath
        }.toList()
        
        if (threadsToCleanup.isEmpty()) {
            logger.debug("No threads to cleanup for file $normalizedFilePath")
            return
        }
        
        logger.debug("Cleaning up UI for ${threadsToCleanup.size} threads in file $normalizedFilePath")
        
        // Only clean up UI, keep thread data for when file reopens
        threadsToCleanup.forEach { thread ->
            // Clean up UI (highlighters and popups)
            clearThreadUI(thread)
            // Mark thread as not visible, but don't remove it
            thread.isVisible = false
            thread.collapseState = "collapsed"
            thread.manuallyCollapsed = true
            logger.debug("Cleaned up UI for thread ${thread.threadId} (handle=${thread.handle})")
        }
        
        // Notify listeners that thread states have changed
        notifyListeners()
    }

    private fun restoreThreadsForFile(fileUri: URI) {
        if (project.isDisposed) return
        
        val normalizedFilePath = normalizedPath(fileUri.path)
        logger.debug("Restoring threads for opened file: $normalizedFilePath")
        
        // Find all threads for this file
        val threadsToRestore = threads.values.filter { thread ->
            val threadUri = createURI(thread.resource)
            val normalizedThreadPath = normalizedPath(threadUri.path)
            normalizedThreadPath == normalizedFilePath
        }.toList()
        
        if (threadsToRestore.isEmpty()) {
            logger.debug("No threads to restore for file $normalizedFilePath")
            return
        }
        
        logger.debug("Restoring ${threadsToRestore.size} threads for file $normalizedFilePath")
        
        // Restore each thread that should be visible
        threadsToRestore.forEach { thread ->
            if (thread.collapseState != "collapsed" && !thread.manuallyCollapsed) {
                logger.debug("Restoring thread ${thread.threadId} (handle=${thread.handle})")
                ensureThreadDisplayed(thread)
            } else {
                logger.debug("Skipping collapsed thread ${thread.threadId} (handle=${thread.handle})")
            }
        }
        
        // Notify listeners that thread states have changed
        notifyListeners()
    }

    private fun runOnEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeLater {
                if (!project.isDisposed) {
                    action()
                }
            }
        }
    }

    override fun dispose() {
        logger.debug("Dispose CommentManager")
        scope.cancel()

        val existingThreads = threads.values.toList()
        runOnEdt {
            existingThreads.forEach { clearThreadUiDirect(it) }
            openPopups.values.forEach { it.cancel() }
            openPopups.clear()
        }

        threads.clear()
        controllers.clear()
        controllerRanges.clear()
        notifyListeners()
        listeners.clear()
    }

    private data class EditorContext(
        val holder: EditorHolder,
        val editor: Editor
    )

    private data class ThreadCreationData(
        val commentThreadHandle: Int,
        val threadId: String,
        val controllerHandle: Int,
        val resource: Map<String, Any?>,
        val range: Map<String, Any?>?,
        val comments: List<Map<String, Any?>>,
        val extensionId: String,
        val isTemplate: Boolean,
        val editorId: String?
    )

    private fun createThreadInternal(
        data: ThreadCreationData,
        controller: CommentController?
    ): CommentThread {
        if (controller == null) {
            logger.debug("Creating comment thread ${data.commentThreadHandle} while controller ${data.controllerHandle} is pending registration.")
        }
        val commentRange = CommentRange.fromMap(data.range)
        val thread = CommentThread(
            handle = data.commentThreadHandle,
            threadId = data.threadId,
            controllerHandle = controller?.handle ?: data.controllerHandle,
            resource = data.resource,
            range = commentRange,
            extensionId = data.extensionId,
            comments = data.comments,
            isTemplate = data.isTemplate,
            editorHandleId = data.editorId
        )
        thread.collapseState = thread.collapseState ?: "expanded"
        thread.canReply = true
        thread.isTemplate = data.isTemplate
        thread.editorHandleId = data.editorId

        val shouldRender = thread.collapseState != "collapsed"

        threads[data.commentThreadHandle] = thread
        if (shouldRender) {
            ensureThreadDisplayed(thread)
        } else {
            thread.isVisible = false
        }
        return thread
    }

    private fun createThreadPopup(editor: Editor, thread: CommentThread): JBPopup {
        val content = buildPopupContent(thread)
        val title = "CodeReview 详情"
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()
    }

    private fun buildPopupContent(thread: CommentThread): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            isOpaque = false
        }
        panel.add(buildActionsHeader(thread), BorderLayout.NORTH)
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(0, 0, 0, 0)
            isOpaque = false
        }

        if (thread.comments.isEmpty()) {
            listPanel.add(JBLabel("暂无评论").apply {
                border = JBUI.Borders.empty(4, 0, 4, 0)
            })
        } else {
            thread.comments.forEachIndexed { index, comment ->
                listPanel.add(buildCommentItem(comment))
                if (index != thread.comments.lastIndex) {
                    listPanel.add(JPanel().apply {
                        border = JBUI.Borders.empty(6, 0, 6, 0)
                        isOpaque = false
                    })
                }
            }
        }

        val scrollPane = JBScrollPane(
            listPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = JBUI.size(360, 220)
        }

        panel.add(scrollPane, BorderLayout.CENTER)
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar?.let { bar ->
                bar.value = bar.minimum
            }
        }
        return panel
    }

    private fun buildActionsHeader(thread: CommentThread): JComponent {
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
        }

        actions.add(
            createActionButton(
                thread,
                ThreadAction.ACCEPT,
                AllIcons.Actions.Checked,
                "接受"
            )
        )
        actions.add(
            createActionButton(
                thread,
                ThreadAction.REJECT,
                AllIcons.General.Remove,
                "拒绝"
            )
        )
        actions.add(
            createActionButton(
                thread,
                ThreadAction.ASK,
                AllIcons.Actions.Edit,
                "修复代码"
            )
        )
        actions.add(
            createActionButton(
                thread,
                ThreadAction.CLOSE,
                AllIcons.Actions.Cancel,
                "关闭"
            )
        )

        header.add(actions, BorderLayout.EAST)
        return header
    }

    private fun createActionButton(
        thread: CommentThread,
        action: ThreadAction,
        icon: Icon,
        tooltip: String
    ): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isOpaque = false
            isContentAreaFilled = false
            isFocusPainted = false
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(24, 24)
            putClientProperty("JButton.buttonType", "square")
            addActionListener { handleThreadAction(thread, action) }
        }

    private fun buildCommentItem(comment: Map<String, Any?>): JComponent {
        val itemPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 4, 0)
        }

        val body = extractBody(comment)
        val html = renderMarkdownHtml(body)
        val bodyPane = JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = false
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(6, 0, 0, 0)
            font = UIUtil.getLabelFont()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    event.url?.let { BrowserUtil.browse(it) }
                }
            }
        }
        itemPanel.add(bodyPane)
        return itemPanel
    }

    private fun handleThreadAction(thread: CommentThread, action: ThreadAction) {
        when (action) {
            ThreadAction.CLOSE -> closeThread(thread)
            ThreadAction.ACCEPT -> {
                val id = extractCommentId(thread)
                if (id != null) {
                    executeThreadCommand(COMMAND_ACCEPT_COMMENT, mapOf("id" to id))
                    closeThread(thread)
                } else {
                    logger.warn("Accept action skipped: missing contextValue for thread ${thread.threadId}")
                }
            }
            ThreadAction.REJECT -> {
                val id = extractCommentId(thread)
                if (id != null) {
                    logger.info("Reject comment thread ${thread.threadId} (controller=${thread.controllerHandle}) with id=$id")
                    executeThreadCommand(COMMAND_REJECT_COMMENT, mapOf("id" to id))
                    closeThread(thread)
                } else {
                    logger.warn("Reject action skipped: missing contextValue for thread ${thread.threadId}")
                }
            }
            ThreadAction.ASK -> {
                val id = extractCommentId(thread)
                if (id != null) {
                    logger.info("Ask follow-up for comment thread ${thread.threadId} (controller=${thread.controllerHandle}) with id=$id")
                    executeThreadCommand(COMMAND_ASK_COMMENT, mapOf("id" to id))
                    closeThread(thread)
                } else {
                    logger.warn("Ask action skipped: missing contextValue for thread ${thread.threadId}")
                }
            }
        }
    }

    private fun closeThread(thread: CommentThread) {
        logger.info("Close comment thread ${thread.threadId} (handle=${thread.handle})")
        thread.isVisible = false
        thread.collapseState = "collapsed"
        thread.manuallyCollapsed = true
        clearThreadHighlight(thread)
        cancelThreadPopup(thread)
        notifyListeners()
    }

    private fun executeThreadCommand(commandId: String, args: Map<String, String>) {
        executeCommand(commandId, project, args)
    }

    private fun extractCommentId(thread: CommentThread): String? {
        val comment = thread.comments.firstOrNull() ?: return null
        val rawContext = comment["contextValue"]
        return when (rawContext) {
            is String -> rawContext.takeIf { it.isNotBlank() }
            is Number -> rawContext.toString()
            else -> null
        }
    }

    private fun renderMarkdownHtml(source: String): String {
        var normalized = source.ifBlank { "(暂无内容)" }.replace("\r\n", "\n")
        if (!normalized.endsWith("\n")) {
            normalized += "\n"
        }
        val innerHtml = runCatching {
            val parser = MarkdownParser(markdownFlavour)
            val parsedTree = parser.buildMarkdownTreeFromString(normalized)
            HtmlGenerator(normalized, parsedTree, markdownFlavour).generateHtml()
        }.getOrElse {
            StringUtil.escapeXmlEntities(normalized)
        }
        return buildHtmlDocument(innerHtml)
    }

    private fun buildHtmlDocument(innerContent: String): String {
        val bodyFont = UIUtil.getLabelFont()
        val foreground = ColorUtil.toHex(UIUtil.getLabelForeground())
        val background = ColorUtil.toHex(UIUtil.getPanelBackground())
        val link = ColorUtil.toHex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        val muted = ColorUtil.toHex(UIUtil.getLabelDisabledForeground())
        val codeBgColor = ColorUtil.toHex(ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.1))
        val tableBorder = ColorUtil.toHex(ColorUtil.mix(UIUtil.getLabelForeground(), UIUtil.getPanelBackground(), 0.3))
        return """
            <html>
              <head>
                <style>
                  body {
                    background-color: #$background;
                    color: #$foreground;
                    font-family: '${bodyFont.family}', sans-serif;
                    font-size: ${bodyFont.size}pt;
                    line-height: 1.5;
                    margin: 0;
                  }
                  p { margin-top: 4px; margin-bottom: 8px; }
                  ul, ol { margin-top: 4px; margin-bottom: 8px; padding-left: 20px; }
                  code {
                    background-color: #$codeBgColor;
                    padding: 0 3px;
                    font-family: 'JetBrains Mono', 'Consolas', monospace;
                  }
                  pre {
                    padding: 8px;
                    overflow-x: auto;
                    font-family: 'JetBrains Mono', 'Consolas', monospace;
                    background-color: #$codeBgColor;
                  }
                  a {
                    color: #$link;
                    text-decoration: none;
                  }
                  a:hover {
                    text-decoration: underline;
                  }
                  blockquote {
                    border-left: 3px solid #$muted;
                    margin-left: 0;
                    padding-left: 12px;
                    color: #$muted;
                  }
                  table {
                    border-collapse: collapse;
                    margin-top: 6px;
                    margin-bottom: 10px;
                    width: 100%;
                  }
                  th, td {
                    border: 1px solid #$tableBorder;
                    padding: 4px 6px;
                    text-align: left;
                  }
                </style>
              </head>
              <body>$innerContent</body>
            </html>
        """.trimIndent()
    }

    private fun extractBody(comment: Map<String, Any?>): String {
        val body = comment["body"]
        return when (body) {
            is String -> body
            is Map<*, *> -> body["value"]?.toString() ?: body.toString()
            else -> comment.toString()
        }
    }

    private fun computePopupLocation(editor: Editor, thread: CommentThread): Point {
        val logicalPosition = LogicalPosition(
            (thread.range.startLine - 1).coerceAtLeast(0),
            (thread.range.startColumn - 1).coerceAtLeast(0)
        )
        val xy = editor.logicalPositionToXY(logicalPosition)
        return Point(xy.x, xy.y + editor.lineHeight)
    }

    private enum class ThreadAction {
        CLOSE,
        ACCEPT,
        REJECT,
        ASK
    }

    private companion object {
        private const val COMMAND_ACCEPT_COMMENT = "zgsm.acceptIssueJetbrains"
        private const val COMMAND_REJECT_COMMENT = "zgsm.rejectIssueJetbrains"
        private const val COMMAND_ASK_COMMENT = "zgsm.askReviewSuggestionWithAIJetbrains"
    }
}

interface CommentManagerListener {
    fun onThreadsChanged(threads: List<CommentThread>)
}
