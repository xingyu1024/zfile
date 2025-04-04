package im.zhaojun.zfile.module.filter.service;

import im.zhaojun.zfile.core.util.CollectionUtils;
import im.zhaojun.zfile.core.util.FileUtils;
import im.zhaojun.zfile.core.util.PatternMatcherUtils;
import im.zhaojun.zfile.core.util.StringUtils;
import im.zhaojun.zfile.module.filter.mapper.FilterConfigMapper;
import im.zhaojun.zfile.module.filter.model.entity.FilterConfig;
import im.zhaojun.zfile.module.storage.event.StorageSourceCopyEvent;
import im.zhaojun.zfile.module.storage.event.StorageSourceDeleteEvent;
import im.zhaojun.zfile.module.storage.model.enums.FileOperatorTypeEnum;
import im.zhaojun.zfile.module.user.service.UserStorageSourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 存储源过滤规则 Service
 *
 * @author zhaojun
 */
@Slf4j
@Service
@CacheConfig(cacheNames = "filterConfig")
public class FilterConfigService {

    @Resource
    private FilterConfigMapper filterConfigMapper;

    @Resource
    private UserStorageSourceService userStorageSourceService;

    /**
     * 根据存储源 ID 获取存储源配置列表
     *
     * @param   storageId
     *          存储源 ID
     *
     * @return  存储源过滤规则配置列表
     */
    @Cacheable(key = "'filter-base-' + #storageId",
            condition = "#storageId != null")
    public List<FilterConfig> findByStorageId(Integer storageId) {
        return filterConfigMapper.findByStorageId(storageId);
    }


    /**
     * 获取所有类型为禁止访问的过滤规则
     *
     * @param   storageId
     *          存储 ID
     *
     * @return  禁止访问的过滤规则列表
     */
    @Cacheable(key = "'filter-inaccessible-' + #storageId",
            condition = "#storageId != null")
    public List<FilterConfig> findByStorageIdAndInaccessible(Integer storageId) {
        return filterConfigMapper.findByStorageIdAndInaccessible(storageId);
    }


    /**
     * 获取所有类型为禁止下载的过滤规则
     *
     * @param   storageId
     *          存储 ID
     *
     * @return  禁止下载的过滤规则列表
     */
    @Cacheable(key = "'filter-disable-download-' + #storageId",
            condition = "#storageId != null")
    public List<FilterConfig> findByStorageIdAndDisableDownload(Integer storageId) {
        return filterConfigMapper.findByStorageIdAndDisableDownload(storageId);
    }


    /**
     * 批量保存存储源过滤规则配置, 会先删除之前的所有配置(在事务中运行)
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   filterConfigList
     *          存储源过滤规则配置列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchSave(Integer storageId, List<FilterConfig> filterConfigList) {
        ((FilterConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
        log.info("更新存储源 ID 为 {} 的过滤规则 {} 条", storageId, filterConfigList.size());

        for (FilterConfig filterConfig : filterConfigList) {
            filterConfig.setId(null);
            filterConfig.setStorageId(storageId);
            filterConfigMapper.insert(filterConfig);

            if (log.isDebugEnabled()) {
                log.debug("新增过滤规则, 存储源 ID: {}, 表达式: {}, 描述: {}, 隐藏模式: {}",
                        filterConfig.getStorageId(), filterConfig.getExpression(),
                        filterConfig.getDescription(), filterConfig.getMode().getValue());
            }
        }
    }


    /**
     * 根据存储源 ID 删除所有过滤规则配置
     *
     * @param   storageId
     *          存储源 ID
     */
    @Caching(evict = {
            @CacheEvict(key = "'filter-base-' + #storageId"),
            @CacheEvict(key = "'filter-inaccessible-' + #storageId"),
            @CacheEvict(key = "'filter-disable-download-' + #storageId")
    })
    public int deleteByStorageId(Integer storageId) {
        int deleteSize = filterConfigMapper.deleteByStorageId(storageId);
        log.info("删除存储源 ID 为 {} 的过滤规则 {} 条", storageId, deleteSize);
        return deleteSize;
    }

    /**
     * 监听存储源删除事件，根据存储源 id 删除相关的过滤条件设置
     *
     * @param   storageSourceDeleteEvent
     *          存储源删除事件
     */
    @EventListener
    public void onStorageSourceDelete(StorageSourceDeleteEvent storageSourceDeleteEvent) {
        Integer storageId = storageSourceDeleteEvent.getId();
        int updateRows = ((FilterConfigService) AopContext.currentProxy()).deleteByStorageId(storageId);
        if (log.isDebugEnabled()) {
            log.debug("删除存储源 [id {}, name: {}, type: {}] 时，关联删除存储源过滤条件设置 {} 条",
                    storageId,
                    storageSourceDeleteEvent.getName(),
                    storageSourceDeleteEvent.getType().getDescription(),
                    updateRows);
        }

    }

