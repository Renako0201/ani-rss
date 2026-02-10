package ani.rss.notification;

import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.RcloneSyncTask;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.service.RcloneSyncTaskService;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
public class RcloneSyncNotification implements BaseNotification {

    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        if (NotificationStatusEnum.DOWNLOAD_END != notificationStatusEnum) {
            // Ignore non-download-end notifications to avoid noisy retries.
            log.debug("RcloneSync skip status: {}", notificationStatusEnum);
            return true;
        }
        Assert.isTrue(StrUtil.isNotBlank(notificationConfig.getRcloneSyncSshHost()), "RcloneSync ssh host 不能为空");
        Assert.isTrue(StrUtil.isNotBlank(notificationConfig.getRcloneSyncSourceFs()), "RcloneSync sourceFs 不能为空");
        Assert.isTrue(StrUtil.isNotBlank(notificationConfig.getRcloneSyncTargetFs()), "RcloneSync targetFs 不能为空");

        ani = ObjectUtil.clone(ani);

        String srcFsTemplate = notificationConfig.getRcloneSyncSourceFs();
        String dstFsTemplate = notificationConfig.getRcloneSyncTargetFs();
        String srcFs = replaceNotificationTemplate(ani, srcFsTemplate, text, notificationStatusEnum);
        String dstFs = replaceNotificationTemplate(ani, dstFsTemplate, text, notificationStatusEnum);

        int season = ObjectUtil.defaultIfNull(ani.getSeason(), 1);
        String seasonFormat = String.format("%02d", season);
        String episodeFormat = "";
        if (ReUtil.contains(StringEnum.SEASON_REG, text)) {
            episodeFormat = ReUtil.get(StringEnum.SEASON_REG, text, 2);
        }
        boolean collection = text.startsWith(RcloneSyncTaskService.COLLECTION_PREFIX);

        String mode = collection ? RcloneSyncTaskService.MODE_COLLECTION : RcloneSyncTaskService.MODE_EPISODE;
        RcloneSyncTask task = RcloneSyncTaskService.createTask(
                mode,
                ani.getTitle(),
                seasonFormat,
                episodeFormat,
                srcFs,
                dstFs
        );

        List<String> extraArgs = parseExtraArgs(
                replaceNotificationTemplate(
                        ani,
                        StrUtil.blankToDefault(notificationConfig.getRcloneSyncExtraArgs(), ""),
                        text,
                        notificationStatusEnum
                )
        );

        String rcApi = "sync/copy";
        if (collection && ObjectUtil.defaultIfNull(notificationConfig.getRcloneSyncCollectionUseSync(), true)) {
            rcApi = "sync/sync";
        }
        if (!collection) {
            String include = replaceNotificationTemplate(
                    ani,
                    StrUtil.blankToDefault(notificationConfig.getRcloneSyncEpisodeInclude(), ""),
                    text,
                    notificationStatusEnum
            );
            if (StrUtil.isNotBlank(include)) {
                extraArgs.add("_include=" + include);
            }
        }
        log.info("RcloneSync triggered: mode={} title={} src={} dst={} status={}",
                mode, ani.getTitle(), srcFs, dstFs, notificationStatusEnum);
        RcloneSyncTaskService.startRcTask(task, notificationConfig, rcApi, extraArgs);
        log.info("RcloneSync task done: id={} mode={} status={} src={} => dst={} msg={}",
                task.getId(), mode, task.getStatus(), srcFs, dstFs, task.getMessage());
        return true;
    }

    private List<String> parseExtraArgs(String text) {
        List<String> args = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return args;
        }
        for (String line : StrUtil.split(text, "\n", true, true)) {
            line = StrUtil.trim(line);
            if (StrUtil.isBlank(line)) {
                continue;
            }
            if (!line.contains("=")) {
                continue;
            }
            String key = StrUtil.subBefore(line, "=", false);
            String value = StrUtil.subAfter(line, "=", false);
            if (StrUtil.hasBlank(key, value)) {
                continue;
            }
            if (Objects.equals("srcFs", key) || Objects.equals("dstFs", key) || Objects.equals("_async", key) || Objects.equals("_group", key)) {
                continue;
            }
            args.add(key + "=" + value);
        }
        return args;
    }
}
