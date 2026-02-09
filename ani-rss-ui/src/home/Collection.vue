<template>
  <Bgm ref="bgmRef" @callback="bgmAdd"/>
  <CollectionPreview ref="collectionPreviewRef" v-model:data="data"/>
  <CollectionMagnetDialog
      v-model="magnetDetailVisible"
      :task="magnetTask"
      :loading="magnetTaskLoading"
      :organizing="magnetTask.organizing"
      @organize="handleMagnetOrganize"
      @force-complete="handleForceComplete"
      @exit-and-cleanup="confirmExitAndCleanup"
      @cancel="cancelMagnetTask"
  />
  <el-dialog v-model="dialogVisible"
             center
             :close-on-click-modal="!isCollectionLocked"
             :close-on-press-escape="!isCollectionLocked"
             :show-close="!isCollectionLocked"
             :before-close="handleDialogBeforeClose"
             title="添加合集">
    <div v-loading="loading" style="height: 500px;">
      <el-scrollbar style="padding: 0 12px;">
        <div>
          <el-form label-width="auto"
                   @submit="(event)=>{
                event.preventDefault()
             }">
            <el-form-item label="番剧名称">
              <div style="width: 100%;">
                <div class="flex" style="width: 100%;">
                  <el-input
                      v-model:model-value="data.ani.title"
                      :disabled="rssButtonLoading"
                      placeholder="请勿留空"
                      @keyup.enter="bgmRef?.show(data.ani.title)"
                  />
                  <div style="width: 4px;"></div>
                  <el-button :disabled="rssButtonLoading" bg icon="Search" text type="primary"
                             @click="bgmRef?.show(data.ani.title)"/>
                </div>
                <div v-if="data.show" class="change-title-button">
                  <el-button :loading="getBgmNameLoading"
                             bg
                             icon="DocumentAdd" text @click="getBgmName">
                    使用Bangumi
                  </el-button>
                  <el-button :disabled="data.ani.title === data.ani.themoviedbName || !data.ani.themoviedbName.length"
                             bg
                             icon="DocumentAdd"
                             text
                             @click="data.ani.title = data.ani.themoviedbName">
                    使用TMDB
                  </el-button>
                </div>
              </div>
            </el-form-item>
            <template v-if="data.show">
              <el-form-item label="TMDB">
                <div class="flex" style="width: 100%;justify-content: space-between;">
                  <div class="el-input is-disabled">
                    <div class="el-input__wrapper"
                         style="pointer-events: auto;cursor: auto;justify-content: left;padding: 0 11px;"
                         tabindex="-1">
                      <el-link v-if="data.ani?.tmdb?.id"
                               :href="`https://www.themoviedb.org/${data.ani.ova ? 'movie' : 'tv'}/${data.ani.tmdb.id}`"
                               target="_blank"
                               type="primary">
                        {{ data.ani.themoviedbName }}
                      </el-link>
                      <span v-else>{{ data.ani.themoviedbName }}</span>
                    </div>
                  </div>
                  <div style="width: 4px;"></div>
                  <el-button :loading="getThemoviedbNameLoading" bg icon="Refresh" text @click="getThemoviedbName"/>
                </div>
              </el-form-item>
              <el-form-item label="字幕组">
                <div class="form-item-flex">
                  <el-input v-model:model-value="data.ani.subgroup" placeholder="字幕组" style="width: 150px"/>
                </div>
              </el-form-item>
              <el-form-item label="季">
                <div class="form-item-flex">
                  <el-input-number v-model:model-value="data.ani.season" :min="0" style="max-width: 200px"/>
                </div>
              </el-form-item>
              <el-form-item label="集数偏移">
                <div class="form-item-flex">
                  <el-input-number v-model:model-value="data.ani.offset"/>
                </div>
              </el-form-item>
              <el-form-item label="日期">
                <div class="form-item-flex">
                  <el-date-picker
                      v-model="date"
                      style="max-width: 150px;"
                      @change="dateChange"
                  />
                </div>
              </el-form-item>
              <el-form-item label="匹配">
                <Exclude ref="match" v-model:exclude="data.ani.match" :import-exclude="false"/>
              </el-form-item>
              <el-form-item label="排除">
                <Exclude ref="exclude" v-model:exclude="data.ani.exclude" :import-exclude="true"/>
              </el-form-item>
              <el-form-item label="全局排除">
                <el-switch v-model:model-value="data.ani['globalExclude']"/>
              </el-form-item>
              <el-form-item label="剧场版">
                <el-switch v-model:model-value="data.ani.ova"/>
              </el-form-item>
              <el-form-item label="自定义集数规则">
                <div style="display: flex;width: 100%;">
                  <el-input v-model:model-value="data.ani.customEpisodeStr"
                            style="width: 100%"/>
                  <div style="width: 4px;"></div>
                  <el-input-number v-model:model-value="data.ani.customEpisodeGroupIndex"/>
                </div>
              </el-form-item>
              <el-form-item label="下载位置">
                <div style="width: 100%;">
                  <el-input v-model:model-value="data.ani.downloadPath" :autosize="{ minRows: 2}"
                            style="width: 100%"
                            type="textarea"/>
                </div>
                <div style="margin-top: 6px;">
                  <el-button :disabled="!data.ani.customDownloadPath"
                             :loading="downloadPathLoading" bg icon="Refresh"
                             text
                             @click="downloadPath"/>
                </div>
              </el-form-item>
              <el-form-item label="自定义标签">
                <custom-tags :config="data.ani"/>
              </el-form-item>
              <!-- 下载方式选择 -->
              <el-form-item label="下载方式">
                <el-radio-group v-model="data.downloadMode">
                  <el-radio label="torrent">种子文件</el-radio>
                  <el-radio label="magnet">磁力链接</el-radio>
                </el-radio-group>
              </el-form-item>

              <!-- 种子文件模式 -->
              <template v-if="data.downloadMode === 'torrent'">
                <el-form-item label="Torrent">
                  <el-tag v-if="data.filename" closable @close="()=>{
                  data.filename = ''
                  data.torrent = ''
                }">
                    <el-tooltip :content="data.filename">
                      <el-text line-clamp="1" size="small" class="filename">
                        {{ data.filename }}
                      </el-text>
                    </el-tooltip>
                  </el-tag>
                  <el-upload
                      v-else
                      :action="`api/upload?type=getBase64&s=${authorization}`"
                      :before-upload="beforeAvatarUpload"
                      :on-success="onSuccess"
                      :show-file-list="false"
                      class="upload-demo"
                      drag
                      multiple
                      style="width: 100%"
                  >
                    <el-icon class="el-icon--upload">
                      <upload-filled/>
                    </el-icon>
                    <div class="el-upload__text">
                      在这里拖拽 .torrent 文件或 <em>点击上传</em>
                    </div>
                    <template #tip>
                      <div class="el-upload__tip flex" style="justify-content: end;">
                        .torrent 文件小于 5M
                      </div>
                    </template>
                  </el-upload>
                </el-form-item>
              </template>

              <!-- 磁力链接模式 -->
              <template v-if="data.downloadMode === 'magnet'">
                <el-form-item label="磁力链接">
                  <el-input
                      v-model="data.magnet"
                      type="textarea"
                      :rows="3"
                      placeholder="magnet:?xt=urn:btih:..."
                  />
                </el-form-item>
                <el-alert type="info" :closable="false" style="margin-bottom: 12px;">
                  <template #default>
                    <div>磁力链接将下载到 OpenList 云盘，完成后可选择保留的文件</div>
                  </template>
                </el-alert>

                <!-- 下载状态概览 -->
                <template v-if="magnetTask.taskId">
                  <el-divider/>
                  <div class="magnet-task-summary">
                    <div class="magnet-task-header">
                      <div>
                        <span class="magnet-task-label">任务状态:</span>
                        <el-tag :type="getTaskStatusType(magnetTask.status)" size="large">
                          {{ getTaskStatusText(magnetTask.status) }}
                        </el-tag>
                      </div>
                      <div>
                        <el-button
                            v-if="magnetTask.taskId && magnetTask.status !== 'completed'"
                            type="primary"
                            @click="showMagnetDetailDialog"
                        >
                          查看暂存目录
                        </el-button>
                        <el-button 
                            v-if="magnetTask.status === 'completed'" 
                            type="primary" 
                            @click="showMagnetDetailDialog"
                        >
                          查看详情并整理
                        </el-button>
                        <el-button v-if="magnetTask.status === 'downloading'" type="danger" @click="cancelMagnetTask">
                          取消任务
                        </el-button>
                      </div>
                    </div>
                    <el-progress v-if="magnetTask.status === 'downloading'" :percentage="magnetTask.progress" class="magnet-task-progress"/>
                    <div v-if="magnetTask.status === 'completed'" class="magnet-task-completed">
                      <el-icon><circle-check /></el-icon>
                      下载完成，点击下方“查看详情并整理”按钮管理文件
                    </div>
                  </div>
                </template>
              </template>
            </template>
          </el-form>
        </div>
      </el-scrollbar>
    </div>
    <div class="action">
      <!-- 种子文件模式按钮 -->
      <template v-if="data.downloadMode === 'torrent'">
        <el-button :disabled="!data.filename" bg
                   icon="Grid"
                   text
                   @click="collectionPreviewRef?.show">
          预览
        </el-button>
        <el-button :disabled="!data.filename"
                   :loading="startLoading"
                   bg
                   icon="Check"
                   text
                   type="primary" @click="start">
          开始
        </el-button>
      </template>

      <!-- 磁力链接模式按钮 -->
      <template v-if="data.downloadMode === 'magnet'">
        <el-button v-if="!magnetTask.taskId"
                   :disabled="!data.magnet"
                   :loading="magnetTask.creating"
                   bg
                   icon="Download"
                   text
                   type="primary"
                   @click="createMagnetTask">
          开始下载
        </el-button>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import {computed, onBeforeUnmount, ref, watch} from "vue";
