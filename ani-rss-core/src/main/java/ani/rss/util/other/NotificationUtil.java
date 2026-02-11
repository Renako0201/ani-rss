package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.notification.*;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReflectUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class NotificationUtil {
    private static final ExecutorService EXECUTOR_SERVICE = ExecutorBuilder.create()
            .setCorePoolSize(1)
            .setMaxPoolSize(1)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();
    private static final ExecutorService DOWNLOAD_END_EXECUTOR_SERVICE = ExecutorBuilder.create()
            .setCorePoolSize(1)
            .setMaxPoolSize(1)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();
    private static final AtomicInteger DOWNLOAD_END_PENDING = new AtomicInteger(0);

    public final static Map<NotificationTypeEnum, Class<? extends BaseNotification>>
            NOTIFICATION_MAP =
            Map.of(
                    NotificationTypeEnum.EMBY_REFRESH, EmbyRefreshNotification.class,
                    NotificationTypeEnum.MAIL, MailNotification.class,
                    NotificationTypeEnum.SERVER_CHAN, ServerChanNotification.class,
                    NotificationTypeEnum.SYSTEM, SystemNotification.class,
                    NotificationTypeEnum.TELEGRAM, TelegramNotification.class,
                    NotificationTypeEnum.WEB_HOOK, WebHookNotification.class,
                    NotificationTypeEnum.SHELL, ShellNotification.class,
                    NotificationTypeEnum.FILE_MOVE, FileMoveNotification.class,
                    NotificationTypeEnum.OPEN_LIST_UPLOAD, OpenListUploadNotification.class,
                    NotificationTypeEnum.RCLONE_SYNC, RcloneSyncNotification.class
            );

    /**
     * 发送通知
     *
     * @param config
     * @param ani
     * @param text
     * @param notificationStatusEnum
     */
    public static synchronized void send(Config config, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        Boolean isMessage = ani.getMessage();

        if (!isMessage) {
            // 未开启此订阅通知
            return;
        }

        List<NotificationConfig> notificationConfigList = config.getNotificationConfigList();
        notificationConfigList = notificationConfigList
                .stream()
                .sorted(Comparator.comparingLong(NotificationConfig::getSort))
                .toList();

        if (NotificationStatusEnum.DOWNLOAD_END == notificationStatusEnum) {
            // 下载完成事件使用独立串行队列。
            // 并且强制将 RCLONE_SYNC 放到最后，避免先同步后清理导致临时目录被同步。
            List<NotificationConfig> enabledConfigs = notificationConfigList.stream()
                    .filter(NotificationConfig::getEnable)
                    .filter(notificationConfig -> notificationConfig.getStatusList().contains(notificationStatusEnum))
                    .filter(notificationConfig -> NOTIFICATION_MAP.containsKey(notificationConfig.getNotificationType()))
                    .collect(Collectors.toList());
            if (enabledConfigs.isEmpty()) {
                return;
            }
            List<NotificationConfig> orderedConfigs = enabledConfigs.stream()
                    .filter(notificationConfig -> NotificationTypeEnum.RCLONE_SYNC != notificationConfig.getNotificationType())
                    .collect(Collectors.toList());
            orderedConfigs.addAll(
                    enabledConfigs.stream()
                            .filter(notificationConfig -> NotificationTypeEnum.RCLONE_SYNC == notificationConfig.getNotificationType())
                            .toList()
            );

            DOWNLOAD_END_PENDING.incrementAndGet();
            DOWNLOAD_END_EXECUTOR_SERVICE.execute(() -> {
                try {
                    orderedConfigs.forEach(notificationConfig ->
                            sendWithRetry(notificationConfig, ani, text, notificationStatusEnum));
                } finally {
                    DOWNLOAD_END_PENDING.decrementAndGet();
                }
            });
            return;
        }

        for (NotificationConfig notificationConfig : notificationConfigList) {
            boolean enable = notificationConfig.getEnable();
            NotificationTypeEnum notificationType = notificationConfig.getNotificationType();
            List<NotificationStatusEnum> statusList = notificationConfig.getStatusList();

            if (!enable) {
                // 未开启
                continue;
            }

            if (!statusList.contains(notificationStatusEnum)) {
                // 未启用 通知状态
                continue;
            }

            if (!NOTIFICATION_MAP.containsKey(notificationType)) {
                return;
            }

            EXECUTOR_SERVICE.execute(() -> sendWithRetry(notificationConfig, ani, text, notificationStatusEnum));
        }
    }

    private static void sendWithRetry(
            NotificationConfig notificationConfig,
            Ani ani,
            String text,
            NotificationStatusEnum notificationStatusEnum
    ) {
        NotificationTypeEnum notificationType = notificationConfig.getNotificationType();
        Class<? extends BaseNotification> aClass = NOTIFICATION_MAP.get(notificationType);
        int retry = notificationConfig.getRetry();

        BaseNotification baseNotification = ReflectUtil.newInstance(aClass);
        int currentRetry = 0;
        do {
            if (currentRetry > 0) {
                log.warn("通知失败 正在重试 第{}次 {}", currentRetry, aClass.getName());
            }
            try {
                baseNotification.send(notificationConfig, ani, text, notificationStatusEnum);
                return;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            currentRetry += 1;
            ThreadUtil.sleep(1000);
        } while (currentRetry < retry);
    }

    public static boolean hasPendingDownloadEndTasks() {
        return DOWNLOAD_END_PENDING.get() > 0;
    }
}
