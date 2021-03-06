package org.xiaowu.behappy.product.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xiaowu.behappy.product.dto.ProductUploadDto;
import org.xiaowu.behappy.product.dto.SkuUploadDto;
import org.xiaowu.behappy.product.entity.ProductEntity;
import org.xiaowu.behappy.product.entity.SkuEntity;
import org.xiaowu.behappy.product.mapper.ProductMapper;
import org.xiaowu.behappy.product.vo.*;
import org.xiaowu.common.mybatis.base.BaseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author xiaowu
 * @date 16:01
 * @apiNote
 */
@Service
@AllArgsConstructor
public class ProdService extends ServiceImpl<ProductMapper, ProductEntity> implements IService<ProductEntity> {

    private final SkuService skuService;

    public Page<ProductVo> pageByCategoryId(Long categoryId, Page page) {
        // 查询正常状态下的商品
        LambdaQueryWrapper<ProductEntity> queryWrapper = Wrappers.<ProductEntity>lambdaQuery().
                select(ProductEntity::getProdId,
                        ProductEntity::getProdName,
                        ProductEntity::getPrice,
                        ProductEntity::getBrief,
                        ProductEntity::getPic,
                        ProductEntity::getCategoryId).
                eq(ProductEntity::getStatus, 1).
                eq(ObjectUtil.isNotNull(categoryId), ProductEntity::getCategoryId, categoryId);

        Page voPage = baseMapper.selectPage(page, queryWrapper);
        List<ProductEntity> records = voPage.getRecords();
        List<ProductVo> productVos = BeanUtil.copyToList(records, ProductVo.class, CopyOptions.create());
        voPage.setRecords(productVos);
        return voPage;
    }

    public ProdInfoVo getProductByProdId(Long prodId) {
        ProductEntity productEntity = baseMapper.selectById(prodId);
        if (ObjectUtil.isNull(productEntity)) {
            return null;
        }

        List<SkuEntity> skuEntities = skuService.listById(prodId);
        // 启用的sku列表
        List<SkuEntity> skuEntityList = skuEntities.stream().filter(skuEntity -> skuEntity.getStatus().equals(1)).collect(Collectors.toList());
        List<SkuInfoVo> skuInfoVos = BeanUtil.copyToList(skuEntityList, SkuInfoVo.class, CopyOptions.create());
        ProdInfoVo prodInfoVo = BeanUtil.copyProperties(productEntity, ProdInfoVo.class);
        prodInfoVo.setSkuList(skuInfoVos);
        return prodInfoVo;

    }

    public Page<SearchProdVo> searchProdPageByProdName(Page page, String prodName, Integer sort, Integer orderBy) {

        LambdaQueryWrapper<ProductEntity> queryWrapper = Wrappers.<ProductEntity>lambdaQuery().select(
                        ProductEntity::getProdId,
                        ProductEntity::getPic,
                        ProductEntity::getProdName,
                        ProductEntity::getPrice,
                        ProductEntity::getBrief
                ).
                eq(ProductEntity::getStatus, 1).
                like(StrUtil.isNotBlank(prodName), ProductEntity::getProdName, prodName).
                groupBy(ProductEntity::getProdId).
                orderBy(ObjectUtil.equal(sort, 0), ObjectUtil.equal(orderBy, 0), BaseEntity::getUpdateTime).
                orderBy(ObjectUtil.equal(sort, 1), ObjectUtil.equal(orderBy, 0), ProductEntity::getSoldNum).
                orderBy(ObjectUtil.equal(sort, 2), ObjectUtil.equal(orderBy, 0), ProductEntity::getPrice);

        Page voPage = baseMapper.selectPage(page, queryWrapper);
        List<SearchProdVo> searchProdVos = BeanUtil.copyToList(voPage.getRecords(), SearchProdVo.class, CopyOptions.create());
        voPage.setRecords(searchProdVos);
        return voPage;
    }

    public List<ShopProdVo> shopProdVoList(List<Long> prodIds) {
        if (CollUtil.isEmpty(prodIds)) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<ProductEntity> queryWrapper = Wrappers.<ProductEntity>lambdaQuery().
                select(ProductEntity::getProdId, ProductEntity::getPic, ProductEntity::getBrief, ProductEntity::getStatus).
                eq(ProductEntity::getStatus, 1).
                in(ProductEntity::getProdId, prodIds);
        List<ProductEntity> productEntities = baseMapper.selectList(queryWrapper);
        List<ShopProdVo> shopProdVos = BeanUtil.copyToList(productEntities, ShopProdVo.class, CopyOptions.create());
        return shopProdVos;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(List<Long> prodIds) {
        // 删除商品
        removeByIds(prodIds);
        skuService.removeSkuBySkuId(prodIds);
        // todo 删除购物车
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveOrUpdate(ProductUploadDto productUploadDto) {
        ProductEntity productEntity = BeanUtil.copyProperties(productUploadDto, ProductEntity.class);
        if (ObjectUtil.isNotNull(productEntity.getProdId())) {
            // 新增
            if (productEntity.getStatus() != 0) {
                productEntity.setPutAwayTime(LocalDateTime.now());
            }
            this.baseMapper.insert(productEntity);
            // insert sku
            if (CollUtil.isNotEmpty(productUploadDto.getSkuList())) {
                skuService.insertBatch(productEntity.getProdId(), productUploadDto.getSkuList());
            }
        } else {
            ProductEntity dbProduct = this.getById(productUploadDto.getProdId());
            // 更新
            if (dbProduct.getStatus() == 0 && productUploadDto.getStatus() == 1) {
                dbProduct.setPutAwayTime(LocalDateTime.now());
            }
            saveOrUpdate(dbProduct);
            List<SkuEntity> skuEntities = skuService.listById(productUploadDto.getProdId());
            // 将所有该商品的sku标记为已删除状态
            List<Long> skuIds = skuEntities.stream().map(SkuEntity::getSkuId).collect(Collectors.toList());
            skuService.removeByIds(skuIds);
            List<SkuUploadDto> skuList = productUploadDto.getSkuList();
            if (CollectionUtil.isEmpty(skuList)) {
                return;
            }
            List<SkuUploadDto> skuEntityList = new ArrayList<>();
            for (SkuUploadDto skuUploadDto : skuList) {
                skuUploadDto.setIsDelete(0);
                // 如果数据库中原有sku就更新，否者就插入
                if (skuIds.contains(skuUploadDto.getSkuId())) {
                    SkuEntity skuEntity = BeanUtil.copyProperties(skuUploadDto, SkuEntity.class);
                    skuService.updateById(skuEntity);
                } else {
                    skuEntityList.add(skuUploadDto);
                }
            }
            // 批量插入sku
            if (CollUtil.isNotEmpty(skuEntityList)) {
                skuService.insertBatch(productUploadDto.getProdId(), skuEntityList);
            }
        }
    }
}