import {UploadFilled, CircleCheck} from "@element-plus/icons-vue";
import {ElMessage, ElMessageBox} from "element-plus";
import Bgm from "./Bgm.vue";
import api from "@/js/api.js";
import Exclude from "@/config/Exclude.vue";
import CollectionPreview from "./CollectionPreview.vue";
import CollectionMagnetDialog from "./CollectionMagnetDialog.vue";
import CustomTags from "@/config/CustomTags.vue";
import {aniData} from "@/js/ani.js";
import {authorization} from "@/js/global.js";

let start = () => {
  startLoading.value = true
  api.post('api/collection?type=start', data.value)
      .then((res) => {
        ElMessageBox.confirm(
            res.message,
            'success',
            {
              confirmButtonText: 'OK',
              confirmButtonClass: 'is-text is-has-bg el-button--primary',
              type: 'success',
              center: true,
              showCancelButton: false
            }
        )
      })
      .finally(() => {
        startLoading.value = false
      })
}

let downloadPathLoading = ref(false)
let downloadPath = () => {
  downloadPathLoading.value = true
  let newAni = JSON.parse(JSON.stringify(data.value.ani))
  newAni.customDownloadPath = false
  api.post('api/downloadPath', newAni)
      .then(res => {
        data.value.ani.downloadPath = res.data.downloadPath
      })
      .finally(() => {
        downloadPathLoading.value = false
      })
}

