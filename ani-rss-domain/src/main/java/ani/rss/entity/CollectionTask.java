package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 合集下载任务
 */
@Data
@Accessors(chain = true)
public class CollectionTask implements Serializable {
    /**
     * 任务ID
     */
    private String id;

    /**
     * 磁力链接
     */
    private String magnet;

    /**
     * 临时下载路径
     */
    private String tempPath;

    /**
     * 最终保存路径
     */
    private String finalPath;

    /**
     * 状态：downloading / completed / failed / organizing
     */
    private String status;

    /**
     * OpenList 任务ID
     */
    private String tid;

    /**
     * 文件列表
     */
    private List<CollectionFile> files;

    /**
     * 下载中的暂存文件树
     */
    private List<CollectionFile> tempFiles;

    /**
     * 订阅信息
     */
    private Ani ani;

    /**
     * 创建时间
     */
    private Long createTime;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 下载进度 (0-100)
     */
    private Integer progress;
    
    /**
     * 是否保留目录结构
     */
    private Boolean keepDirectoryStructure;
    
    /**
     * 目录重命名映射 (旧路径 -> 新名称)
     */
    private Map<String, String> directoryRenameMap;
}
