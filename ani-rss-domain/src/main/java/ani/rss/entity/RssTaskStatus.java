package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

@Data
@Accessors(chain = true)
public class RssTaskStatus implements Serializable {
    private Boolean running;
    private String currentTask;
    private Integer queueSize;
    private List<String> queue;
    private Long startTime;
    private Long lastFinishTime;
}