let collectionPreviewRef = ref()

let startLoading = ref(false);

let date = ref()

let dateChange = () => {
  if (!date.value) {
    return
  }
  data.value.ani.year = date.value.getFullYear()
  data.value.ani.month = date.value.getMonth() + 1
  data.value.ani.date = date.value.getDate()
  let minYear = 1970
  if (data.value.ani.year < minYear) {
    data.value.ani.year = minYear
    init()
    ElMessage.error(`最小年份为 ${minYear}`)
  }
}

let init = () => {
  date.value = new Date(data.value.ani.year, data.value.ani.month - 1, data.value.ani.date);
}

let bgmRef = ref()

let rssButtonLoading = ref(false)
let loading = ref(false)

let bgmAdd = (bgm) => {
  loading.value = true
  data.value.show = false
  data.value.torrent = ''
  data.value.filename = ''
  api.post('api/bgm?type=getAniBySubjectId&id=' + bgm['id'])
      .then((res) => {
        data.value.ani = res.data
        data.value.ani.subgroup = '未知字幕组'
        data.value.ani.customEpisode = true
        data.value.show = true
        data.value.ani.match = []
        data.value.ani.exclude = ['^(SPs?|CDs|Scans|PV|menu)/', 'Fonts|NCED|NCOP|迷你动画']
      })
      .finally(() => {
        loading.value = false
      })
}

