// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.sina.weibo.agent.extensions.plugin.costrict.CostrictFileConstants

/**
 * 运行单个任务的操作类
 * 继承 WorkflowActionBase，实现运行单个任务的逻辑
 */
class RetryTaskAction : WorkflowActionBase {
    
    // 预定义的行号（从 TasksFileHandler 传入），用于确保按钮点击时使用正确的行号
    private var predefinedLineNumber: Int? = null
    
    /**
     * 主构造函数
     */
    constructor() : super(
        actionName = "重试",
        rpcCommand = "costrict.coworkflow.retryTaskJetbrains",
        actionType = "retry"
    )
    
    /**
     * 辅助构造函数，用于从 TasksFileHandler 创建带预定义行号的实例
     * @param lineNumber 0-based 行号
     */
    constructor(lineNumber: Int) : super(
        actionName = "重试",
        rpcCommand = "costrict.coworkflow.retryTaskJetbrains",
        actionType = "retry"
    ) {
        this.predefinedLineNumber = lineNumber
    }
    
    override fun collectParams(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): WorkflowActionParams {
        val document = editor.document
        
        // 使用预定义的行号（从 TasksFileHandler 传入）或从当前光标位置获取
        val lineNumber = predefinedLineNumber?.let { it + 1 } ?: run {
            val caretModel = editor.caretModel
            document.getLineNumber(caretModel.offset) + 1
        }
        
        logger.info("RetryTaskAction: 使用行号 $lineNumber, 预定义行号: $predefinedLineNumber")
        
        // 获取当前行的任务文本
        val taskText = getTaskText(editor, psiFile, lineNumber)
        if (taskText.isNullOrBlank()) {
            logger.warn("RetryTaskAction: 未找到任务文本")
            throw IllegalStateException("未找到任务文本，请确保光标位于任务行上")
        }
        
        logger.info("RetryTaskAction: 获取到任务文本长度: ${taskText.length}, 前100字符: ${taskText.take(100)}")
        
        // 获取任务状态
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
        val taskStatus = getTaskStatus(lineText)
        
        logger.info("RetryTaskAction: 行文本: $lineText")
        logger.info("RetryTaskAction: 任务状态: $taskStatus")
        
        // 检查是否为第一个任务
        val isFirstTask = isFirstTask(editor, psiFile, lineNumber)
        
        // 获取选中文本（用于传递给 VSCode 扩展）
        val selectionModel = editor.selectionModel
        val selectedText = if (selectionModel.hasSelection()) {
            selectionModel.selectedText
        } else {
            // 如果没有选中文本，使用任务文本作为 selectedText
            taskText
        }
        
        val startLine = if (selectionModel.hasSelection()) {
            document.getLineNumber(selectionModel.selectionStart) + 1
        } else {
            lineNumber
        }
        
        val endLine = if (selectionModel.hasSelection()) {
            document.getLineNumber(selectionModel.selectionEnd) + 1
        } else {
            lineNumber
        }
        
        return WorkflowActionParams(
            filePath = virtualFile.path,
            documentType = "tasks",
            lineNumber = lineNumber,
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
            taskText = taskText,
            taskStatus = taskStatus,
            isFirstTask = isFirstTask,
            actionType = "retry"
        )
    }
}
