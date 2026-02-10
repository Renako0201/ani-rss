package ani.rss.task;

import ani.rss.commons.ExceptionUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.RssTaskStatus;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.thread.ThreadUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RSS
 */
@Slf4j
public class RssTask extends Thread {
    public static final AtomicBoolean download = new AtomicBoolean(false);
    private static final AtomicLong START_TIME = new AtomicLong(0L);
    private static final AtomicLong LAST_FINISH_TIME = new AtomicLong(0L);
    private static final AtomicReference<String> CURRENT_TASK = new AtomicReference<>("");
    private static final List<String> QUEUE = new ArrayList<>();

    private final AtomicBoolean loop;

    public RssTask(AtomicBoolean loop) {
        this.loop = loop;
    }

    public static void download(AtomicBoolean loop) {
        try {
            if (!TorrentUtil.login()) {
                return;
            }

            List<String> queue = new ArrayList<>();
            for (Ani ani : AniUtil.ANI_LIST) {
                if (!Boolean.TRUE.equals(ani.getEnable())) {
                    continue;
                }
                queue.add(taskName(ani));
            }
            synchronized (QUEUE) {
                QUEUE.clear();
                QUEUE.addAll(queue);
            }

            for (Ani ani : AniUtil.ANI_LIST) {
                if (!loop.get()) {
                    return;
                }

                if (!AniUtil.ANI_LIST.contains(ani)) {
                    continue;
                }

                String title = ani.getTitle();
                if (!Boolean.TRUE.equals(ani.getEnable())) {
                    log.debug("{} 未启用", title);
                    continue;
                }

                String taskName = taskName(ani);
                CURRENT_TASK.set(taskName);
                try {
                    DownloadService.downloadAni(ani);
                } catch (Exception e) {
                    String message = ExceptionUtils.getMessage(e);
                    log.error("{} {}", title, message);
                    log.error(message, e);
                } finally {
                    synchronized (QUEUE) {
                        QUEUE.remove(taskName);
                    }
                }

                // Avoid request bursts over a short period.
                ThreadUtil.sleep(500);
            }
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error(message, e);
        } finally {
            CURRENT_TASK.set("");
            synchronized (QUEUE) {
                QUEUE.clear();
            }
            LAST_FINISH_TIME.set(System.currentTimeMillis());
            download.set(false);
        }
    }

    public static void sync() {
        synchronized (download) {
            if (download.get()) {
                throw new RuntimeException("存在未完成任务，请等待...");
            }
            START_TIME.set(System.currentTimeMillis());
            CURRENT_TASK.set("");
            synchronized (QUEUE) {
                QUEUE.clear();
            }
            download.set(true);
        }
    }

    public static RssTaskStatus getStatus() {
        List<String> queue;
        synchronized (QUEUE) {
            queue = new ArrayList<>(QUEUE);
        }
        return new RssTaskStatus()
                .setRunning(download.get())
                .setCurrentTask(CURRENT_TASK.get())
                .setQueue(queue)
                .setQueueSize(queue.size())
                .setStartTime(START_TIME.get())
                .setLastFinishTime(LAST_FINISH_TIME.get());
    }

    private static String taskName(Ani ani) {
        String title = ani.getTitle();
        Integer season = ani.getSeason();
        if (season == null || season <= 0) {
            return title;
        }
        return title + " S" + String.format("%02d", season);
    }

    @Override
    public void run() {
        super.setName("rss-task-thread");
        Config config = ConfigUtil.CONFIG;
        Integer sleep = config.getRssSleepMinutes();
        log.info("{} 当前设置间隔为 {} 分钟", getName(), sleep);
        while (loop.get()) {
            if (!config.getRss()) {
                log.debug("rss未启用");
                ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
                continue;
            }
            try {
                sync();
                download(loop);
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error(message, e);
            }
            ThreadUtil.sleep(sleep, TimeUnit.MINUTES);
        }
        log.info("{} 任务已停止", getName());
    }
}
