package com.oroncal.semaphoreui.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreInfo(
    val version: String? = null,
    val ansible: String? = null,
    val web_host: String? = null,
    val use_remote_runner: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreProject(
    val id: Int,
    val name: String,
    val created: String? = null,
    val alert: Boolean? = null,
    val alert_chat: String? = null,
    val max_parallel_tasks: Int? = null,
    val type: String? = null,
) {
    override fun toString(): String = name
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreTaskParams(
    val debug: Boolean? = null,
    val dry_run: Boolean? = null,
    val diff: Boolean? = null,
    val tags: List<String>? = null,
    val skip_tags: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreSurveyVarValue(
    val name: String? = null,
    val value: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreSurveyVar(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null,
    val required: Boolean? = null,
    val values: List<SemaphoreSurveyVarValue>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreTemplate(
    val id: Int,
    val project_id: Int,
    val inventory_id: Int? = null,
    val repository_id: Int? = null,
    val environment_id: Int? = null,
    val name: String,
    val playbook: String? = null,
    val arguments: String? = null,
    val description: String? = null,
    val allow_override_args_in_task: Boolean? = null,
    val view_id: Int? = null,
    val app: String? = null,
    val git_branch: String? = null,
    val survey_vars: List<SemaphoreSurveyVar>? = null,
    val task_params: SemaphoreTaskDefaults? = null,
    val last_task: SemaphoreTask? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreView(
    val id: Int,
    val title: String? = null,
    val project_id: Int? = null,
    val position: Int? = null,
    val hidden: Boolean? = null,
    val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreTaskDefaults(
    val environment: String? = null,
    val git_branch: String? = null,
    val message: String? = null,
    val arguments: String? = null,
    val params: SemaphoreTaskParams? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreTask(
    val id: Int,
    val template_id: Int? = null,
    val status: String? = null,
    val playbook: String? = null,
    val environment: String? = null,
    val secret: String? = null,
    val arguments: String? = null,
    val git_branch: String? = null,
    val message: String? = null,
    val inventory_id: Int? = null,
    val limit: String? = null,
    val params: SemaphoreTaskParams? = null,
    val user_id: Int? = null,
    val schedule_id: Int? = null,
    val integration_id: Int? = null,
    val created: String? = null,
    val start: String? = null,
    val end: String? = null,
    val version: String? = null,
    val user_name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreTaskOutput(
    val task_id: Int,
    val time: String? = null,
    val output: String? = null,
)

data class SemaphoreConnectionStatus(
    val ping: String,
    val info: SemaphoreInfo,
)

data class SemaphoreRunTaskRequest(
    val template_id: Int,
    val debug: Boolean? = null,
    val dry_run: Boolean? = null,
    val diff: Boolean? = null,
    val limit: String? = null,
    val git_branch: String? = null,
    val message: String? = null,
    val arguments: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SemaphoreRealtimeMessage(
    val type: String? = null,
    val project_id: Int? = null,
    val template_id: Int? = null,
    val task_id: Int? = null,
    val status: String? = null,
    val start: String? = null,
    val end: String? = null,
    val version: String? = null,
    val output: String? = null,
    val time: String? = null,
)
