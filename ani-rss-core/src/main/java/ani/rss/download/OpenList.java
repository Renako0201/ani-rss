package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.URLUtils;
import ani.rss.entity.*;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class OpenList implements BaseDownload {
    private Config config;
    private static final long API_MIN_INTERVAL_MS = 150;

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = config;
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("OpenList 未配置完成");
            return false;
        }
        String downloadPath = config.getDownloadPathTemplate();
        Assert.notBlank(downloadPath, "未设置下载位置");
        String provider = config.getProvider();
        Assert.notBlank(provider, "请选择 Driver");
        try {
            return postApi("me")
                    .setMethod(Method.GET)
                    .thenFunction(res -> {
                        if (!res.isOk()) {
                            log.error("登录 OpenList 失败");
                            return false;
                        }
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        if (jsonObject.get("code").getAsInt() != 200) {
                            log.error("登录 OpenList 失败");
                            return false;
                        }
                        return true;
                    });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(e.getMessage(), e);
            log.error("登录 OpenList 失败 {}", message);
        }
        return false;
    }


    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        return List.of();
    }

    @Override
    public synchronized Boolean download(Ani ani, Item item, String savePath, File torrentFile, Boolean ova) {
        // windows 真该死啊
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        String magnet = TorrentUtil.getMagnet(torrentFile);
        String reName = item.getReName();
        String path = savePath + "/" + reName;
        Boolean standbyRss = config.getStandbyRss();
        Boolean delete = config.getDelete();
        Boolean coexist = config.getCoexist();
        try {
            // 洗版，删除备 用RSS 所下载的视频
            if (standbyRss && delete && !coexist) {
                String s = ReUtil.get(StringEnum.SEASON_REG, reName, 0);
                String finalSavePath = savePath;
                ls(savePath)
                        .stream()
                        .map(OpenListFileInfo::getName)
                        .filter(name -> name.contains(s))
                        .forEach(name -> {
                            postApi("fs/remove")
                                    .body(GsonStatic.toJson(Map.of(
                                            "dir", finalSavePath,
                                            "names", List.of(name)
                                    ))).then(HttpResponse::isOk);
                            log.info("已开启备用RSS, 自动删除 {}/{}", finalSavePath, name);
                        });
            }
            String tid = postApi("fs/add_offline_download")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "urls", List.of(magnet),
                            "tool", config.getProvider(),
                            "delete_policy", "delete_on_upload_succeed"
                    )))
                    .thenFunction(res -> {
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        Assert.isTrue(jsonObject.get("code").getAsInt() == 200, "添加离线下载失败 {}", reName);
                        log.info("添加离线下载成功 {}", reName);
                        return jsonObject.getAsJsonObject("data")
                                .getAsJsonArray("tasks")
                                .get(0).getAsJsonObject()
                                .get("id").getAsString();
                    });

            TimeInterval timer = DateUtil.timer();
            // 重试次数
            long retry = 0;
            while (true) {
                Integer alistDownloadTimeout = config.getAlistDownloadTimeout();
                Long alistDownloadRetryNumber = config.getAlistDownloadRetryNumber();
                if (timer.intervalMinute() > alistDownloadTimeout) {
                    // 超过下载超时限制
                    timer.clear();
                    log.error("{} {} 分钟还未下载完成, 停止检测下载", reName, alistDownloadTimeout);
                    return false;
                }

                // https://github.com/AlistGo/alist/blob/main/pkg/task/task.go
                JsonObject taskInfo;

                try {
                    taskInfo = taskInfo(tid);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    continue;
                }

                String error = taskInfo.get("error").getAsString();
                int state = taskInfo
                        .get("state").getAsInt();
                // errored 重试
                if (state > 5) {
                    // 已到达最大重试次数 5 次, -1 不限制
                    if (alistDownloadRetryNumber > -1) {
                        if (retry >= alistDownloadRetryNumber) {
                            log.error("离线下载失败 {}", error);
                            return false;
                        }
                        retry++;
                        log.info("离线任务正在进行重试 {}, 当前重试次数 {}, 最大重试次数 {}", tid, retry, alistDownloadRetryNumber);
                    }
                    taskRetry(tid);
                    continue;
                }

                if (List.of(3, 4).contains(state)) {
                    log.error("离线任务已被取消 {}", reName);
                    return false;
                }

                // 成功
                if (state == 2) {
                    break;
                }
            }

            if (delete) {
                log.info("离线下载完成, 自动删除已完成任务");
                taskDelete(tid);
            }

            List<OpenListFileInfo> openListFileInfos = findFiles(path);

            // 取大小最大的一个视频文件
            OpenListFileInfo videoFile = openListFileInfos.stream()
                    .filter(openListFileInfo ->
                            FileUtils.isVideoFormat(openListFileInfo.getName()))
                    .findFirst()
                    .orElse(null);

            if (Objects.isNull(videoFile)) {
                return false;
            }

            List<OpenListFileInfo> subtitleList = openListFileInfos.stream()
                    .filter(openListFileInfo ->
                            FileUtils.isSubtitleFormat(openListFileInfo.getName()))
                    .toList();

            Map<String, String> renameMap = new HashMap<>();
            renameMap.put(videoFile.getName(), reName + "." + FileUtil.extName(videoFile.getName()));
            for (OpenListFileInfo openListFileInfo : subtitleList) {
                String name = openListFileInfo.getName();
                String extName = FileUtil.extName(name);
                String newName = reName;
                String lang = FileUtil.extName(FileUtil.mainName(name));
                if (StrUtil.isNotBlank(lang)) {
                    newName = newName + "." + lang;
                }
                renameMap.put(name, newName + "." + extName);
            }

            Boolean rename = config.getRename();

            if (rename) {
                // 重命名
                List<Map<String, String>> rename_objects = renameMap.entrySet().stream()
                        .map(map -> {
                            String srcName = map.getKey();
                            String newName = map.getValue();
                            log.info("重命名 {} ==> {}", srcName, newName);
                            return Map.of(
                                    "src_name", srcName,
                                    "new_name", newName
                            );
                        }).toList();
                postApi("fs/batch_rename")
                        .body(GsonStatic.toJson(Map.of(
                                "src_dir", videoFile.getPath(),
                                "rename_objects", rename_objects
                        ))).then(res -> log.info(res.body()));
            }

            // 移动
            List<String> names = renameMap.entrySet()
                    .stream()
                    .map(m -> rename ? m.getValue() : m.getKey())
                    .toList();
            postApi("fs/move")
                    .body(GsonStatic.toJson(Map.of(
                            "src_dir", videoFile.getPath(),
                            "dst_dir", savePath,
                            "names", names
                    ))).then(res -> log.info(res.body()));

            // 删除残留文件夹
            postApi("fs/remove")
                    .body(GsonStatic.toJson(Map.of(
                            "dir", savePath,
                            "names", List.of(reName)
                    ))).then(HttpResponse::isOk);

            NotificationUtil.send(config, ani,
                    StrFormatter.format("{} 下载完成", item.getReName()),
                    NotificationStatusEnum.DOWNLOAD_END
            );
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        return false;
    }

    @Override
    public void rename(TorrentsInfo torrentsInfo) {

    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {

    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {

    }

    /**
     * 文件列表
     *
     * @param path
     * @return
     */
    public List<OpenListFileInfo> ls(String path) {
        try {
            return postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", 1,
                            "per_page", 0,
                            "refresh", false
                    )))
                    .thenFunction(res -> {
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        if (code != 200) {
                            return List.of();
                        }
                        JsonElement data = jsonObject.get("data");
                        if (Objects.isNull(data) || data.isJsonNull()) {
                            return List.of();
                        }
                        JsonElement content = data.getAsJsonObject()
                                .get("content");
                        if (Objects.isNull(content) || content.isJsonNull()) {
                            return List.of();
                        }
                        List<OpenListFileInfo> infos = GsonStatic.fromJsonList(content.getAsJsonArray(), OpenListFileInfo.class);
                        for (OpenListFileInfo info : infos) {
                            info.setPath(path);
                        }
                        return ListUtil.sort(new ArrayList<>(infos), Comparator.comparing(fileInfo -> {
                            Long size = fileInfo.getSize();
                            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
                        }));
                    });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return List.of();
    }

    /**
     * 查看任务
     *
     * @param tid
     * @return
     */
    public JsonObject taskInfo(String tid) {
        return postApi("task/offline_download/info?tid=" + tid)
                .thenFunction(res -> {
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    return jsonObject.get("data").getAsJsonObject();
                });
    }

    /**
     * 重试任务
     *
     * @param tid
     */
    public void taskRetry(String tid) {
        postApi("task/offline_download/retry")
                .form("tid", tid)
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 删除任务
     *
     * @param tid
     */
    public void taskDelete(String tid) {
        postApi("task/offline_download/delete_some")
                .body(GsonStatic.toJson(List.of(tid)))
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 获取目录下及子目录的文件
     *
     * @param path
     * @return
     */
    public synchronized List<OpenListFileInfo> findFiles(String path) {
        List<OpenListFileInfo> openListFileInfos = ls(path);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (openListFileInfo.getIs_dir()) {
                        return findFiles(path + "/" + openListFileInfo.getName()).stream();
                    }
                    return Stream.of(openListFileInfo);
                }).toList();

        return ListUtil.sort(new ArrayList<>(list), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
    }

    @Data
    @Accessors(chain = true)
    public static class OpenListFileInfo implements Serializable {
        private String name;
        private Long size;
        private Boolean is_dir;
        private Date modified;
        private Date created;
        private String path;
    }

    /**
     * 创建目录
     *
     * @param path 目录路径
     */
    public void mkdir(String path) {
        try {
            postApi("fs/mkdir")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path
                    )))
                    .thenFunction(res -> {
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        int code = jsonObject.get("code").getAsInt();
                        // 200 成功，400 可能目录已存在
                        if (code != 200 && code != 400) {
                            log.warn("创建目录失败: {}", jsonObject.get("message").getAsString());
                        }
                        return code == 200;
                    });
        } catch (Exception e) {
            log.warn("创建目录异常", e);
        }
    }

    /**
     * 批量重命名文件
     *
     * @param dir       目录
     * @param renameMap 重命名映射 (旧名->新名)
     */
    public void batchRename(String dir, Map<String, String> renameMap) {
        if (renameMap.isEmpty()) {
            log.debug("[batchRename] 目录 [{}] 没有需要重命名的文件", dir);
            return;
        }

        // 过滤掉新旧名称相同的项
        Map<String, String> filteredMap = renameMap.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(entry.getValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));

        if (filteredMap.isEmpty()) {
            log.debug("[batchRename] 目录 [{}] 所有文件新名称与旧名称相同，跳过重命名", dir);
            return;
        }

        // 记录被跳过的文件
        int skippedCount = renameMap.size() - filteredMap.size();
        if (skippedCount > 0) {
            log.warn("[batchRename] 目录 [{}] 有 {} 个文件因名称未改变而被跳过", dir, skippedCount);
            for (Map.Entry<String, String> entry : renameMap.entrySet()) {
                if (entry.getKey().equals(entry.getValue())) {
                    log.warn("[batchRename] 跳过: {} -> {} (名称相同)", entry.getKey(), entry.getValue());
                }
            }
        }

        List<Map<String, String>> renameObjects = filteredMap.entrySet().stream()
                .map(entry -> Map.of(
                        "src_name", entry.getKey(),
                        "new_name", entry.getValue()
                ))
                .toList();

        log.info("[batchRename] 准备重命名，目录: [{}], 文件数: {}, 详情: {}", 
                dir, renameObjects.size(), filteredMap);

        // 批量重命名可能需要较长时间，设置较长的超时
        postApi("fs/batch_rename")
                .timeout(120000)  // 120秒超时
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", dir,
                        "rename_objects", renameObjects
                )))
                .thenFunction(res -> {
                    String responseBody = res.body();
                    log.debug("[batchRename] 响应: {}", responseBody);
                    JsonObject jsonObject = GsonStatic.fromJson(responseBody, JsonObject.class);
                    int code = jsonObject.get("code").getAsInt();
                    if (code == 200) {
                        log.info("[batchRename] 批量重命名成功，目录: [{}], 共 {} 个文件", dir, renameObjects.size());
                    } else {
                        String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "未知错误";
                        String errorDetail = jsonObject.has("data") ? jsonObject.get("data").toString() : "";
                        log.error("[batchRename] 批量重命名失败，目录: [{}], 错误码: {}, 错误: {}, 详情: {}", 
                                dir, code, message, errorDetail);
                        log.error("[batchRename] 重命名请求内容: {}", renameMap);
                    }
                    return null;
                });
    }

    /**
     * 移动文件
     *
     * @param srcDir 源目录
     * @param dstDir 目标目录
     * @param names  文件名列表
     */
    public void move(String srcDir, String dstDir, List<String> names) {
        log.info("[move] 移动文件，从 [{}] 到 [{}], 文件数: {}", srcDir, dstDir, names.size());
        postApi("fs/move")
                .timeout(120000)  // 120秒超时，移动大文件可能需要时间
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "dst_dir", dstDir,
                        "names", names
                )))
                .thenFunction(res -> {
                    if (res.isOk()) {
                        log.info("[move] 移动成功，文件数: {}", names.size());
                    } else {
                        log.error("[move] 移动失败，状态码: {}, 响应: {}", res.getStatus(), res.body());
                    }
                    return res.isOk();
                });
    }

    /**
     * 复制文件
     *
     * @param srcDir 源目录
     * @param dstDir 目标目录
     * @param names  文件名列表
     */
    public void copy(String srcDir, String dstDir, List<String> names) {
        postApi("fs/copy")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "dst_dir", dstDir,
                        "names", names
                )))
                .then(HttpResponse::isOk);
    }

    /**
     * 删除文件
     *
     * @param dir   目录
     * @param names 文件名列表
     */
    public void remove(String dir, List<String> names) {
        postApi("fs/remove")
                .body(GsonStatic.toJson(Map.of(
                        "dir", dir,
                        "names", names
                )))
                .then(HttpResponse::isOk);
    }

    /**
     * 获取任务进度（百分比）
     *
     * @param tid 任务ID
     * @return 进度 0-100
     */
    public Integer getTaskProgress(String tid) {
        try {
            JsonObject taskInfo = taskInfo(tid);
            // 尝试获取进度信息
            JsonElement progressElement = taskInfo.get("progress");
            if (progressElement != null && !progressElement.isJsonNull()) {
                return progressElement.getAsInt();
            }
            // 如果没有进度字段，根据状态返回
            int state = taskInfo.get("state").getAsInt();
            if (state == 2) return 100;
            if (state == 0) return 0;
            return 50; // 进行中，但不知道具体进度
        } catch (Exception e) {
            log.error("获取任务进度失败", e);
            return 0;
        }
    }

    /**
     * 使用磁力链接添加离线下载任务
     *
     * @param magnet   磁力链接
     * @param savePath 保存路径
     * @return 任务ID
     */
    public String addMagnetOfflineDownload(String magnet, String savePath) {
        // windows 路径处理
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        // 创建保存目录
        mkdir(savePath);

        String provider = config.getProvider();
        Assert.notBlank(provider, "请选择 Driver");

        return postApi("fs/add_offline_download")
                .body(GsonStatic.toJson(Map.of(
                        "path", savePath,
                        "urls", List.of(magnet),
                        "tool", provider,
                        "delete_policy", "delete_on_upload_succeed"
                )))
                .thenFunction(res -> {
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    Assert.isTrue(jsonObject.get("code").getAsInt() == 200,
                            "添加离线下载失败: {}", jsonObject.get("message").getAsString());
                    log.info("添加磁力链接离线下载成功: {}", magnet.substring(0, Math.min(50, magnet.length())));
                    return jsonObject.getAsJsonObject("data")
                            .getAsJsonArray("tasks")
                            .get(0).getAsJsonObject()
                            .get("id").getAsString();
                });
    }

    /**
     * post api
     *
     * @param action
     * @return
     */
    public synchronized HttpRequest postApi(String action) {
        ThreadUtil.sleep(API_MIN_INTERVAL_MS);
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.post(host + "/api/" + action)
                .header(Header.AUTHORIZATION, password);
    }

}
