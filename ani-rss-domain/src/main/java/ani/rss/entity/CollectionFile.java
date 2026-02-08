package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 合集文件
 */
@Data
@Accessors(chain = true)
public class CollectionFile implements Serializable {
    /**
     * 相对路径（含子目录）
     */
    private String path;

    /**
     * 文件名
     */
    private String name;

    /**
     * 文件大小
     */
    private Long size;

    /**
     * 是否目录
     */
    private Boolean isDir;

    /**
     * 是否被选中保留
     */
    private Boolean selected;

    /**
     * 新文件名（重命名后）
     */
    private String newName;

    /**
     * 集数（自动识别）
     */
    private Double episode;

    /**
     * 父目录路径
     */
    private String parentPath;
    
    /**
     * 子文件/目录（用于树形结构）
     */
    private List<CollectionFile> children;
    
    /**
     * 层级深度（用于缩进显示）
     */
    private Integer level;
    
    /**
     * 是否展开（用于树形结构）
     */
    private Boolean expanded;
    
    /**
     * 原始文件名（用于重命名预览）
     */
    private String originalName;
    
    /**
     * 是否是根目录
     */
    private Boolean isRoot;
    
    /**
     * 目录新名称（用于编辑目录名）
     */
    private String directoryNewName;
}
