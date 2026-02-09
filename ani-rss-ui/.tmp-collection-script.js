
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
    ElMessage.error(`鏈€灏忓勾浠戒负 ${minYear}`)
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
        data.value.ani.exclude = ['^(SPs?|CDs|Scans|PV|menu)/', 'Fonts|NCED|NCOP|杩蜂綘鍔ㄧ敾']
      })
      .finally(() => {
        loading.value = false
      })
}

let onSuccess = (res) => {
  data.value.torrent = res.data
  // 鑾峰彇瀛楀箷缁?
  api.post('api/collection?type=subgroup', data.value)
      .then(res => {
        data.value.ani.subgroup = res.data
        if (res.data !== '未知字幕组') {
          ElMessage.success(`瀛楀箷缁勫凡鏇存柊涓?${res.data}`)
        }
      })
}

let data = ref({
  filename: '',
  torrent: '',
  magnet: '',
  downloadMode: 'torrent',  // 'torrent' 鎴?'magnet'
  ani: aniData,
  show: false,
})

// 纾佸姏閾炬帴浠诲姟鐘舵€?
let magnetTask = ref({
  taskId: '',
  status: '',  // downloading, completed, failed, organizing, finished
  progress: 0,
  files: [],
  ani: null,
  finalPath: '',
  creating: false,
  organizing: false,
  pollingInterval: null,
  pollingInFlight: false,
  completedNotified: false
})

// 纾佸姏閾炬帴璇︽儏瀵硅瘽妗?
let magnetDetailVisible = ref(false)
let magnetTaskLoading = ref(false)

// 鏄剧ず纾佸姏閾炬帴璇︽儏瀵硅瘽妗?
let showMagnetDetailDialog = () => {
  magnetDetailVisible.value = true
}

// 澶勭悊鏁寸悊锛堟潵鑷璇濇锛?
let handleMagnetOrganize = (organizeData) => {
  magnetTask.value.organizing = true
  
  // 澶勭悊鏂扮殑鏁版嵁缁撴瀯
  let requestData = {
    taskId: magnetTask.value.taskId,
    files: organizeData.files || organizeData,  // 鍚戝悗鍏煎
    keepDirectoryStructure: organizeData.keepDirectoryStructure || false,
    directoryRenames: organizeData.directoryRenames || {}
  }
  
  api.post('api/collection?type=magnetOrganize', requestData).then(() => {
    ElMessage.success('鏁寸悊瀹屾垚')
    magnetDetailVisible.value = false
    resetMagnetTask()
  }).catch(err => {
    ElMessage.error(err.message || '鏁寸悊澶辫触')
  }).finally(() => {
    magnetTask.value.organizing = false
  })
}

// 鍒涘缓纾佸姏閾炬帴浠诲姟
let createMagnetTask = () => {
  if (!data.value.magnet || !data.value.magnet.startsWith('magnet:')) {
    ElMessage.error('璇疯緭鍏ユ湁鏁堢殑纾佸姏閾炬帴')
    return
  }
  
  lockAfterStartDownload.value = true
  magnetTask.value.creating = true
  api.post('api/collection?type=magnetCreate', {
    magnet: data.value.magnet,
    ani: data.value.ani
  }).then(res => {
    magnetTask.value.taskId = res.data.id
    ElMessage.success('鍒涘缓涓嬭浇浠诲姟鎴愬姛')
    startPollingTaskStatus()
  }).catch(err => {
    lockAfterStartDownload.value = false
    ElMessage.error(err.message || '鍒涘缓浠诲姟澶辫触')
  }).finally(() => {
    magnetTask.value.creating = false
  })
}

// 杞浠诲姟鐘舵€?
let startPollingTaskStatus = () => {
  if (magnetTask.value.pollingInterval) {
    clearInterval(magnetTask.value.pollingInterval)
  }
  magnetTask.value.pollingInFlight = false
  magnetTask.value.completedNotified = false
  
  // 淇濆瓨褰撳墠ani淇℃伅
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
        
        if (task.status === 'completed' && task.files) {
          magnetTask.value.files = task.files
          // 淇濈暀ani淇℃伅锛堝悗绔繑鍥炵殑鍙兘娌℃湁锛?
          if (!magnetTask.value.ani) {
            magnetTask.value.ani = data.value.ani
          }
          clearInterval(magnetTask.value.pollingInterval)
          magnetTask.value.pollingInterval = null
          if (!magnetTask.value.completedNotified) {
            magnetTask.value.completedNotified = true
            ElMessage.success('涓嬭浇瀹屾垚锛岀偣鍑?鏌ョ湅璇︽儏骞舵暣鐞?鎸夐挳绠＄悊鏂囦欢')
          }
        } else if (task.status === 'failed') {
          clearInterval(magnetTask.value.pollingInterval)
          magnetTask.value.pollingInterval = null
          lockAfterStartDownload.value = false
          ElMessage.error(task.error || '涓嬭浇澶辫触')
        }
      })
      .finally(() => {
        magnetTask.value.pollingInFlight = false
      })
  }, 5000)  // 姣?绉掕疆璇竴娆?}

// 鍙栨秷纾佸姏閾炬帴浠诲姟
let cancelMagnetTask = () => {
  if (magnetTask.value.pollingInterval) {
    clearInterval(magnetTask.value.pollingInterval)
  }
  
  return api.get(`api/collection?type=magnetCancel&taskId=${magnetTask.value.taskId}`)
    .then(() => {
      ElMessage.success('任务已取消')
      resetMagnetTask()
    })
}

// 閲嶇疆纾佸姏閾炬帴浠诲姟鐘舵€?
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
        '浜屾纭',
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
  lockAfterStartDownload.value = false
  magnetTask.value = {
    taskId: '',
    status: '',
    progress: 0,
    files: [],
    ani: null,
    finalPath: '',
    creating: false,
    organizing: false,
    pollingInterval: null,
    pollingInFlight: false,
    completedNotified: false
  }
}

// 鑾峰彇鐘舵€佹爣绛剧被鍨?
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

// 鑾峰彇鐘舵€佹枃鏈?
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

// 鍒ゆ柇鏄惁涓鸿棰戞枃浠?
let isVideoFile = (filename) => {
  let ext = filename.split('.').pop().toLowerCase()
  return ['mkv', 'mp4', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm2ts', 'ts'].includes(ext)
}

// 鏍煎紡鍖栨枃浠跺ぇ灏?
let formatFileSize = (bytes) => {
  if (!bytes || bytes === 0) return '0 B'
  let k = 1024
  let sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

// 鑾峰彇鏂囦欢鎵╁睍鍚?
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
    ElMessage.warning('鍚堥泦浠诲姟杩涜涓紝璇峰厛瀹屾垚褰撳墠娴佺▼')
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

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
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

