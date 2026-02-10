package ani.rss.action;

import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.service.RcloneSyncTaskService;
import ani.rss.util.other.ConfigUtil;
import ani.rss.web.action.BaseAction;
import ani.rss.web.annotation.Auth;
import ani.rss.web.annotation.Path;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.Objects;

@Auth
@Slf4j
@Path("/rcloneSyncTasks")
public class RcloneSyncTaskAction implements BaseAction {
    @Override
    public void doAction(HttpServerRequest request, HttpServerResponse response) {
        String method = request.getMethod();
        if ("DELETE".equals(method)) {
            RcloneSyncTaskService.clearFinished();
            resultSuccessMsg("清理完成");
            return;
        }

        boolean refresh = ObjectUtil.defaultIfNull(Boolean.parseBoolean(request.getParam("refresh")), true);
        NotificationConfig notificationConfig = getRcloneConfig();
        log.debug("RcloneSync tasks query: refresh={} hasConfig={} host={}",
                refresh,
                Objects.nonNull(notificationConfig),
                Objects.isNull(notificationConfig) ? "-" : notificationConfig.getRcloneSyncSshHost());
        resultSuccess(RcloneSyncTaskService.list(refresh, notificationConfig));
    }

    private NotificationConfig getRcloneConfig() {
        Config config = ConfigUtil.CONFIG;
        return config.getNotificationConfigList()
                .stream()
                .filter(it -> Boolean.TRUE.equals(it.getEnable()))
                .filter(it -> NotificationTypeEnum.RCLONE_SYNC == it.getNotificationType())
                .sorted(Comparator.comparingLong(NotificationConfig::getSort))
                .findFirst()
                .orElse(null);
    }
}


