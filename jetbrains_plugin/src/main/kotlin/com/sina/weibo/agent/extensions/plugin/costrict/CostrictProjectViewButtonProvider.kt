// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.extensions.plugin.costrict

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.psi.PsiElement
import com.sina.weibo.agent.actions.executeCommand
import com.sina.weibo.agent.comments.CommentManager
import com.sina.weibo.agent.extensions.ui.buttons.ExtensionButtonProvider
import com.sina.weibo.agent.extensions.ui.buttons.ButtonType
import com.sina.weibo.agent.extensions.ui.buttons.ButtonConfiguration

/**
 * Costrict project view button provider for code review functionality.
 */
class CostrictProjectViewButtonProvider : ExtensionButtonProvider {
    
    override fun getExtensionId(): String = "costrict"
    
    override fun getDisplayName(): String = "CoStrict"
    
    override fun getDescription(): String = "AI-powered code assistant for project view operations"
    
    override fun isAvailable(project: Project): Boolean {
        return true
    }
    
    override fun getButtons(project: Project): List<AnAction> {
        return listOf(CodeReviewAction())
    }
    
    override fun getButtonConfiguration(): ButtonConfiguration {
        return CostrictProjectViewButtonConfiguration()
    }
    
    private class CostrictProjectViewButtonConfiguration : ButtonConfiguration {
        override fun isButtonVisible(buttonType: ButtonType): Boolean {
            return true
        }
        
        override fun getVisibleButtons(): List<ButtonType> {
            return emptyList()
        }
    }

    /**
     * Action to perform code review on selected files (directories not supported).
     */
    class CodeReviewAction : AnAction("Code Review") {
        private val logger: Logger = Logger.getInstance(CodeReviewAction::class.java)
        
        init {
            templatePresentation.icon = AllIcons.Actions.Preview
            templatePresentation.text = "Code Review"
            templatePresentation.description = "Review code for selected files (directories not supported)"
        }
        
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            
            var virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.filter { !it.isDirectory }?.toList()
            if (virtualFiles != null && virtualFiles.isNotEmpty()) {
                logger.info("Using VIRTUAL_FILE_ARRAY: ${virtualFiles.size} file(s)")
            } else {
                virtualFiles = null
            }
            
            if (virtualFiles == null) {
                val singleFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
                if (singleFile != null && !singleFile.isDirectory) {
                    virtualFiles = listOf(singleFile)
                    logger.info("Using single VIRTUAL_FILE: ${singleFile.name}")
                }
            }
            
            if (virtualFiles == null) {
                val selectedItems = e.getData(PlatformDataKeys.SELECTED_ITEMS)
                virtualFiles = selectedItems?.mapNotNull { item ->
                    val file = when (item) {
                        is VirtualFile -> item
                        is PsiFileNode -> item.virtualFile
                        is PsiDirectoryNode -> item.virtualFile
                        is AbstractTreeNode<*> -> {
                            val value = item.value
                            when (value) {
                                is VirtualFile -> value
                                is PsiElement -> value.containingFile?.virtualFile
                                else -> null
                            }
                        }
                        else -> null
                    }
                    
                    if (file != null && file.isDirectory) {
                        null
                    } else {
                        file
                    }
                }
                
                if (virtualFiles != null) {
                    logger.info("Extracted ${virtualFiles.size} file(s) from SELECTED_ITEMS")
                }
            }
            
            if (virtualFiles == null || virtualFiles.isEmpty()) {
                logger.warn("No files selected for code review")
                return
            }
            
            val filePaths = virtualFiles.map { it.path }
            logger.info("🔍 Triggering code review for ${filePaths.size} file(s): ${filePaths.joinToString(", ")}")
            
            val args = mutableMapOf<String, Any?>()
            args["filePaths"] = filePaths
            args["fileCount"] = filePaths.size
            project.getService(CommentManager::class.java)?.clearAllThreads()
            executeCommand("costrict.reviewFilesAndFoldersJetbrains", project, args)
        }
        
        override fun update(e: AnActionEvent) {
            val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            val singleFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val selectedItems = e.getData(PlatformDataKeys.SELECTED_ITEMS)
            
            val filesFromItems = selectedItems?.mapNotNull { item ->
                val virtualFile = when (item) {
                    is VirtualFile -> item
                    is PsiFileNode -> item.virtualFile
                    is PsiDirectoryNode -> item.virtualFile
                    is AbstractTreeNode<*> -> {
                        val value = item.value
                        when (value) {
                            is VirtualFile -> value
                            is PsiElement -> value.containingFile?.virtualFile
                            else -> null
                        }
                    }
                    else -> null
                }
                
                if (virtualFile != null && virtualFile.isDirectory) {
                    null
                } else {
                    virtualFile
                }
            }
            
            val hasFiles = when {
                selectedFiles != null && selectedFiles.isNotEmpty() -> {
                    val actualFiles = selectedFiles.filter { !it.isDirectory }
                    actualFiles.isNotEmpty()
                }
                singleFile != null -> !singleFile.isDirectory
                filesFromItems != null && filesFromItems.isNotEmpty() -> true
                else -> false
            }
            
            e.presentation.isEnabled = hasFiles
        }
    }
}
