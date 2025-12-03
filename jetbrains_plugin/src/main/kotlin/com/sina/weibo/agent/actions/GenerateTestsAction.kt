// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * 生成测试用例的操作类
 */
class GenerateTestsAction(private val command: String = "生成测试用例") : AnAction(command) {
    override fun actionPerformed(e: AnActionEvent) {
        println("GenerateTestsAction: 开始执行 actionPerformed")
        val project: Project = e.project ?: run {
            println("GenerateTestsAction: 项目为空，返回")
            return
        }
        
        try {
            // 创建VSCode命令参数
            val params = CoworkflowCommandConverter.createRunTestParams(e)
            
            if (params != null) {
                // 调用VSCode的runTest命令
                CoworkflowCommandConverter.executeVSCodeCommand(
                    CoworkflowCommandConverter.VSCodeCommands.RUN_TEST,
                    e,
                    params
                )
                println("GenerateTestsAction: VSCode命令执行完成")
            } else {
                // 回退到原有逻辑
                println("GenerateTestsAction: 显示信息对话框")
                Messages.showInfoMessage(project, "正在生成测试用例", "生成中")
            }
        } catch (ex: Exception) {
            println("GenerateTestsAction: 执行生成测试用例操作失败 - ${ex.message}")
            ex.printStackTrace()
            // 回退到原有逻辑
            println("GenerateTestsAction: 显示信息对话框")
            Messages.showInfoMessage(project, "正在生成测试用例", "生成中")
        }
        
        println("GenerateTestsAction: 测试用例生成完成")
    }
}