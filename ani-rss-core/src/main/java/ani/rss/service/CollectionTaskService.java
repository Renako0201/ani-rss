package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.download.OpenList;
import ani.rss.entity.*;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.RenameUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 合集下载任务管理
 */
@Slf4j
public class CollectionTaskService {

    private static final Map<String, CollectionTask> TASK_MAP = new ConcurrentHashMap<>();
    private static final long TASK_EXPIRE_TIME = 24 * 60 * 60 * 1000;
    private static final int OPENLIST_BATCH_SIZE = 100;
    private static final long OPENLIST_BATCH_PAUSE_MS = 100;

    /**
     * 创建磁力链接合集下载任务
     */
    public static CollectionTask createMagnetTask(String magnet, Ani ani) {
        Assert.isTrue(magnet.startsWith("magnet:"), "无效的磁力链接格式");

        String taskId = IdUtil.simpleUUID();
        
        // 使用用户下载路径下的 .collection-temp 作为临时目录
        // 避免在根目录创建目录，因为 OpenList 的存储结构可能不支持
        String downloadPath = ani.getDownloadPath();
        if (StrUtil.isBlank(downloadPath)) {
            downloadPath = ConfigUtil.CONFIG.getDownloadPathTemplate();
        }
        // Windows 路径处理
        downloadPath = ReUtil.replaceAll(downloadPath, "^[A-z]:", "");
        
        // 临时路径：下载路径/.collection-temp/{taskId}
        String tempPath = downloadPath + "/.collection-temp/" + taskId;

        Config config = ConfigUtil.CONFIG;
        OpenList openList = new OpenList();
        Assert.isTrue(openList.login(config), "OpenList 登录失败");

        CollectionTask task = new CollectionTask()
                .setId(taskId)
                .setMagnet(magnet)
                .setTempPath(tempPath)
                .setFinalPath(downloadPath)
                .setStatus("downloading")
                .setAni(ani)
                .setCreateTime(System.currentTimeMillis())
                .setProgress(0);

        try {
            String tid = openList.addMagnetOfflineDownload(magnet, tempPath);
            task.setTid(tid);
            log.info("创建合集下载任务成功: {}, tid: {}, 临时路径: {}", taskId, tid, tempPath);
        } catch (Exception e) {
            log.error("创建离线下载任务失败", e);
            task.setStatus("failed");
            task.setError(e.getMessage());
        }

        TASK_MAP.put(taskId, task);
        return task;
    }

    /**
     * 获取任务状态
     */
    public static CollectionTask getTask(String taskId) {
        CollectionTask task = TASK_MAP.get(taskId);
        if (task == null) return null;

        if (System.currentTimeMillis() - task.getCreateTime() > TASK_EXPIRE_TIME) {
            TASK_MAP.remove(taskId);
            return null;
        }

        if ("downloading".equals(task.getStatus()) && StrUtil.isNotBlank(task.getTid())) {
            updateTaskStatus(task);
        }

        return task;
    }