let onSuccess = (res) => {
  data.value.torrent = res.data
  // 获取字幕组
  api.post('api/collection?type=subgroup', data.value)
      .then(res => {
        data.value.ani.subgroup = res.data
        if (res.data !== '未知字幕组') {
          ElMessage.success(`字幕组已更新为 ${res.data}`)
        }
      })
}

let data = ref({
  filename: '',
  torrent: '',
  magnet: '',
  downloadMode: 'torrent',  // 'torrent' or 'magnet'
  ani: aniData,
  show: false,
})

// 磁力链接任务状态
let magnetTask = ref({
  taskId: '',
  status: '',  // downloading, completed, failed, organizing, finished
  progress: 0,
  files: [],
  tempFiles: [],
  ani: null,
  finalPath: '',
  tempPath: '',
  creating: false,
  organizing: false,
  pollingInterval: null,
  pollingInFlight: false,
  completedNotified: false,
  tempFilesPollingInterval: null,
  tempFilesPollingInFlight: false
})

// 磁力链接详情对话框
let magnetDetailVisible = ref(false)
let magnetTaskLoading = ref(false)

// 显示磁力链接详情对话框
let showMagnetDetailDialog = () => {
  magnetDetailVisible.value = true
  startTempFilesPolling()
}

let handleForceComplete = () => {
  if (!magnetTask.value.taskId) {
    ElMessage.warning('当前没有可处理的任务')
    return
  }
  magnetTaskLoading.value = true
  api.get(`api/collection?type=magnetForceComplete&taskId=${magnetTask.value.taskId}`)
      .then(res => {
        const task = res.data || {}
        magnetTask.value.status = task.status || 'completed'
        magnetTask.value.progress = 100
        magnetTask.value.files = task.files || []
        magnetTask.value.tempFiles = []
        magnetTask.value.tempPath = task.tempPath || magnetTask.value.tempPath
        stopTempFilesPolling()
        if (magnetTask.value.pollingInterval) {
          clearInterval(magnetTask.value.pollingInterval)
          magnetTask.value.pollingInterval = null
        }
        ElMessage.success('已跳过离线完成校验，进入整理阶段')
      })
      .catch(err => {
        ElMessage.error(err.message || '切换整理阶段失败')
      })
      .finally(() => {
        magnetTaskLoading.value = false
      })
}

// 处理整理（来自对话框）
let handleMagnetOrganize = (organizeData) => {
  magnetTask.value.organizing = true
  
  // 处理新的数据结构
  let requestData = {
    taskId: magnetTask.value.taskId,
    files: organizeData.files || organizeData,  // 向后兼容
    keepDirectoryStructure: organizeData.keepDirectoryStructure || false,
    directoryRenames: organizeData.directoryRenames || {}
  }
  
  api.post('api/collection?type=magnetOrganize', requestData).then(() => {
    ElMessage.success('整理完成')
    magnetDetailVisible.value = false
    resetMagnetTask()
  }).catch(err => {
    ElMessage.error(err.message || '整理失败')
  }).finally(() => {
    magnetTask.value.organizing = false
  })
}

