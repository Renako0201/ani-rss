package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.RcloneSyncTask;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RcloneSyncTaskService {
    public static final String MODE_EPISODE = "episode";
    public static final String MODE_COLLECTION = "collection";
    public static final String COLLECTION_PREFIX = "[COLLECTION]";

    private static final Map<String, RcloneSyncTask> TASK_MAP = new ConcurrentHashMap<>();
    private static final Map<String, NetSnapshot> NET_SNAPSHOT_MAP = new ConcurrentHashMap<>();
    private static final Set<String> ACTIVE_TASK_IDS = ConcurrentHashMap.newKeySet();
    private static final int MAX_HISTORY = 200;
    private static final String NET_MARK = "__ANIRSS_NET__";

    public static RcloneSyncTask createTask(
            String mode,
            String title,
            String seasonFormat,
            String episodeFormat,
            String srcFs,
            String dstFs
    ) {
        long now = System.currentTimeMillis();
        RcloneSyncTask task = new RcloneSyncTask()
                .setId(IdUtil.simpleUUID())
                .setCreateTime(now)
                .setUpdateTime(now)
                .setMode(mode)
                .setStatus("queued")
                .setTitle(title)
                .setSeasonFormat(seasonFormat)
                .setEpisodeFormat(episodeFormat)
                .setSrcFs(srcFs)
                .setDstFs(dstFs)
                .setGroup(IdUtil.fastSimpleUUID())
                .setNetIface("")
                .setNetRxBps(0L)
                .setNetTxBps(0L)
                .setNetSampleTime(0L)
                .setMessage("");
        TASK_MAP.put(task.getId(), task);
        ACTIVE_TASK_IDS.add(task.getId());
        trimHistory();
        return task;
    }

    public static List<RcloneSyncTask> list(Boolean refresh, NotificationConfig notificationConfig) {
        List<RcloneSyncTask> list = new ArrayList<>(TASK_MAP.values());
        list.sort(Comparator.comparingLong(RcloneSyncTask::getCreateTime).reversed());

        if (!refresh) {
            return list;
        }
        if (Objects.isNull(notificationConfig)) {
            log.debug("RcloneSync task list refresh skipped: no enabled RCLONE_SYNC config found");
            return list;
        }

        for (RcloneSyncTask task : list) {
            if (!List.of("queued", "running").contains(task.getStatus())) {
                continue;
            }
            try {
                refreshTask(task, notificationConfig);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        List<RcloneSyncTask> result = new ArrayList<>(TASK_MAP.values())
                .stream()
                .sorted(Comparator.comparingLong(RcloneSyncTask::getCreateTime).reversed())
                .toList();
        applyNetSample(result, sampleRemoteNet(notificationConfig));
        return result;
    }

    public static void clearFinished() {
        TASK_MAP.entrySet().removeIf(entry -> {
            boolean active = List.of("queued", "running").contains(entry.getValue().getStatus());
            if (!active) {
                ACTIVE_TASK_IDS.remove(entry.getKey());
            }
            return !active;
        });
    }

    public static boolean hasActiveTasks() {
        return !ACTIVE_TASK_IDS.isEmpty();
    }

    public static String probe(NotificationConfig notificationConfig) {
        String command = buildRcCommand(notificationConfig, "core/version", List.of());
        String host = StrUtil.blankToDefault(notificationConfig.getRcloneSyncSshHost(), "-");
        int port = ObjectUtil.defaultIfNull(notificationConfig.getRcloneSyncSshPort(), 22);
        log.info("RcloneSync probe start: ssh={}:{} rc={}", host, port,
                StrUtil.blankToDefault(notificationConfig.getRcloneSyncRcUrl(), "http://127.0.0.1:5572"));
        long start = System.currentTimeMillis();
        ExecResult execResult = execSsh(notificationConfig, command, 20);
        if (execResult.exitCode != 0) {
            log.warn("RcloneSync probe failed: exit={} output={}", execResult.exitCode, shortOutput(execResult.output));
            throw new IllegalArgumentException(StrUtil.blankToDefault(execResult.output, "ssh/rclone rc probe failed"));
        }
        JsonObject jsonObject = GsonStatic.fromJson(execResult.output, JsonObject.class);
        String version = getString(jsonObject, "version");
        if (StrUtil.isBlank(version)) {
            log.warn("RcloneSync probe invalid response: {}", shortOutput(execResult.output));
            throw new IllegalArgumentException(StrUtil.blankToDefault(execResult.output, "rclone rc probe returned invalid response"));
        }
        log.info("RcloneSync probe success: version={} cost={}ms", version, System.currentTimeMillis() - start);
        return execResult.output;
    }

    public static String startRcTask(
            RcloneSyncTask task,
            NotificationConfig notificationConfig,
            String rcApi,
            List<String> args
    ) {
        List<String> command = new ArrayList<>();
        String rcloneBin = StrUtil.blankToDefault(notificationConfig.getRcloneSyncRcloneBin(), "rclone");
        command.add(rcloneBin);
        command.add("rc");

        String rcUrl = StrUtil.blankToDefault(notificationConfig.getRcloneSyncRcUrl(), "http://127.0.0.1:5572");
        command.add("--url");
        command.add(rcUrl);

        String rcUser = notificationConfig.getRcloneSyncRcUser();
        String rcPass = notificationConfig.getRcloneSyncRcPass();
        if (StrUtil.isAllNotBlank(rcUser, rcPass)) {
            command.add("--user");
            command.add(rcUser);
            command.add("--pass");
            command.add(rcPass);
        }

        command.add(rcApi);
        command.add("srcFs=" + task.getSrcFs());
        command.add("dstFs=" + task.getDstFs());
        // Execute synchronously in notification queue.
        // This prevents concurrent syncs during continuous subscription downloads.
        command.add("_async=false");
        command.add("_group=" + task.getGroup());
        if (CollUtil.isNotEmpty(args)) {
            command.addAll(args);
        }

        String remoteCommand = toRemoteCommand(command);
        String taskId = task.getId();
        task.setStatus("running")
                .setMessage("running")
                .setUpdateTime(System.currentTimeMillis());
        ACTIVE_TASK_IDS.add(taskId);
        long start = System.currentTimeMillis();
        try {
            log.info("RcloneSync execute start: id={} mode={} api={} src={} dst={} argsCount={}",
                    task.getId(), task.getMode(), rcApi, task.getSrcFs(), task.getDstFs(), ObjectUtil.defaultIfNull(args, List.of()).size());
            // Long-running transfer: keep enough time for real file sync.
            ExecResult result = execSsh(notificationConfig, remoteCommand, 60 * 60 * 6);
            if (result.exitCode != 0) {
                task.setStatus("failed")
                        .setMessage(result.output)
                        .setUpdateTime(System.currentTimeMillis());
                log.warn("RcloneSync execute failed: id={} exit={} cost={}ms output={}",
                        task.getId(), result.exitCode, System.currentTimeMillis() - start, shortOutput(result.output));
                return result.output;
            }
            task.setStatus("success")
                    .setMessage("success")
                    .setUpdateTime(System.currentTimeMillis());
            log.info("RcloneSync execute success: id={} cost={}ms output={}",
                    task.getId(), System.currentTimeMillis() - start, shortOutput(result.output));
            return result.output;
        } finally {
            ACTIVE_TASK_IDS.remove(taskId);
        }
    }

    public static void refreshTask(RcloneSyncTask task, NotificationConfig notificationConfig) {
        ExecResult statsResult = execSsh(
                notificationConfig,
                buildRcCommand(notificationConfig, "core/stats", List.of("group=" + task.getGroup())),
                20
        );
        if (statsResult.exitCode == 0) {
            JsonObject stats = GsonStatic.fromJson(statsResult.output, JsonObject.class);
            task.setBytes(getLong(stats, "bytes"))
                    .setTotalBytes(getLong(stats, "totalBytes"))
                    .setSpeed(getLong(stats, "speed"))
                    .setTransferred((int) getLong(stats, "transfers"))
                    .setErrors((int) getLong(stats, "errors"))
                    .setUpdateTime(System.currentTimeMillis());
        }

        // In sync mode (_async=false), rc won't return jobId.
        // Keep stats refresh by group and skip job/status polling.
        if (Objects.isNull(task.getJobId())) {
            return;
        }

        ExecResult jobResult = execSsh(
                notificationConfig,
                buildRcCommand(notificationConfig, "job/status", List.of("jobid=" + task.getJobId())),
                20
        );
        if (jobResult.exitCode != 0) {
            task.setMessage(jobResult.output)
                    .setUpdateTime(System.currentTimeMillis());
            return;
        }

        JsonObject statusJson = GsonStatic.fromJson(jobResult.output, JsonObject.class);
        boolean finished = getBoolean(statusJson, "finished");
        boolean success = getBoolean(statusJson, "success");
        String error = getString(statusJson, "error");
        if (!finished) {
            task.setStatus("running")
                    .setMessage(StrUtil.blankToDefault(error, "running"))
                    .setUpdateTime(System.currentTimeMillis());
            return;
        }

        task.setStatus(success ? "success" : "failed")
                .setMessage(StrUtil.blankToDefault(error, success ? "success" : "failed"))
                .setUpdateTime(System.currentTimeMillis());
    }

    private static String buildRcCommand(NotificationConfig notificationConfig, String api, List<String> args) {
        List<String> command = new ArrayList<>();
        String rcloneBin = StrUtil.blankToDefault(notificationConfig.getRcloneSyncRcloneBin(), "rclone");
        command.add(rcloneBin);
        command.add("rc");
        command.add("--url");
        command.add(StrUtil.blankToDefault(notificationConfig.getRcloneSyncRcUrl(), "http://127.0.0.1:5572"));

        String rcUser = notificationConfig.getRcloneSyncRcUser();
        String rcPass = notificationConfig.getRcloneSyncRcPass();
        if (StrUtil.isAllNotBlank(rcUser, rcPass)) {
            command.add("--user");
            command.add(rcUser);
            command.add("--pass");
            command.add(rcPass);
        }

        command.add(api);
        command.addAll(args);
        return toRemoteCommand(command);
    }

    private static long getLong(JsonObject jsonObject, String key) {
        if (Objects.isNull(jsonObject) || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return jsonObject.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean getBoolean(JsonObject jsonObject, String key) {
        if (Objects.isNull(jsonObject) || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return false;
        }
        try {
            return jsonObject.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String getString(JsonObject jsonObject, String key) {
        if (Objects.isNull(jsonObject) || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return "";
        }
        try {
            return jsonObject.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String toRemoteCommand(List<String> command) {
        return command.stream()
                .map(RcloneSyncTaskService::shQuote)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    private static String shQuote(String value) {
        if (Objects.isNull(value)) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static ExecResult execSsh(NotificationConfig notificationConfig, String remoteCommand, int timeoutSeconds) {
        String sshBin = StrUtil.blankToDefault(notificationConfig.getRcloneSyncSshBin(), "ssh");
        int sshPort = ObjectUtil.defaultIfNull(notificationConfig.getRcloneSyncSshPort(), 22);
        String sshUser = notificationConfig.getRcloneSyncSshUser();
        String sshHost = notificationConfig.getRcloneSyncSshHost();
        String userHost = StrUtil.isBlank(sshUser) ? sshHost : sshUser + "@" + sshHost;

        List<String> cmd = new ArrayList<>();
        cmd.add(sshBin);
        cmd.add("-p");
        cmd.add(String.valueOf(sshPort));
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("ConnectTimeout=8");
        cmd.add(userHost);
        cmd.add(remoteCommand);

        Process process = null;
        try {
            process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean ok = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                process.destroyForcibly();
                return new ExecResult(124, "ssh timeout");
            }

            String output;
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(line);
                }
                output = sb.toString().trim();
            }
            return new ExecResult(process.exitValue(), output);
        } catch (Exception e) {
            return new ExecResult(500, e.getMessage());
        } finally {
            if (Objects.nonNull(process)) {
                process.destroy();
            }
        }
    }

    private static void trimHistory() {
        List<RcloneSyncTask> list = new ArrayList<>(TASK_MAP.values());
        if (list.size() <= MAX_HISTORY) {
            return;
        }
        list.sort(Comparator.comparingLong(RcloneSyncTask::getCreateTime).reversed());
        for (int i = MAX_HISTORY; i < list.size(); i++) {
            TASK_MAP.remove(list.get(i).getId());
        }
    }

    private static String shortOutput(String output) {
        output = StrUtil.blankToDefault(output, "");
        output = output.replace("\r", " ").replace("\n", " ").trim();
        int max = 220;
        if (output.length() <= max) {
            return output;
        }
        return output.substring(0, max) + "...";
    }

    private static void applyNetSample(List<RcloneSyncTask> list, NetSample sample) {
        if (CollUtil.isEmpty(list) || Objects.isNull(sample)) {
            return;
        }
        for (RcloneSyncTask task : list) {
            task.setNetIface(sample.iface())
                    .setNetRxBps(sample.rxBps())
                    .setNetTxBps(sample.txBps())
                    .setNetSampleTime(sample.sampleTime());
        }
    }

    private static NetSample sampleRemoteNet(NotificationConfig notificationConfig) {
        String key = netSampleKey(notificationConfig);
        if (StrUtil.isBlank(key)) {
            log.debug("RcloneSync net sample skipped: invalid ssh host/user/port");
            return null;
        }
        log.debug("RcloneSync net sample start: key={}", key);

        String remoteCommand = "cat /proc/net/dev";
        ExecResult execResult = execSsh(notificationConfig, remoteCommand, 8);
        if (execResult.exitCode != 0 || StrUtil.isBlank(execResult.output)) {
            log.debug("RcloneSync net sample empty/fail: exit={} output={}", execResult.exitCode, shortOutput(execResult.output));
            log.debug("RcloneSync net sample failed: exit={} output={}", execResult.exitCode, shortOutput(execResult.output));
            return null;
        }

        NetSnapshot current = parseNetSnapshot(execResult.output);
        if (Objects.isNull(current)) {
            log.debug("RcloneSync net sample parse miss: exit={} output={}", execResult.exitCode, shortOutput(execResult.output));
            log.debug("RcloneSync net sample parse failed: output={}", shortOutput(execResult.output));
            return null;
        }
        log.debug("RcloneSync net sample raw: iface={} rxBytes={} txBytes={} output={}",
                current.iface(), current.rxBytes(), current.txBytes(), shortOutput(execResult.output));

        long now = System.currentTimeMillis();
        NetSnapshot snapshot = new NetSnapshot(current.iface(), current.rxBytes(), current.txBytes(), now);
        NetSnapshot last = NET_SNAPSHOT_MAP.put(key, snapshot);
        if (Objects.isNull(last)) {
            return new NetSample(snapshot.iface(), 0L, 0L, now);
        }

        long deltaMs = now - last.sampleTime();
        if (deltaMs <= 0) {
            return new NetSample(snapshot.iface(), 0L, 0L, now);
        }
        long rxDelta = Math.max(0L, snapshot.rxBytes() - last.rxBytes());
        long txDelta = Math.max(0L, snapshot.txBytes() - last.txBytes());
        long rxBps = rxDelta * 1000L / deltaMs;
        long txBps = txDelta * 1000L / deltaMs;
        return new NetSample(snapshot.iface(), rxBps, txBps, now);
    }

    private static NetSnapshot parseNetSnapshot(String output) {
        if (StrUtil.isBlank(output)) {
            return null;
        }
        List<String> lines = StrUtil.split(output, '\n');
        for (String line : lines) {
            String value = StrUtil.trim(line);
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (!value.contains(":")) {
                continue;
            }
            String iface = StrUtil.trim(StrUtil.subBefore(value, ":", false));
            if (StrUtil.isBlank(iface) || "lo".equals(iface)) {
                continue;
            }
            String tail = StrUtil.trim(StrUtil.subAfter(value, ":", false));
            String[] parts = tail.split("\\s+");
            if (parts.length < 16) {
                continue;
            }
            // /proc/net/dev: rx bytes at col 1, tx bytes at col 9 after ':'
            try {
                long rx = Long.parseLong(parts[0]);
                long tx = Long.parseLong(parts[8]);
                return new NetSnapshot(iface, rx, tx, 0L);
            } catch (Exception ignored) {
                // Try next line/interface
            }
        }
        return null;
    }

    private static String netSampleKey(NotificationConfig notificationConfig) {
        String host = StrUtil.trim(notificationConfig.getRcloneSyncSshHost());
        if (StrUtil.isBlank(host)) {
            return "";
        }
        String user = StrUtil.trim(notificationConfig.getRcloneSyncSshUser());
        int port = ObjectUtil.defaultIfNull(notificationConfig.getRcloneSyncSshPort(), 22);
        return StrUtil.blankToDefault(user, "-") + "@" + host + ":" + port;
    }

    private record ExecResult(int exitCode, String output) {
    }

    private record NetSnapshot(String iface, long rxBytes, long txBytes, long sampleTime) {
    }

    private record NetSample(String iface, long rxBps, long txBps, long sampleTime) {
    }
}