    /**
     * 更新任务状态
     */
    private static void updateTaskStatus(CollectionTask task) {
        Config config = ConfigUtil.CONFIG;
        OpenList openList = new OpenList();
        if (!openList.login(config)) return;

        try {
            JsonObject taskInfo = openList.taskInfo(task.getTid());
            int state = taskInfo.get("state").getAsInt();
            String error = taskInfo.has("error") ? taskInfo.get("error").getAsString() : "";

            Integer progress = openList.getTaskProgress(task.getTid());
            task.setProgress(progress);

            switch (state) {
                case 0, 1, 5 -> task.setStatus("downloading");
                case 2 -> {
                    task.setProgress(100);
                    // 先构建文件树，再标记为完成
                    if (task.getFiles() == null) {
                        try {
                            List<CollectionFile> files = buildFileTree(task.getTempPath(), task.getAni());
                            task.setFiles(files);
                            log.info("文件树构建完成，任务ID: {}", task.getId());
                        } catch (Exception e) {
                            log.error("构建文件树失败，任务ID: {}", task.getId(), e);
                            task.setStatus("failed");
                            task.setError("构建文件树失败: " + e.getMessage());
                            return;
                        }
                    }
                    // 文件树构建完成后再标记为已完成
                    task.setStatus("completed");
                }
                case 3 -> {
                    task.setStatus("failed");
                    task.setError("任务已取消");
                }
                case 4 -> {
                    task.setStatus("failed");
                    task.setError(StrUtil.blankToDefault(error, "下载失败"));
                }
                default -> {
                    if (state > 5) {
                        task.setStatus("failed");
                        task.setError(StrUtil.blankToDefault(error, "下载失败，重试次数超限"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("更新任务状态失败 {}", task.getId(), e);
        }
    }

    /**
     * 构建文件树（树形结构）
     */
    public static List<CollectionFile> buildFileTree(String path, Ani ani) {
        Config config = ConfigUtil.CONFIG;
        OpenList openList = new OpenList();
        if (!openList.login(config)) return List.of();

        // 创建根节点
        CollectionFile root = new CollectionFile()
                .setPath(path)
                .setName(FileUtil.getName(path))
                .setIsDir(true)
                .setSelected(true)
                .setLevel(0)
                .setExpanded(true)
                .setChildren(new ArrayList<>());

        // 构建树
        buildFileTreeNode(openList, root, ani);
        
        // 自动重命名选中的视频文件
        autoRenameFiles(root, ani);
        
        return root.getChildren();
    }

    /**
     * 递归构建文件树节点
     */
    private static void buildFileTreeNode(OpenList openList, CollectionFile parent, Ani ani) {
        List<OpenList.OpenListFileInfo> fileInfos = openList.ls(parent.getPath());
        if (fileInfos == null) return;

        for (OpenList.OpenListFileInfo info : fileInfos) {
            CollectionFile file = new CollectionFile()
                    .setPath(info.getPath() + "/" + info.getName())
                    .setName(info.getName())
                    .setOriginalName(info.getName())
                    .setSize(ObjectUtil.defaultIfNull(info.getSize(), 0L))
                    .setIsDir(info.getIs_dir())
                    .setParentPath(info.getPath())
                    .setLevel(parent.getLevel() + 1)
                    .setExpanded(true)
                    .setChildren(info.getIs_dir() ? new ArrayList<>() : null);

            if (!info.getIs_dir()) {
                String ext = FileUtil.extName(info.getName());
                if (FileUtils.isVideoFormat(ext)) {
                    file.setSelected(true);
                    try {
                        Double episode = extractEpisodeFromFileName(info.getName(), ani);
                        file.setEpisode(episode);
                    } catch (Exception e) {
                        log.debug("无法识别集数: {}", info.getName());
                    }
                }
            } else {
                // 目录默认选中，状态会传递给子文件
                file.setSelected(true);
            }

            parent.getChildren().add(file);

            if (info.getIs_dir()) {
                buildFileTreeNode(openList, file, ani);
            }
        }
    }

    /**
     * 自动重命名文件
     */
    private static void autoRenameFiles(CollectionFile node, Ani ani) {
        if (node.getChildren() == null) return;
        
        for (CollectionFile file : node.getChildren()) {
            if (file.getIsDir()) {
                autoRenameFiles(file, ani);
            } else if (Boolean.TRUE.equals(file.getSelected()) && file.getEpisode() != null) {
                // 使用 RenameUtil 生成新文件名
                Item item = new Item()
                        .setTitle(file.getOriginalName())
                        .setEpisode(file.getEpisode())
                        .setSubgroup(ani.getSubgroup());
                
                RenameUtil.rename(ani, item);
                
                if (StrUtil.isNotBlank(item.getReName())) {
                    // 保留原始扩展名
                    String ext = FileUtil.extName(file.getOriginalName());
                    String newName = item.getReName() + "." + ext;
                    file.setNewName(newName);
                    log.info("自动重命名: {} -> {}", file.getOriginalName(), newName);
                }
            }
        }
    }

    /**
     * 提取集数并生成新文件名
     */
    private static Double extractEpisodeFromFileName(String fileName, Ani ani) {
        Item item = new Item().setTitle(fileName);
        RenameUtil.rename(ani, item);
        return item.getEpisode();
    }


    /**
     * 执行整理
     */
    public static Boolean organizeCollection(String taskId, List<CollectionFile> selectedFiles, Boolean keepDirStructure, Map<String, String> directoryRenames) {
        log.info("[organizeCollection] 开始整理任务，taskId: {}", taskId);
        
        CollectionTask task = TASK_MAP.get(taskId);
        Assert.notNull(task, "任务不存在或已过期");
        Assert.isTrue("completed".equals(task.getStatus()), "任务尚未完成下载");

        Config config = ConfigUtil.CONFIG;
        OpenList openList = new OpenList();
        Assert.isTrue(openList.login(config), "OpenList 登录失败");

        task.setStatus("organizing");
        task.setKeepDirectoryStructure(keepDirStructure);
        task.setDirectoryRenameMap(directoryRenames);

        try {
            String tempPath = task.getTempPath();
            String finalPath = task.getFinalPath();

            log.info("[organizeCollection] 任务信息 - tempPath: {}, finalPath: {}, keepStructure: {}", tempPath, finalPath, keepDirStructure);
            
            // 移除 Windows 驱动器号，确保 OpenList 处理的是相对路径
            tempPath = ReUtil.replaceAll(tempPath, "^[A-z]:", "");
            finalPath = ReUtil.replaceAll(finalPath, "^[A-z]:", "");
            
            // 同时更新 task 中的路径，确保后续使用的路径都是一致的
            task.setTempPath(tempPath);
            task.setFinalPath(finalPath);
            
            // 同时更新文件树中的所有路径，去除驱动器号
            if (task.getFiles() != null) {
                normalizeFilePaths(task.getFiles());
            }
            
            log.info("[organizeCollection] 路径处理后 - tempPath: {}, finalPath: {}", tempPath, finalPath);
            openList.mkdir(finalPath);

            if (ObjectUtil.defaultIfNull(keepDirStructure, false)) {
                // 保留目录结构的处理方式
                organizeWithDirectoryStructure(openList, selectedFiles, tempPath, finalPath, directoryRenames);
            } else {
                // 不保留目录结构的处理方式（原来的逻辑）
                organizeWithoutDirectoryStructure(openList, selectedFiles, finalPath);
            }


            // 删除未选中的文件
            log.info("[organizeCollection] 开始删除未选中的文件");
            deleteUnselectedFiles(openList, task.getFiles());

            log.info("[organizeCollection] 开始清理临时目录: {}", tempPath);
            cleanupTempDir(openList, tempPath);

            task.setStatus("finished");
            log.info("[organizeCollection] 合集整理完成: {}", taskId);

            ThreadUtil.execute(() -> {
                ThreadUtil.sleep(60000);
                TASK_MAP.remove(taskId);
            });

            return true;

        } catch (Exception e) {
            log.error("[organizeCollection] 整理合集失败，taskId: {}, 错误: {}", taskId, e.getMessage(), e);
            task.setStatus("failed");
            task.setError(e.getMessage());
            return false;
        }
    }

    /**
     * 规范化文件树中的所有路径，移除 Windows 驱动器号
     */
    private static void normalizeFilePaths(List<CollectionFile> files) {
        if (files == null) return;
        
        for (CollectionFile file : files) {
            // 移除路径中的驱动器号
            if (StrUtil.isNotBlank(file.getPath())) {
                file.setPath(ReUtil.replaceAll(file.getPath(), "^[A-z]:", ""));
            }
            if (StrUtil.isNotBlank(file.getParentPath())) {
                file.setParentPath(ReUtil.replaceAll(file.getParentPath(), "^[A-z]:", ""));
            }
            
            // 递归处理子文件
            if (file.getChildren() != null && !file.getChildren().isEmpty()) {
                normalizeFilePaths(file.getChildren());
            }
        }
    }

    /**
     * 递归删除未选中的文件
     */
    private static void deleteUnselectedFiles(OpenList openList, List<CollectionFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        Map<String, List<String>> filesToDeleteByParent = new LinkedHashMap<>();
        collectUnselectedFilesToDelete(files, filesToDeleteByParent);

        for (Map.Entry<String, List<String>> entry : filesToDeleteByParent.entrySet()) {
            removeNamesInBatches(openList, entry.getKey(), entry.getValue(), "deleteUnselectedFiles");
        }
    }

    private static void cleanupTempDir(OpenList openList, String tempPath) {
        log.info("[cleanupTempDir] 开始清理临时目录: {}", tempPath);
        try {
            // 统一路径分隔符为 Unix 格式（Alist 需要）
            tempPath = tempPath.replace("\\", "/");
            // 移除驱动器号（如果有的话）
            tempPath = ReUtil.replaceAll(tempPath, "^[A-z]:", "");
            
            // 获取 .collection-temp 目录路径
            String collectionTempDir = FileUtil.getParent(tempPath, 1);  // .collection-temp 目录
            String downloadPath = FileUtil.getParent(collectionTempDir, 1);  // 下载目录
            String collectionTempName = FileUtil.getName(collectionTempDir);  // .collection-temp
            
            // 统一路径分隔符并移除驱动器号
            collectionTempDir = collectionTempDir.replace("\\", "/");
            collectionTempDir = ReUtil.replaceAll(collectionTempDir, "^[A-z]:", "");
            downloadPath = downloadPath.replace("\\", "/");
            downloadPath = ReUtil.replaceAll(downloadPath, "^[A-z]:", "");
            
            log.info("[cleanupTempDir] 参数: collectionTempDir={}, downloadPath={}, collectionTempName={}", 
                    collectionTempDir, downloadPath, collectionTempName);
            
            // 步骤1: 先递归删除 .collection-temp 内的所有内容
            deleteDirectoryRecursively(openList, collectionTempDir);
            
            // 步骤2: 尝试删除 .collection-temp 目录本身
            ThreadUtil.sleep(1000);
            
            try {
                log.info("[cleanupTempDir] 尝试删除 .collection-temp 目录: {}/{}", downloadPath, collectionTempName);
                openList.remove(downloadPath, List.of(collectionTempName));
                log.info("[cleanupTempDir] 成功删除 .collection-temp 目录");
            } catch (Exception e) {
                log.warn("[cleanupTempDir] 删除 .collection-temp 目录失败: {}, 尝试再次清理...", e.getMessage());
                
                // 再次清理
                deleteDirectoryRecursively(openList, collectionTempDir);
                ThreadUtil.sleep(1000);
                
                try {
                    openList.remove(downloadPath, List.of(collectionTempName));
                    log.info("[cleanupTempDir] 第二次尝试成功删除 .collection-temp 目录");
                } catch (Exception e2) {
                    log.error("[cleanupTempDir] 第二次删除仍然失败: {}, 可能需要手动清理", e2.getMessage());
                }
            }
            
            log.info("[cleanupTempDir] 清理临时目录流程完成");
        } catch (Exception e) {
            log.warn("[cleanupTempDir] 清理临时目录失败: {}", tempPath, e);
        }
    }
    
    /**
     * 递归删除目录及其所有内容
     */
    private static void deleteDirectoryRecursively(OpenList openList, String dirPath) {
        try {
            dirPath = dirPath.replace("\\", "/");

            log.info("[deleteDirectoryRecursively] 开始递归删除目录: {}", dirPath);
            List<OpenList.OpenListFileInfo> items = openList.ls(dirPath);

            if (items == null || items.isEmpty()) {
                log.info("[deleteDirectoryRecursively] 目录为空: {}", dirPath);
                return;
            }

            List<String> fileNames = new ArrayList<>();
            List<String> subDirNames = new ArrayList<>();

            for (OpenList.OpenListFileInfo item : items) {
                if (item.getIs_dir()) {
                    String subDirPath = dirPath + "/" + item.getName();
                    deleteDirectoryRecursively(openList, subDirPath);
                    subDirNames.add(item.getName());
                } else {
                    fileNames.add(item.getName());
                }
            }

            removeNamesInBatches(openList, dirPath, fileNames, "deleteDirectoryRecursively");
            removeNamesInBatches(openList, dirPath, subDirNames, "deleteDirectoryRecursively");

            log.info("[deleteDirectoryRecursively] 目录内容删除完成: {}", dirPath);
        } catch (Exception e) {
            log.warn("[deleteDirectoryRecursively] 递归删除目录失败: {}", dirPath, e);
        }
    }

    private static void organizeWithDirectoryStructure(OpenList openList, List<CollectionFile> selectedFiles, String tempPath, String finalPath, Map<String, String> directoryRenames) {
        log.info("[organizeWithDirectoryStructure] 开始保留目录结构方式组织文件");

        if (directoryRenames == null) {
            directoryRenames = new HashMap<>();
        }

        List<CollectionFile> selectedFileList = new ArrayList<>();
        collectSelectedFiles(selectedFiles, selectedFileList);

        if (selectedFileList.isEmpty()) {
            log.warn("[organizeWithDirectoryStructure] 没有选中的文件");
            return;
        }

        String actualTempPath = skipSingleRootDirectory(openList, tempPath, selectedFileList);
        if (!actualTempPath.equals(tempPath)) {
            log.info("[organizeWithDirectoryStructure] 跳过最外层单一根目录，实际处理路径: {}", actualTempPath);
        }

        Map<String, String> fileRenameMap = new HashMap<>();
        Map<String, List<CollectionFile>> dirGroup = selectedFileList.stream()
                .filter(f -> !f.getIsDir())
                .collect(Collectors.groupingBy(CollectionFile::getParentPath));

        for (Map.Entry<String, List<CollectionFile>> entry : dirGroup.entrySet()) {
            String parentDir = entry.getKey();
            List<CollectionFile> files = entry.getValue();

            Map<String, String> renameMap = new HashMap<>();
            for (CollectionFile file : files) {
                String oldName = file.getName();
                String newName = file.getNewName();
                if (StrUtil.isBlank(newName)) {
                    newName = oldName;
                }
                String ext = FileUtil.extName(oldName);
                if (StrUtil.isNotBlank(ext) && !newName.endsWith("." + ext)) {
                    newName = newName + "." + ext;
                }
                renameMap.put(oldName, newName);
                fileRenameMap.put(file.getPath(), newName);
            }

            if (!renameMap.isEmpty()) {
                log.info("[organizeWithDirectoryStructure] 批量重命名目录: [{}], 文件数: {}", parentDir, renameMap.size());
                openList.batchRename(parentDir, renameMap);
                pauseBriefly();
            }
        }

        log.info("[organizeWithDirectoryStructure] 开始批量移动文件，共 {} 个", selectedFileList.size());
        Map<String, MoveOperation> moveOperations = new LinkedHashMap<>();

        for (CollectionFile file : selectedFileList) {
            String parentDir = file.getParentPath();
            String newFileName = fileRenameMap.get(file.getPath());
            if (StrUtil.isBlank(newFileName)) {
                newFileName = file.getName();
            }

            String relativeDir = parentDir.substring(actualTempPath.length());
            if (relativeDir.startsWith("/") || relativeDir.startsWith("\\")) {
                relativeDir = relativeDir.substring(1);
            }

            for (Map.Entry<String, String> dirRename : directoryRenames.entrySet()) {
                String oldDirPath = dirRename.getKey();
                String newDirName = dirRename.getValue();
                if (parentDir.contains(oldDirPath)) {
                    relativeDir = relativeDir.replace(FileUtil.getName(oldDirPath), newDirName);
                }
            }

            String targetDir = (finalPath + "/" + relativeDir)
                    .replace("\\", "/")
                    .replaceAll("/+", "/");

            String moveKey = parentDir + ">>" + targetDir;
            MoveOperation operation = moveOperations.computeIfAbsent(moveKey, key -> new MoveOperation(parentDir, targetDir));
            operation.getNames().add(newFileName);
        }

        Set<String> createdDirs = new HashSet<>();
        int movedCount = 0;
        for (MoveOperation operation : moveOperations.values()) {
            if (createdDirs.add(operation.getTargetDir())) {
                openList.mkdir(operation.getTargetDir());
            }
            movedCount += moveNamesInBatches(openList, operation.getParentDir(), operation.getTargetDir(), operation.getNames());
        }

        log.info("[organizeWithDirectoryStructure] 保留目录结构组织完成，移动了 {} 个文件", movedCount);
    }

    private static String skipSingleRootDirectory(OpenList openList, String tempPath, List<CollectionFile> selectedFileList) {
        try {
            List<OpenList.OpenListFileInfo> items = openList.ls(tempPath);
            
            // 只有一个项目，且是目录
            if (items != null && items.size() == 1 && items.get(0).getIs_dir()) {
                String singleDirPath = tempPath + "/" + items.get(0).getName();
                
                // 检查所有选中的文件是否都在这个目录下
                boolean allInSingleDir = selectedFileList.stream()
                        .allMatch(file -> file.getPath().startsWith(singleDirPath + "/") || file.getPath().startsWith(singleDirPath + "\\"));
                
                if (allInSingleDir) {
                    log.info("[skipSingleRootDirectory] 检测到最外层单一根目录: {}", items.get(0).getName());
                    return singleDirPath;
                }
            }
        } catch (Exception e) {
            log.warn("[skipSingleRootDirectory] 检测根目录失败: {}", e.getMessage());
        }
        
        return tempPath;
    }
    
    /**
     * 递归收集所有选中的文件（不包括目录）
     */
    private static void collectSelectedFiles(List<CollectionFile> files, List<CollectionFile> result) {
        if (files == null) return;
        for (CollectionFile file : files) {
            if (!file.getIsDir() && ObjectUtil.defaultIfNull(file.getSelected(), false)) {
                result.add(file);
            }
            if (file.getChildren() != null) {
                collectSelectedFiles(file.getChildren(), result);
            }
        }
    }
    
    /**
     * 移动选中的文件及其目录结构（保留目录名且支持重命名）
     */

    private static void collectUnselectedFilesToDelete(List<CollectionFile> files, Map<String, List<String>> filesToDeleteByParent) {
        if (files == null || files.isEmpty()) {
            return;
        }

        for (CollectionFile file : files) {
            if (file.getChildren() != null && !file.getChildren().isEmpty()) {
                collectUnselectedFilesToDelete(file.getChildren(), filesToDeleteByParent);
            }

            if (!file.getIsDir() && !ObjectUtil.defaultIfNull(file.getSelected(), false)) {
                if (StrUtil.isBlank(file.getParentPath()) || StrUtil.isBlank(file.getName())) {
                    continue;
                }
                filesToDeleteByParent
                        .computeIfAbsent(file.getParentPath(), key -> new ArrayList<>())
                        .add(file.getName());
            }
        }
    }

    private static void removeNamesInBatches(OpenList openList, String dir, List<String> names, String logTag) {
        if (StrUtil.isBlank(dir) || names == null || names.isEmpty()) {
            return;
        }

        for (int i = 0; i < names.size(); i += OPENLIST_BATCH_SIZE) {
            int toIndex = Math.min(i + OPENLIST_BATCH_SIZE, names.size());
            List<String> batch = new ArrayList<>(names.subList(i, toIndex));
            try {
                log.info("[{}] 批量删除: dir={}, count={}", logTag, dir, batch.size());
                openList.remove(dir, batch);
                pauseBriefly();
            } catch (Exception e) {
                log.warn("[{}] 批量删除失败，回退到逐个删除: dir={}, count={}, 错误={}", logTag, dir, batch.size(), e.getMessage());
                for (String name : batch) {
                    try {
                        openList.remove(dir, List.of(name));
                    } catch (Exception singleException) {
                        log.warn("[{}] 删除失败: {}/{}, 错误={}", logTag, dir, name, singleException.getMessage());
                    }
                }
            }
        }
    }

    private static int moveNamesInBatches(OpenList openList, String parentDir, String targetDir, List<String> names) {
        if (StrUtil.isBlank(parentDir) || StrUtil.isBlank(targetDir) || names == null || names.isEmpty()) {
            return 0;
        }

        int moved = 0;
        for (int i = 0; i < names.size(); i += OPENLIST_BATCH_SIZE) {
            int toIndex = Math.min(i + OPENLIST_BATCH_SIZE, names.size());
            List<String> batch = new ArrayList<>(names.subList(i, toIndex));
            try {
                log.info("[organizeWithDirectoryStructure] 批量移动文件: [{}] -> [{}], count={}", parentDir, targetDir, batch.size());
                openList.move(parentDir, targetDir, batch);
                moved += batch.size();
                pauseBriefly();
            } catch (Exception e) {
                log.warn("[organizeWithDirectoryStructure] 批量移动失败，回退到逐个移动: [{}] -> [{}], count={}, 错误={}",
                        parentDir, targetDir, batch.size(), e.getMessage());
                for (String name : batch) {
                    try {
                        openList.move(parentDir, targetDir, List.of(name));
                        moved++;
                    } catch (Exception singleException) {
                        log.error("[organizeWithDirectoryStructure] 移动文件失败: {}/{} -> {}, 错误: {}",
                                parentDir, name, targetDir, singleException.getMessage());
                    }
                }
            }
        }

        return moved;
    }

    private static void pauseBriefly() {
        ThreadUtil.sleep(OPENLIST_BATCH_PAUSE_MS);
    }

    private static final class MoveOperation {
        private final String parentDir;
        private final String targetDir;
        private final List<String> names = new ArrayList<>();

        private MoveOperation(String parentDir, String targetDir) {
            this.parentDir = parentDir;
            this.targetDir = targetDir;
        }

        private String getParentDir() {
            return parentDir;
        }

        private String getTargetDir() {
            return targetDir;
        }

        private List<String> getNames() {
            return names;
        }
    }
    private static void moveSelectedDirectoryStructure(OpenList openList, String sourcePath, String destPath, Set<String> selectedFilePaths, Map<String, String> directoryRenames) {
        try {
            log.info("[moveSelectedDirectoryStructure] 开始移动选中文件的目录结构");
            List<OpenList.OpenListFileInfo> items = openList.ls(sourcePath);
            if (items == null || items.isEmpty()) return;

            for (OpenList.OpenListFileInfo item : items) {
                String sourceFull = sourcePath + "/" + item.getName();
                
                // 检查是否有重命名规则
                String targetName = item.getName();
                if (directoryRenames != null && directoryRenames.containsKey(sourceFull)) {
                    targetName = directoryRenames.get(sourceFull);
                    log.info("[moveSelectedDirectoryStructure] 目录重命名: {} -> {}", item.getName(), targetName);
                }
                
                String destFull = destPath + "/" + targetName;

                if (item.getIs_dir()) {
                    // 检查此目录下是否有选中的文件
                    if (hasSelectedFilesInDir(openList, sourceFull, selectedFilePaths)) {
                        // 创建目标目录
                        openList.mkdir(destFull);
                        // 递归移动子目录（只移动包含选中文件的目录）
                        moveSelectedDirectoryStructure(openList, sourceFull, destFull, selectedFilePaths, directoryRenames);
                    }
                } else {
                    // 只移动选中的文件
                    if (selectedFilePaths.contains(sourceFull)) {
                        openList.move(sourcePath, destPath, List.of(item.getName()));
                        log.debug("[moveSelectedDirectoryStructure] 移动文件: {} -> {}", sourceFull, destFull);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[moveSelectedDirectoryStructure] 移动目录结构失败: {}", sourcePath, e);
        }
    }
    
    /**
     * 检查目录下是否有选中的文件
     */
    private static boolean hasSelectedFilesInDir(OpenList openList, String dirPath, Set<String> selectedFilePaths) {
        // 检查该目录路径下是否有任何选中文件
        return selectedFilePaths.stream().anyMatch(path -> path.startsWith(dirPath + "/") || path.startsWith(dirPath + "\\"));
    }

    /**
     * 不保留目录结构方式组织文件
     */
    private static void organizeWithoutDirectoryStructure(OpenList openList, List<CollectionFile> selectedFiles, String finalPath) {
        log.info("[organizeWithoutDirectoryStructure] 开始不保留目录结构方式组织文件");
        
        // 递归收集所有选中的文件
        List<CollectionFile> selectedFileList = new ArrayList<>();
        collectSelectedFiles(selectedFiles, selectedFileList);
        
        if (selectedFileList.isEmpty()) {
            log.warn("[organizeWithoutDirectoryStructure] 没有选中的文件");
            return;
        }
        
        // 按目录分组
        Map<String, List<CollectionFile>> dirGroup = selectedFileList.stream()
                .collect(Collectors.groupingBy(CollectionFile::getParentPath));

        // 重命名文件
        for (Map.Entry<String, List<CollectionFile>> entry : dirGroup.entrySet()) {
            String parentDir = entry.getKey();
            List<CollectionFile> files = entry.getValue();
            
            log.info("[organizeWithoutDirectoryStructure] 处理目录: [{}], 文件数: {}", parentDir, files.size());

            Map<String, String> renameMap = new HashMap<>();
            for (CollectionFile file : files) {
                String oldName = file.getName();
                String newName = file.getNewName();
                if (StrUtil.isBlank(newName)) {
                    newName = oldName;
                }
                String ext = FileUtil.extName(oldName);
                if (!newName.endsWith("." + ext)) {
                    newName = newName + "." + ext;
                }
                renameMap.put(oldName, newName);
                log.info("[organizeWithoutDirectoryStructure] 准备重命名: {} -> {}", oldName, newName);
            }

            if (!renameMap.isEmpty()) {
                log.info("[organizeWithoutDirectoryStructure] 开始批量重命名，目录: [{}]", parentDir);
                openList.batchRename(parentDir, renameMap);
                ThreadUtil.sleep(3000);
                
                log.info("[organizeWithoutDirectoryStructure] 开始移动文件，从 [{}] 到 [{}]", parentDir, finalPath);
                List<String> newNames = new ArrayList<>(renameMap.values());
                log.info("[organizeWithoutDirectoryStructure] 要移动的文件名: {}", newNames);
                
                try {
                    openList.move(parentDir, finalPath, newNames);
                    log.info("[organizeWithoutDirectoryStructure] 移动成功");
                } catch (Exception e) {
                    log.error("[organizeWithoutDirectoryStructure] 移动失败: {}", e.getMessage(), e);
                    throw e;
                }
                ThreadUtil.sleep(2000);
            } else {
                log.warn("[organizeWithoutDirectoryStructure] 没有需要处理的文件");
            }
        }
        
        log.info("[organizeWithoutDirectoryStructure] 不保留目录结构组织完成");
    }
    
    /**
     * 删除目录内容，保留目录结构用于日志记录
     * 
     * @param openList OpenList 实例
     * @param path 要清理的目录路径
     * @param keepDirStructure 是否保留目录结构
     */
    private static void deleteDirectoryContents(OpenList openList, String path, Boolean keepDirStructure) {
        try {
            List<OpenList.OpenListFileInfo> files = openList.ls(path);
            if (files == null || files.isEmpty()) {
                log.debug("[deleteDirectoryContents] 目录为空: {}", path);
                return;
            }
            
            for (OpenList.OpenListFileInfo file : files) {
                try {
                    if (file.getIs_dir()) {
                        // 递归清理子目录
                        deleteDirectoryContents(openList, path + "/" + file.getName(), keepDirStructure);
                        
                        // 如果不保留目录结构，则删除空目录
                        if (!keepDirStructure) {
                            try {
                                openList.remove(path, List.of(file.getName()));
                                log.debug("[deleteDirectoryContents] 删除空目录: {}/{}", path, file.getName());
                            } catch (Exception e) {
                                log.warn("[deleteDirectoryContents] 删除目录失败: {}/{}", path, file.getName(), e);
                            }
                        }
                    } else {
                        // 删除文件
                        try {
                            openList.remove(path, List.of(file.getName()));
                            log.debug("[deleteDirectoryContents] 删除文件: {}/{}", path, file.getName());
                        } catch (Exception e) {
                            log.warn("[deleteDirectoryContents] 删除文件失败: {}/{}", path, file.getName(), e);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[deleteDirectoryContents] 处理文件失败: {}/{}", path, file.getName(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[deleteDirectoryContents] 读取目录失败: {}", path, e);
        }
    }
    
    /**
     * 取消任务
     */
    public static void cancelTask(String taskId) {
        CollectionTask task = TASK_MAP.get(taskId);
        if (task == null) {
            log.warn("[cancelTask] 任务不存在: {}", taskId);
            return;
        }

        log.info("[cancelTask] 开始取消任务: {}", taskId);

        if ("downloading".equals(task.getStatus()) && StrUtil.isNotBlank(task.getTid())) {
            Config config = ConfigUtil.CONFIG;
            OpenList openList = new OpenList();
            if (openList.login(config)) {
                try {
                    openList.taskDelete(task.getTid());
                    log.info("[cancelTask] 已取消 OpenList 下载任务: {}", task.getTid());
                } catch (Exception e) {
                    log.warn("[cancelTask] 取消 OpenList 下载任务失败: {}", task.getTid(), e);
                }
            }
        }

        // 清理临时目录
        if (StrUtil.isNotBlank(task.getTempPath())) {
            try {
                Config config = ConfigUtil.CONFIG;
                OpenList openList = new OpenList();
                if (openList.login(config)) {
                    cleanupTempDir(openList, task.getTempPath());
                    log.info("[cancelTask] 已清理临时目录: {}", task.getTempPath());
                } else {
                    log.warn("[cancelTask] 无法连接 OpenList，跳过临时文件清理");
                }
            } catch (Exception e) {
                log.warn("[cancelTask] 清理临时目录失败: {}", task.getTempPath(), e);
            }
        }

        // 更新任务状态
        task.setStatus("cancelled");
        TASK_MAP.remove(taskId);
        log.info("[cancelTask] 任务已取消并移除: {}", taskId);
    }

    /**
     * 验证任务文件完整性
     * 
     * @param taskId 任务ID
     * @return 验证结果
     */
    public static Boolean validateTaskFiles(String taskId) {
        CollectionTask task = TASK_MAP.get(taskId);
        if (task == null) {
            log.warn("[validateTaskFiles] 任务不存在: {}", taskId);
            return false;
        }

        if (task.getFiles() == null || task.getFiles().isEmpty()) {
            log.warn("[validateTaskFiles] 任务没有文件列表: {}", taskId);
            return false;
        }

        Config config = ConfigUtil.CONFIG;
        OpenList openList = new OpenList();
        if (!openList.login(config)) {
            log.warn("[validateTaskFiles] OpenList 登录失败");
            return false;
        }

        try {
            boolean allValid = true;
            for (CollectionFile file : task.getFiles()) {
                if (!file.getIsDir()) {
                    try {
                        List<OpenList.OpenListFileInfo> parentFiles = openList.ls(file.getParentPath());
                        boolean fileExists = parentFiles != null && parentFiles.stream()
                                .anyMatch(f -> f.getName().equals(file.getName()));
                        
                        if (!fileExists) {
                            log.warn("[validateTaskFiles] 文件不存在: {}/{}", file.getParentPath(), file.getName());
                            allValid = false;
                        }
                    } catch (Exception e) {
                        log.warn("[validateTaskFiles] 验证文件失败: {}/{}", file.getParentPath(), file.getName(), e);
                        allValid = false;
                    }
                }
            }

            if (allValid) {
                log.info("[validateTaskFiles] 任务文件验证通过: {}", taskId);
            } else {
                log.warn("[validateTaskFiles] 任务某些文件缺失: {}", taskId);
            }
            return allValid;
        } catch (Exception e) {
            log.error("[validateTaskFiles] 验证任务文件失败: {}", taskId, e);
            return false;
        }
    }

    /**
     * 获取任务日志信息
     * 
     * @param taskId 任务ID
     * @return 日志内容
     */
    public static String getTaskLog(String taskId) {
        CollectionTask task = TASK_MAP.get(taskId);
        if (task == null) {
            return "任务不存在或已过期";
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 合集下载任务日志 ===\n");
        log.append(String.format("任务ID: %s\n", task.getId()));
        log.append(String.format("任务状态: %s\n", task.getStatus()));
        log.append(String.format("磁力链接: %s\n", task.getMagnet()));
        log.append(String.format("临时路径: %s\n", task.getTempPath()));
        log.append(String.format("最终路径: %s\n", task.getFinalPath()));
        log.append(String.format("下载进度: %d%%\n", ObjectUtil.defaultIfNull(task.getProgress(), 0)));
        log.append(String.format("创建时间: %s\n", new java.util.Date(task.getCreateTime())));
        
        if (task.getFiles() != null) {
            log.append(String.format("文件数量: %d\n", task.getFiles().size()));
            long selectedCount = task.getFiles().stream()
                    .filter(f -> !f.getIsDir() && f.getSelected())
                    .count();
            log.append(String.format("已选择文件: %d\n", selectedCount));
        }

        if (StrUtil.isNotBlank(task.getError())) {
            log.append(String.format("错误信息: %s\n", task.getError()));
        }

        return log.toString();
    }

    /**
     * 获取所有任务
     */
    public static Collection<CollectionTask> getAllTasks() {
        return TASK_MAP.values();
    }

    public static void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        TASK_MAP.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().getCreateTime() > TASK_EXPIRE_TIME;
            if (expired) {
                log.info("清理过期任务: {}", entry.getKey());
            }
            return expired;
        });
    }
}




