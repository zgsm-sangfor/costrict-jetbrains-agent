package com.sina.weibo.agent.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class WorkflowDebugRunCommandAction(private val command: String) : AnAction("Debug '$command'") {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        
        try {
            // 添加调试日志
            println("WorkflowDebugRunCommandAction: 开始执行重试任务操作")
            
            // 创建VSCode命令参数
            val params = CoworkflowCommandConverter.createRetryTaskParams(e)
            
            if (params != null) {
                println("WorkflowDebugRunCommandAction: 参数创建成功，准备执行VSCode命令")
                // 调用VSCode的retryTask命令
                CoworkflowCommandConverter.executeVSCodeCommand(
                    CoworkflowCommandConverter.VSCodeCommands.RETRY_TASK,
                    e,
                    params
                )
                println("WorkflowDebugRunCommandAction: VSCode命令执行完成")
            } else {
                println("WorkflowDebugRunCommandAction: 参数创建失败，可能不是tasks文档")
                // 回退到原有逻辑
                Messages.showInfoMessage(project, "调试命令：$command", "调试模式")
            }
        } catch (ex: Exception) {
            println("WorkflowDebugRunCommandAction: 执行重试任务操作失败 - ${ex.message}")
            ex.printStackTrace()
            // 回退到原有逻辑
            Messages.showInfoMessage(project, "调试命令：$command", "调试模式")
        }
    }
}