// 创建磁力链接任务
let createMagnetTask = () => {
  if (!data.value.magnet || !data.value.magnet.startsWith('magnet:')) {
    ElMessage.error('请输入有效的磁力链接')
    return
  }
  
  lockAfterStartDownload.value = true
  magnetTask.value.creating = true
  api.post('api/collection?type=magnetCreate', {
    magnet: data.value.magnet,
    ani: data.value.ani
  }).then(res => {
    magnetTask.value.taskId = res.data.id
    magnetTask.value.tempPath = res.data.tempPath || ''
    ElMessage.success('创建下载任务成功')
    startPollingTaskStatus()
  }).catch(err => {
    lockAfterStartDownload.value = false
    ElMessage.error(err.message || '创建任务失败')
  }).finally(() => {
    magnetTask.value.creating = false
  })
}

// 轮询任务状态
let startPollingTaskStatus = () => {
  if (magnetTask.value.pollingInterval) {
    clearInterval(magnetTask.value.pollingInterval)
  }
  magnetTask.value.pollingInFlight = false
  magnetTask.value.completedNotified = false
  
  // 保存当前 ani 信息
  magnetTask.value.ani = data.value.ani
  magnetTask.value.finalPath = data.value.ani.downloadPath
  
  magnetTask.value.pollingInterval = setInterval(() => {
    if (magnetTask.value.pollingInFlight) {
      return
    }
    if (!magnetTask.value.taskId) {
      clearInterval(magnetTask.value.pollingInterval)
      magnetTask.value.pollingInterval = null
      return
    }
    magnetTask.value.pollingInFlight = true
    api.get(`api/collection?type=magnetStatus&taskId=${magnetTask.value.taskId}`)
      .then(res => {
        let task = res.data
        magnetTask.value.status = task.status
        magnetTask.value.progress = task.progress || 0
        magnetTask.value.tempPath = task.tempPath || magnetTask.value.tempPath
        
        if (task.status === 'completed' && task.files) {
          magnetTask.value.files = task.files
          magnetTask.value.tempFiles = []
          // 保留 ani 信息（后端返回的可能没有）
          if (!magnetTask.value.ani) {
            magnetTask.value.ani = data.value.ani
          }
          clearInterval(magnetTask.value.pollingInterval)
          magnetTask.value.pollingInterval = null
          stopTempFilesPolling()
          if (!magnetTask.value.completedNotified) {
            magnetTask.value.completedNotified = true
            ElMessage.success('下载完成，点击"查看详情并整理"按钮管理文件')
          }
        } else if (task.status === 'failed') {
          clearInterval(magnetTask.value.pollingInterval)
          magnetTask.value.pollingInterval = null
          stopTempFilesPolling()
          lockAfterStartDownload.value = false
          ElMessage.error(task.error || '下载失败')
        }
      })
      .finally(() => {
        magnetTask.value.pollingInFlight = false
      })
  }, 5000)  // 每5秒轮询一次
}

// 取消磁力链接任务
let cancelMagnetTask = () => {
  if (magnetTask.value.pollingInterval) {
    clearInterval(magnetTask.value.pollingInterval)
  }
  stopTempFilesPolling()
  
  return api.get(`api/collection?type=magnetCancel&taskId=${magnetTask.value.taskId}`)
    .then(() => {
      ElMessage.success('任务已取消')
      resetMagnetTask()
    })
}

// 重置磁力链接任务状态
let confirmExitAndCleanup = () => {
  if (!magnetTask.value.taskId) {
    ElMessage.warning('当前没有可退出的任务')
    return
  }

  ElMessageBox.confirm(
      '退出后将删除当前任务临时文件，且无法恢复，是否继续？',
      '确认退出',
      {
        confirmButtonText: '继续',
        cancelButtonText: '取消',
        type: 'warning'
      }
  ).then(() => {
    ElMessageBox.confirm(
        '请再次确认：将退出合集下载管理并删除临时文件。',
        '二次确认',
        {
          confirmButtonText: '确认删除并退出',
          cancelButtonText: '返回',
          type: 'error'
        }
    ).then(() => {
      cancelMagnetTask().then(() => {
        magnetDetailVisible.value = false
        dialogVisible.value = false
      })
    })
  }).catch(() => {
    // user canceled
  })
}

