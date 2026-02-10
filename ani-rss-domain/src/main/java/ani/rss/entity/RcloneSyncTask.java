package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class RcloneSyncTask implements Serializable {
    private String id;
    private Long createTime;
    private Long updateTime;
    private String status;
    private String mode;
    private String title;
    private String seasonFormat;
    private String episodeFormat;
    private String srcFs;
    private String dstFs;
    private Long jobId;
    private String group;
    private Long bytes;
    private Long totalBytes;
    private Long speed;
    private Integer transferred;
    private Integer errors;
    private String netIface;
    private Long netRxBps;
    private Long netTxBps;
    private Long netSampleTime;
    private String message;
}