    /**
     * 判断访问的路径是否是不允许访问的
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   path
     *          请求路径
     *
     */
    public boolean checkFileIsInaccessible(Integer storageId, String path) {
        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageIdAndInaccessible(storageId);
        return testPattern(storageId, filterConfigList, path);
    }


    /**
     * 指定存储源下的文件名称, 根据过滤表达式判断是否会显示, 如果符合任意一条表达式, 表示隐藏则返回 true, 反之表示不隐藏则返回 false.
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   fileName
     *          文件名
     *
     * @return  是否是隐藏文件夹
     */
    public boolean checkFileIsHidden(Integer storageId, String fileName) {
        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageId(storageId);
        return testPattern(storageId, filterConfigList, fileName);
    }


    /**
     * 指定存储源下的文件名称, 根据过滤表达式判断文件名和所在路径是否禁止下载, 如果符合任意一条表达式, 则返回 true, 反之则返回 false.
     *
     * @param   storageId
     *          存储源 ID
     *
     * @param   fileName
     *          文件名
     *
     * @return  是否显示
     */
    public boolean checkFileIsDisableDownload(Integer storageId, String fileName) {
        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageIdAndDisableDownload(storageId);
        String filePath = FileUtils.getParentPath(fileName);
        if (StringUtils.isEmpty(filePath)) {
            return testPattern(storageId, filterConfigList, fileName);
        } else {
            return testPattern(storageId, filterConfigList, fileName) || testPattern(storageId, filterConfigList, filePath);
        }
    }


    /**
     * 根据规则表达式和测试字符串进行匹配，如测试字符串和其中一个规则匹配上，则返回 true，反之返回 false。
     *
     * @param   patternList
     *          规则列表
     *
     * @param   test
     *
     *          测试字符串
     *
     * @return  是否显示
     */
    private boolean testPattern(Integer storageId, List<FilterConfig> patternList, String test) {
        // 如果规则列表为空, 则表示不需要过滤, 直接返回 false
        if (CollectionUtils.isEmpty(patternList)) {
            if (log.isDebugEnabled()) {
                log.debug("过滤规则列表为空, 存储源 ID: {}, 测试字符串: {}", storageId, test);
            }
            return false;
        }

        // 判断是否需要忽略文件隐藏校验
        boolean isIgnoreHidden = userStorageSourceService.hasCurrentUserStorageOperatorPermission(storageId, FileOperatorTypeEnum.IGNORE_HIDDEN);
        if (isIgnoreHidden) {
            if (log.isDebugEnabled()) {
                log.debug("权限配置忽略过滤规则, 存储源 ID: {}, 测试字符串: {}", storageId, test);
            }
            return false;
        }

        // 校验表达式
        for (FilterConfig filterConfig : patternList) {
            String expression = filterConfig.getExpression();

            if (StringUtils.isEmpty(expression)) {
                if (log.isDebugEnabled()) {
                    log.debug("存储源 {} 过滤文件测试表达式: {}, 测试字符串: {}, 表达式为空，跳过该规则校验", storageId, expression, test);
                }
                continue;
            }

            try {
                boolean match = PatternMatcherUtils.testCompatibilityGlobPattern(expression, test);

                if (log.isDebugEnabled()) {
                    log.debug("存储源 {} 过滤文件测试表达式: {}, 测试字符串: {}, 匹配结果: {}", storageId, expression, test, match);
                }

                if (match) {
                    return true;
                }
            } catch (Exception e) {
                log.error("存储源 {} 过滤文件测试表达式: {}, 测试字符串: {}, 匹配异常，跳过该规则.", storageId, expression, test, e);
            }
        }

        return false;
    }

    /**
     * 监听存储源复制事件, 复制存储源的过滤条件设置到新的存储源
     *
     * @param   storageSourceCopyEvent
     *          存储源复制事件
     */
    @EventListener
    public void onStorageSourceCopy(StorageSourceCopyEvent storageSourceCopyEvent) {
        Integer fromId = storageSourceCopyEvent.getFromId();
        Integer newId = storageSourceCopyEvent.getNewId();

        List<FilterConfig> filterConfigList = ((FilterConfigService) AopContext.currentProxy()).findByStorageId(fromId);

        filterConfigList.forEach(filterConfig -> {
            FilterConfig newFilterConfig = new FilterConfig();
            BeanUtils.copyProperties(filterConfig, newFilterConfig);
            newFilterConfig.setId(null);
            newFilterConfig.setStorageId(newId);
            filterConfigMapper.insert(newFilterConfig);
        });

        log.info("复制存储源 ID 为 {} 的存储源过滤条件设置到存储源 ID 为 {} 成功, 共 {} 条", fromId, newId, filterConfigList.size());
    }

}