let resetMagnetTask = () => {
  if (magnetTask.value.pollingInterval) {
    clearInterval(magnetTask.value.pollingInterval)
  }
  stopTempFilesPolling()
  lockAfterStartDownload.value = false
  magnetTask.value = {
    taskId: '',
    status: '',
    progress: 0,
    files: [],
    tempFiles: [],
    ani: null,
    finalPath: '',
    tempPath: '',
    creating: false,
    organizing: false,
    pollingInterval: null,
    pollingInFlight: false,
    completedNotified: false,
    tempFilesPollingInterval: null,
    tempFilesPollingInFlight: false
  }
}

let fetchTempFiles = () => {
  if (!magnetTask.value.taskId || magnetTask.value.status !== 'downloading') {
    return
  }
  if (magnetTask.value.tempFilesPollingInFlight) {
    return
  }
  magnetTask.value.tempFilesPollingInFlight = true
  api.get(`api/collection?type=magnetTempFiles&taskId=${magnetTask.value.taskId}`)
      .then(res => {
        magnetTask.value.tempFiles = mergeTempFilesExpandState(
            magnetTask.value.tempFiles || [],
            res.data || []
        )
      })
      .finally(() => {
        magnetTask.value.tempFilesPollingInFlight = false
      })
}

let mergeTempFilesExpandState = (oldNodes, newNodes) => {
  let expandedMap = new Map()
  let walkOld = (nodes) => {
    for (let node of (nodes || [])) {
      if (node.isDir) {
        expandedMap.set(node.path, !!node.expanded)
      }
      if (node.children && node.children.length) {
        walkOld(node.children)
      }
    }
  }
  walkOld(oldNodes)

  let walkNew = (nodes) => {
    for (let node of (nodes || [])) {
      if (node.isDir) {
        if (expandedMap.has(node.path)) {
          node.expanded = expandedMap.get(node.path)
        } else if (typeof node.expanded !== 'boolean') {
          node.expanded = false
        }
      }
      if (node.children && node.children.length) {
        walkNew(node.children)
      }
    }
  }
  walkNew(newNodes)
  return newNodes
}

let startTempFilesPolling = () => {
  stopTempFilesPolling()
  if (!magnetTask.value.taskId || magnetTask.value.status !== 'downloading' || !magnetDetailVisible.value) {
    return
  }
  fetchTempFiles()
  magnetTask.value.tempFilesPollingInterval = setInterval(() => {
    fetchTempFiles()
  }, 6000)
}

let stopTempFilesPolling = () => {
  if (magnetTask.value.tempFilesPollingInterval) {
    clearInterval(magnetTask.value.tempFilesPollingInterval)
    magnetTask.value.tempFilesPollingInterval = null
  }
  magnetTask.value.tempFilesPollingInFlight = false
}

// 获取状态标签类型
let getTaskStatusType = (status) => {
  switch (status) {
    case 'downloading': return 'primary'
    case 'completed': return 'success'
    case 'failed': return 'danger'
    case 'organizing': return 'warning'
    case 'finished': return 'success'
    default: return 'info'
  }
}

// 获取状态文本
let getTaskStatusText = (status) => {
  switch (status) {
    case 'downloading': return '下载中'
    case 'completed': return '下载完成'
    case 'failed': return '下载失败'
    case 'organizing': return '整理中'
    case 'finished': return '已完成'
    default: return '等待中'
  }
}

