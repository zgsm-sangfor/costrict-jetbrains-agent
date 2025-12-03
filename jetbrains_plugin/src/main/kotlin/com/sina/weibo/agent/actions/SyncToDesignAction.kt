// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * 同步需求到设计的操作类
 */
class SyncToDesignAction(private val command: String = "同步需求到设计") : AnAction(command) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        
        try {
            // 创建VSCode命令参数
            val params = CoworkflowCommandConverter.createUpdateSectionParams(e)
            
            if (params != null) {
                // 调用VSCode的updateSection命令
                CoworkflowCommandConverter.executeVSCodeCommand(
                    CoworkflowCommandConverter.VSCodeCommands.UPDATE_SECTION,
                    e,
                    params
                )
            } else {
                // 回退到原有逻辑
                Messages.showInfoMessage(project, "正在同步需求变更至设计文档", "同步中")
            }
        } catch (ex: Exception) {
            println("SyncToDesignAction: 执行同步操作失败 - ${ex.message}")
            ex.printStackTrace()
            // 回退到原有逻辑
            Messages.showInfoMessage(project, "正在同步需求变更至设计文档", "同步中")
        }
    }
}