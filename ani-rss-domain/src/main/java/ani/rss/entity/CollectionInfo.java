package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 合集
 */
@Data
@Accessors(chain = true)
public class CollectionInfo implements Serializable {
    /**
     * 种子文件 base64
     */
    private String torrent;

    /**
     * 磁力链接
     */
    private String magnet;

    /**
     * 任务ID（磁力链接模式）
     */
    private String taskId;

    /**
     * 文件列表（磁力链接整理时使用）
     */
    private List<CollectionFile> files;

    /**
     * 订阅
     */
    private Ani ani;

    /**
     * bgm
     */
    private BgmInfo bgmInfo;
    
    /**
     * 是否保留目录结构
     */
    private Boolean keepDirectoryStructure;
    
    /**
     * 目录重命名映射
     */
    private Map<String, String> directoryRenames;
}
