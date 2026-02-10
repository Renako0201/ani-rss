<template>
  <el-dialog v-model="dialogVisible" center title="Rclone 同步任务">
    <div class="sync-tasks-container">
      <div class="sync-tasks-header">
        <div class="sync-refresh-state" :class="{ active: refreshing }">
          <el-icon v-if="refreshing" class="is-loading">
            <Loading />
          </el-icon>
          <span>{{ refreshing ? "刷新中" : "已刷新" }}</span>
        </div>
        <el-button text bg type="danger" :disabled="refreshing" @click="clearFinished">清理已结束</el-button>
      </div>

      <el-empty
        v-if="loadedOnce && !tasks.length"
        description="当前无同步任务"
        class="sync-tasks-empty"
      />

      <el-scrollbar v-else class="sync-tasks-scrollbar">
        <el-card v-for="task in tasks" :key="task.id" shadow="never" class="sync-task-card">
          <div class="sync-task-title">
            <el-tag :type="taskTagType(task.status)">{{ task.status }}</el-tag>
            <span class="sync-task-name">{{ task.title }}</span>
            <span class="sync-task-mode">{{ task.mode }}</span>
          </div>
          <el-progress
            :percentage="calcPercent(task)"
            :status="task.status === 'failed' ? 'exception' : (task.status === 'success' ? 'success' : '')"
          />
          <div class="sync-task-meta">
            <div>源: {{ task.srcFs }}</div>
            <div>目标: {{ task.dstFs }}</div>
            <div>jobId: {{ task.jobId || "-" }} | 任务组: {{ task.group || "-" }} | 速度: {{ toSpeed(task.speed) }} | 错误: {{ task.errors || 0 }}</div>
            <div>VPS: ↓ {{ toSpeed(task.netRxBps) }} | ↑ {{ toSpeed(task.netTxBps) }} {{ task.netIface ? `(${task.netIface})` : "" }}</div>
            <div>{{ task.message || "" }}</div>
          </div>
        </el-card>
      </el-scrollbar>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref } from "vue";
import { Loading } from "@element-plus/icons-vue";
import api from "@/js/api.js";
import { ElMessage } from "element-plus";

const dialogVisible = ref(false)
const refreshing = ref(false)
const loadedOnce = ref(false)
const tasks = ref([])

const show = () => {
  dialogVisible.value = true
  loopLoad()
}

const clearFinished = () => {
  api.del('api/rcloneSyncTasks')
    .then((res) => {
      ElMessage.success(res.message)
      return loadTasks()
    })
}

const loadTasks = async () => {
  refreshing.value = true
  try {
    const res = await api.get('api/rcloneSyncTasks?refresh=true')
    tasks.value = res.data || []
    loadedOnce.value = true
  } finally {
    refreshing.value = false
  }
}

const loopLoad = async () => {
  while (dialogVisible.value) {
    try {
      await loadTasks()
    } catch (_) {
    }
    await sleep(3000)
  }
}

const calcPercent = (task) => {
  const total = task.totalBytes || 0
  const bytes = task.bytes || 0
  if (!total) return task.status === 'success' ? 100 : 0
  const p = Math.floor((bytes / total) * 100)
  return Math.max(0, Math.min(100, p))
}

const toSpeed = (speed) => {
  speed = speed || 0
  if (speed < 1024) return `${speed} B/s`
  if (speed < 1024 * 1024) return `${(speed / 1024).toFixed(1)} KB/s`
  return `${(speed / 1024 / 1024).toFixed(1)} MB/s`
}

const taskTagType = (status) => {
  if (status === 'success') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'running') return 'primary'
  return 'info'
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

defineExpose({ show })
</script>

<style scoped>
.sync-tasks-container {
  height: 500px;
  display: flex;
  flex-direction: column;
}

.sync-tasks-header {
  margin-bottom: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.sync-refresh-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  opacity: 0.65;
}

.sync-refresh-state.active {
  opacity: 0.95;
}

.sync-tasks-scrollbar {
  flex: 1;
}

.sync-tasks-empty {
  flex: 1;
}

.sync-task-card {
  margin-bottom: 8px;
}

.sync-task-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.sync-task-name {
  font-weight: 600;
}

.sync-task-mode {
  opacity: 0.8;
}

.sync-task-meta {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
}
</style>
