// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.extensions.plugin.costrict

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage
import com.sina.weibo.agent.actions.executeCommandWithResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Action to generate commit message using AI in the VCS commit dialog.
 * This action integrates with the VCS commit message input field.
 */
class GenerateCommitMessageAction : AnAction() {

    companion object {
        private val logger: Logger = Logger.getInstance(GenerateCommitMessageAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        logger.info("GenerateCommitMessageAction triggered")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = executeCommandWithResult("zgsm.generateCommitMessage", project)

                if (result != null) {
                    val message = result.await()

                    if (message is String && message.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                setCommitMessage(e, message)
                            }
                        }
                        logger.info("Commit message generated successfully")
                    } else {
                        logger.warn("Generated commit message is empty or not a string: $message")
                    }
                } else {
                    logger.warn("executeCommandWithResult returned null")
                }
            } catch (ex: Exception) {
                logger.error("Error generating commit message", ex)
            }
        }
    }

    /**
     * Sets the commit message in the VCS commit dialog.
     */
    private fun setCommitMessage(e: AnActionEvent, message: String) {
        try {
            // Use VcsDataKeys.COMMIT_MESSAGE_CONTROL (recommended approach)
            val commitMessage = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(e.dataContext)
            if (commitMessage is CommitMessage) {
                commitMessage.setCommitMessage(message)
                logger.info("Commit message set via VcsDataKeys.COMMIT_MESSAGE_CONTROL")
                return
            }

            logger.warn("Could not find CommitMessage component")
        } catch (ex: Exception) {
            logger.error("Error setting commit message", ex)
        }
    }

    override fun update(e: AnActionEvent) {
        // Only enable if we have a project
        e.presentation.isEnabled = e.project != null
    }
}