// 判断是否为视频文件
let isVideoFile = (filename) => {
  let ext = filename.split('.').pop().toLowerCase()
  return ['mkv', 'mp4', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm2ts', 'ts'].includes(ext)
}

// 格式化文件大小
let formatFileSize = (bytes) => {
  if (!bytes || bytes === 0) return '0 B'
  let k = 1024
  let sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

// 获取文件扩展名
let getFileExt = (filename) => {
  return filename.split('.').pop() || ''
}

let beforeAvatarUpload = (rawFile) => {
  data.value.filename = rawFile.name
  if (!rawFile.name.includes('.torrent')) {
    ElMessage.error('Avatar picture must be .torrent format!')
    return false
  }
  if (rawFile.size / 1024 / 1024 > 10) {
    ElMessage.error('Avatar picture size can not exceed 10MB!')
    return false
  }
  return true
}

let dialogVisible = ref(false)
let lockAfterStartDownload = ref(false)

const isCollectionLocked = computed(() => {
  if (!dialogVisible.value) return false
  if (lockAfterStartDownload.value) return true
  if (magnetDetailVisible.value) return true
  if (magnetTask.value.taskId) return true
  if (magnetTask.value.status && magnetTask.value.status !== 'failed') return true
  if (startLoading.value || magnetTask.value.creating || magnetTask.value.organizing) return true
  return ['waiting', 'downloading', 'completed', 'organizing', 'finished'].includes(magnetTask.value.status)
})

const handleDialogBeforeClose = (done) => {
  if (isCollectionLocked.value) {
    ElMessage.warning('合集任务进行中，请先完成当前流程')
    return
  }
  done()
}

const handleBeforeUnload = (event) => {
  if (!isCollectionLocked.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(isCollectionLocked, (locked) => {
  if (locked) {
    window.addEventListener('beforeunload', handleBeforeUnload)
    return
  }
  window.removeEventListener('beforeunload', handleBeforeUnload)
}, {immediate: true})

watch(dialogVisible, (visible, oldVisible) => {
  if (!visible && oldVisible && isCollectionLocked.value) {
    dialogVisible.value = true
    ElMessage.warning('合集任务进行中，当前窗口已锁定')
  }
})

watch([magnetDetailVisible, () => magnetTask.value.status, () => magnetTask.value.taskId], () => {
  if (magnetDetailVisible.value && magnetTask.value.status === 'downloading' && magnetTask.value.taskId) {
    startTempFilesPolling()
    return
  }
  stopTempFilesPolling()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  stopTempFilesPolling()
})

let show = () => {
  init()
  data.value.show = false
  data.value.ani.title = ''
  data.value.torrent = ''
  data.value.filename = ''
  data.value.magnet = ''
  data.value.downloadMode = 'torrent'
  resetMagnetTask()
  dialogVisible.value = true
}

let getBgmNameLoading = ref(false)

let getBgmName = () => {
  getBgmNameLoading.value = true
  api.post('api/bgm?type=getTitle', data.value.ani)
      .then(res => {
        data.value.ani.title = res.data
      })
      .finally(() => {
        getBgmNameLoading.value = false
      })
}

let getThemoviedbNameLoading = ref(false)

let getThemoviedbName = () => {
  if (!data.value.ani.title.length) {
    return
  }

  getThemoviedbNameLoading.value = true
  api.post('api/tmdb?method=getThemoviedbName', data.value.ani)
      .then(res => {
        ElMessage.success(res.message)
        data.value.ani['themoviedbName'] = res.data['themoviedbName']
        data.value.ani['tmdb'] = res.data['tmdb']
      })
      .finally(() => {
        getThemoviedbNameLoading.value = false
      })
}


defineExpose({show})

</script>

<style scoped>
.change-title-button {
  width: 100%;
  justify-content: end;
  display: flex;
  margin-top: 12px;
}

.form-item-flex {
  width: 100%;
  display: flex;
  justify-content: end;
}

.filename {
  max-width: 300px;
  color: var(--el-color-info);
}

.action {
  width: 100%;
  display: flex;
  justify-content: space-between;
}

.video-file {
  color: var(--el-color-primary);
  font-weight: 500;
}

.magnet-task-summary {
  margin-bottom: 12px;
  padding: 12px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.magnet-task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.magnet-task-label {
  margin-right: 10px;
}

.magnet-task-progress {
  margin-top: 12px;
}

.magnet-task-completed {
  margin-top: 10px;
  color: var(--el-color-success);
}
</style>
